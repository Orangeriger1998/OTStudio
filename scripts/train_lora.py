"""OT Studio LoRA training helper. Python 3.11+, standard library only.

prepare is local. submit requires explicit upload and paid-training flags.
status and cancel operate only on a durable training ID, never resubmitting.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import getpass
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import ssl
import sys
import time
from urllib.parse import urlsplit
import uuid
import zipfile

API_HOST = "api.replicate.com"
TRAINER = "ostris/flux-dev-lora-trainer"
VERSION = "26dce37af90b9d997eeb970d92e47de3064d46c300504ae376c75bef6a9022d2"
MAX_INPUT = 95 * 1024 * 1024
MAX_WEIGHTS = 2 * 1024 * 1024 * 1024
MODEL = re.compile(r"[A-Za-z0-9_-]+/[A-Za-z0-9_-]+")
REMOTE_ID = re.compile(r"[A-Za-z0-9_-]{1,160}")
# Replicate file IDs contain dots (seen 2026-10-03). Dots are allowed, but never as a path step.
FILE_ID = re.compile(r"[A-Za-z0-9_-][A-Za-z0-9._-]{0,254}")


def valid_file_id(value):
    return isinstance(value, str) and bool(FILE_ID.fullmatch(value)) and ".." not in value and not value.endswith(".")
TERMINAL = {"succeeded", "failed", "canceled"}


class TrainingError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise TrainingError(message)


def digest(path):
    with open(path, "rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def atomic_json(path, value):
    path = Path(path)
    temp = path.with_name(path.name + ".tmp-" + uuid.uuid4().hex)
    try:
        with open(temp, "x", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=True, indent=2, allow_nan=False)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp, path)
    finally:
        temp.unlink(missing_ok=True)


@contextmanager
def job_lock(folder):
    """OS advisory lock is released if this process exits or is interrupted."""
    with open(Path(folder) / ".operation.lock", "a+b") as lock:
        if os.fstat(lock.fileno()).st_size == 0:
            lock.write(b"0")
            lock.flush()
        lock.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            raise TrainingError("Another operation is using this job folder.") from None
        try:
            yield
        finally:
            lock.seek(0)
            if os.name == "nt":
                msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(lock, fcntl.LOCK_UN)


def load_job(folder):
    path = Path(folder) / "job.json"
    require(path.stat().st_size < 32768, "Job record is too large.")
    job = json.loads(path.read_text(encoding="utf-8"))
    require(job.get("format") == "otstudio-training-v1" and job.get("trainer_version") == VERSION, "Unsupported job record or trainer version.")
    allowed = {"format", "trainer_version", "name", "destination", "trigger_word", "steps", "lora_rank", "photo_count", "zip_sha256", "status", "file_id", "file_url", "training_id", "reference", "weights_file", "weights_sha256"}
    require(set(job) <= allowed, "Job record has unknown fields.")
    require(MODEL.fullmatch(job.get("destination", "")), "Invalid destination.")
    require(isinstance(job.get("name"), str) and 1 <= len(job["name"].strip()) <= 80, "Invalid saved LoRA name.")
    require(isinstance(job.get("trigger_word"), str) and re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,79}", job["trigger_word"]), "Invalid saved trigger word.")
    for key, low, high in (("steps", 3, 6000), ("lora_rank", 1, 128), ("photo_count", 1, 20)):
        require(type(job.get(key)) is int and low <= job[key] <= high, "Invalid saved training setting: " + key)
    require(isinstance(job.get("zip_sha256"), str) and re.fullmatch(r"[a-f0-9]{64}", job["zip_sha256"]), "Invalid archive checksum.")
    require(job.get("status") in {"prepared", "uploaded", "submitting", "uncertain", "starting", "processing"} | TERMINAL, "Invalid training state.")
    return job


def save_job(folder, job):
    atomic_json(Path(folder) / "job.json", job)


def prepare(photos, folder, name, destination, trigger="TOK", steps=1000, rank=16):
    require(MODEL.fullmatch(destination), "Destination must be an existing private Replicate owner/model.")
    require(1 <= len(name.strip()) <= 80 and not any(ord(c) < 32 for c in name), "Use a name of 1-80 characters.")
    require(re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,79}", trigger), "Use a trigger of 1-80 letters, digits, or underscores, starting with a letter.")
    require(3 <= steps <= 6000 and 1 <= rank <= 128, "Steps must be 3-6000 and rank 1-128.")
    folder = Path(folder).resolve()
    if isinstance(photos, (list, tuple)):
        # The desktop trainer passes the chosen files directly; they may come from several folders.
        images = [Path(p).resolve() for p in photos]
        require(all(p.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"} for p in images), "Choose JPG, PNG, or WebP photos only.")
        require(len({p for p in images}) == len(images), "The same photo was chosen twice.")
    else:
        photos = Path(photos).resolve()
        require(photos.is_dir(), "The photo folder does not exist.")
        images = sorted(p for p in photos.iterdir() if p.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"})
    require(1 <= len(images) <= 20, "Select between 1 and 20 JPG, PNG, or WebP photos. The trainer recommends at least 10.")
    total, data = 0, []
    for index, photo in enumerate(images, 1):
        require(photo.is_file() and not photo.is_symlink(), "Photos must be ordinary files, not links or directories.")
        require(0 < photo.stat().st_size <= 20 * 1024 * 1024, "Each photo must be nonempty and at most 20 MiB.")
        raw = photo.read_bytes()
        ext = photo.suffix.lower()
        valid = ((ext in {".jpg", ".jpeg"} and raw.startswith(b"\xff\xd8\xff")) or
                 (ext == ".png" and raw.startswith(b"\x89PNG\r\n\x1a\n")) or
                 (ext == ".webp" and raw.startswith(b"RIFF") and raw[8:12] == b"WEBP"))
        require(valid, "A photo does not match its JPG/PNG/WebP file signature.")
        total += len(raw)
        require(total <= MAX_INPUT, "Training photos and captions must total at most 95 MiB.")
        data.append((f"photo-{index:02d}{ext}", raw))
        caption = photo.with_suffix(".txt")
        if caption.exists():
            require(caption.is_file() and not caption.is_symlink() and caption.stat().st_size <= 16000, "Caption must be an ordinary UTF-8 file of at most 16 KB.")
            text = caption.read_text(encoding="utf-8")
            require(text.strip() and "\x00" not in text, "Caption is empty or contains invalid characters.")
            encoded = text.encode("utf-8")
            total += len(encoded)
            require(total <= MAX_INPUT, "Training photos and captions exceed 95 MiB.")
            data.append((f"photo-{index:02d}.txt", encoded))
    require(not folder.exists(), "Choose a new job folder. Existing jobs are never overwritten.")
    folder.mkdir(parents=True)
    archive = folder / "photos.zip"
    with zipfile.ZipFile(archive, "x", compression=zipfile.ZIP_DEFLATED) as zipped:
        for filename, raw in data:
            zipped.writestr(filename, raw)
    require(archive.stat().st_size <= MAX_INPUT, "Prepared ZIP exceeds the 95 MiB upload limit.")
    job = {"format": "otstudio-training-v1", "trainer_version": VERSION, "name": name.strip(),
           "destination": destination, "trigger_word": trigger, "steps": steps, "lora_rank": rank,
           "photo_count": len(images), "zip_sha256": digest(archive), "status": "prepared"}
    save_job(folder, job)
    return job


# Observed 2026-10-02: Replicate returned outputs as presigned links on this Cloudflare R2 bucket (pinned to its account).
REPLICATE_R2_OUTPUTS = "ai-gateway-outputs.0d37909e38d3e99c29fa2cd343ac421a.r2.cloudflarestorage.com"


def replicate_delivery_host(host):
    return host == "replicate.delivery" or host.endswith(".replicate.delivery")


def delivery_url(url):
    require(isinstance(url, str) and len(url) <= 8000, "Invalid provider file URL.")
    p = urlsplit(url)
    host = (p.hostname or "").lower()
    require(p.scheme == "https" and p.username is None and p.password is None and not p.fragment and p.port in (None, 443)
            and (replicate_delivery_host(host) or host == REPLICATE_R2_OUTPUTS), f"Provider file must use Replicate's HTTPS delivery service (received {host[:80] or 'no host'}).")
    return p


def uploaded_file_url(url, file_id):
    """Replicate's Files API returns urls.get as https://api.replicate.com/v1/files/<id>; accept only that exact form."""
    require(isinstance(url, str) and len(url) <= 8000 and valid_file_id(file_id), "Invalid uploaded file URL.")
    p = urlsplit(url)
    require(p.scheme == "https" and (p.hostname or "").lower() == API_HOST and p.username is None and p.password is None
            and p.port in (None, 443) and not p.query and not p.fragment and p.path == "/v1/files/" + file_id,
            "Upload response must point to the uploaded file on Replicate's API.")
    return p


def read_json_response(response, deadline):
    chunks, size = [], 0
    while True:
        require(time.monotonic() < deadline, "Provider response exceeded its time limit.")
        chunk = response.read1(16384)
        if not chunk:
            break
        size += len(chunk)
        require(size <= 1048576, "Provider response is too large.")
        chunks.append(chunk)
    result = json.loads(b"".join(chunks))
    require(isinstance(result, dict), "Provider returned an invalid response.")
    return result


class ReplicateApi:
    def __init__(self, token):
        require(0 < len(token) <= 4096 and all(33 <= ord(c) <= 126 for c in token), "Invalid Replicate API token.")
        self._token = token

    def request(self, method, path, body=None, allow_missing=False):
        """Returns the JSON reply. With allow_missing, an HTTP 404 returns None instead of failing."""
        require(path.startswith("/v1/") and "?" not in path and ".." not in path, "Invalid API path.")
        payload = json.dumps(body, allow_nan=False).encode() if body is not None else None
        headers = {"Authorization": "Bearer " + self._token, "Accept": "application/json", "User-Agent": "OTStudioTrainer/1.0.0"}
        if payload is not None:
            headers["Content-Type"] = "application/json"
        conn = http.client.HTTPSConnection(API_HOST, timeout=45, context=ssl.create_default_context())
        deadline = time.monotonic() + 90
        try:
            conn.request(method, path, body=payload, headers=headers)
            response = conn.getresponse()
            if allow_missing and response.status == 404:
                return None
            require(response.status not in (401, 403), "Replicate refused the token. Check it on replicate.com/account/api-tokens.")
            require(200 <= response.status < 300, f"Replicate returned HTTP {response.status}. Check access, inputs, and dashboard. No automatic retry was made.")
            return read_json_response(response, deadline)
        finally:
            conn.close()

    def upload(self, archive):
        boundary = "OTStudio" + uuid.uuid4().hex
        prefix = (f'--{boundary}\r\nContent-Disposition: form-data; name="content"; filename="photos.zip"\r\nContent-Type: application/zip\r\n\r\n').encode()
        suffix = f"\r\n--{boundary}--\r\n".encode()
        conn = http.client.HTTPSConnection(API_HOST, timeout=45, context=ssl.create_default_context())
        deadline = time.monotonic() + 300
        try:
            conn.putrequest("POST", "/v1/files")
            conn.putheader("Authorization", "Bearer " + self._token)
            conn.putheader("Content-Type", "multipart/form-data; boundary=" + boundary)
            conn.putheader("Content-Length", str(len(prefix) + archive.stat().st_size + len(suffix)))
            conn.endheaders()
            conn.send(prefix)
            with open(archive, "rb") as stream:
                while chunk := stream.read(65536):
                    require(time.monotonic() < deadline, "Photo upload exceeded five minutes.")
                    conn.send(chunk)
            conn.send(suffix)
            response = conn.getresponse()
            require(200 <= response.status < 300, f"Upload returned HTTP {response.status}. No training was submitted.")
            result = read_json_response(response, deadline)
            require(valid_file_id(result.get("id", "")), "Upload response has no valid file ID.")
            uploaded_file_url(result.get("urls", {}).get("get"), result["id"])
            return result["id"], result["urls"]["get"]
        finally:
            conn.close()

    def download(self, url, target):
        p = delivery_url(url)
        partial = Path(str(target) + ".part")
        conn = http.client.HTTPSConnection(p.hostname, timeout=45, context=ssl.create_default_context())
        deadline, count = time.monotonic() + 600, 0
        try:
            # The token goes only to replicate.delivery; presigned storage links carry their own signature.
            headers = {"Authorization": "Bearer " + self._token} if replicate_delivery_host(p.hostname.lower()) else {}
            conn.request("GET", p.path + ("?" + p.query if p.query else ""), headers=headers)
            response = conn.getresponse()
            require(response.status == 200, f"Weights download returned HTTP {response.status}. Run status again to retry. Redirects are not followed.")
            length = response.getheader("Content-Length")
            require(length is None or int(length) <= MAX_WEIGHTS, "Weights exceed the 2 GiB download limit.")
            with open(partial, "wb") as output:
                while chunk := response.read1(65536):
                    count += len(chunk)
                    require(count <= MAX_WEIGHTS and time.monotonic() < deadline, "Weights exceeded the download size or ten-minute limit.")
                    output.write(chunk)
                output.flush()
                os.fsync(output.fileno())
            require(count > 0 and (length is None or count == int(length)), "Weights download was incomplete.")
            os.replace(partial, target)
        finally:
            conn.close()
            partial.unlink(missing_ok=True)


def account_username(api):
    """The Replicate account the token belongs to. Free; no model or training is touched."""
    result = api.request("GET", "/v1/account")
    username = result.get("username", "")
    require(isinstance(username, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,100}", username), "Replicate did not report a usable account name.")
    return username


def destination_slug(name):
    """A Replicate model name for a LoRA: lowercase letters, digits, and dashes, starting with lora-."""
    slug = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")[:50].strip("-")
    require(slug, "Use a LoRA name with at least one letter or digit.")
    return "lora-" + slug


def ensure_destination(api, owner, slug):
    """Uses the private model owner/slug, creating it when it does not exist. Creating a model is free."""
    require(re.fullmatch(r"[A-Za-z0-9_-]{1,100}", owner) and re.fullmatch(r"[a-z0-9][a-z0-9-]{0,99}", slug), "Invalid destination name.")
    destination = owner + "/" + slug
    found = api.request("GET", "/v1/models/" + destination, allow_missing=True)
    if found is None:
        created = api.request("POST", "/v1/models", {"owner": owner, "name": slug, "visibility": "private", "hardware": "cpu",
                                                    "description": "FLUX dev LoRA trained with the OT Studio LoRA Trainer"})
        require(created.get("owner") == owner and created.get("name") == slug, "Replicate did not create the destination model.")
        found = created
    require(found.get("visibility") == "private", f"The model {destination} exists but is not private. Choose another LoRA name.")
    return destination


def submit(folder, api, accept_charge=False, upload_photos=False):
    require(accept_charge and upload_photos, "Submit needs --accept-charge and --upload-photos. It uploads your prepared photos to Replicate and starts billable training.")
    job = load_job(folder)
    require(job["status"] in {"prepared", "uploaded"} and not job.get("training_id"), "This job was already submitted or its outcome is uncertain. Use status, or inspect the Replicate dashboard. Never resubmit it.")
    archive = Path(folder) / "photos.zip"
    require(archive.is_file() and not archive.is_symlink() and archive.stat().st_size <= MAX_INPUT and digest(archive) == job["zip_sha256"], "The prepared ZIP changed or is missing. Prepare a new job after reviewing it.")
    destination = api.request("GET", "/v1/models/" + job["destination"])
    require(destination.get("owner", "") + "/" + destination.get("name", "") == job["destination"] and destination.get("visibility") == "private", "Use an existing private Replicate destination model owned by your account or organization.")
    if job["status"] == "prepared":
        file_id, file_url = api.upload(archive)
        job.update(status="uploaded", file_id=file_id, file_url=file_url)
        save_job(folder, job)
    uploaded_file_url(job["file_url"], job.get("file_id"))
    payload = {"destination": job["destination"], "input": {"input_images": job["file_url"], "trigger_word": job["trigger_word"], "steps": job["steps"], "lora_rank": job["lora_rank"], "autocaption": True}}
    job["status"] = "submitting"
    save_job(folder, job)
    try:
        result = api.request("POST", f"/v1/models/{TRAINER}/versions/{VERSION}/trainings", payload)
        training_id = result.get("id", "")
        require(isinstance(training_id, str) and REMOTE_ID.fullmatch(training_id), "Provider did not return a usable training ID. Inspect its dashboard.")
        job.update(training_id=training_id, status="processing")
        save_job(folder, job)
    except BaseException:
        if not job.get("training_id"):
            job["status"] = "uncertain"
            save_job(folder, job)
        raise
    return job


def apply_result(folder, job, result, api):
    require(result.get("id") == job.get("training_id"), "Provider returned a different training ID.")
    state = result.get("status")
    require(state in {"starting", "processing"} | TERMINAL, "Provider returned an unsupported training state.")
    job["status"] = state
    save_job(folder, job)
    if state != "succeeded":
        return job
    output = result.get("output") or {}
    version = output.get("version", "")
    reference = version.replace(":", "/") if isinstance(version, str) else ""
    require(re.fullmatch(re.escape(job["destination"]) + r"/[a-f0-9]{64}", reference), "Training output version does not match your destination.")
    url = output.get("weights")
    parsed = delivery_url(url)
    filename = parsed.path.rsplit("/", 1)[-1].lower()
    suffix = next((s for s in (".safetensors", ".tar.gz", ".tar", ".zip") if filename.endswith(s)), ".bin")
    weights = Path(folder) / ("lora-weights" + suffix)
    job.update(reference=reference, weights_file=weights.name)
    save_job(folder, job)
    library = {"format": "otstudio-lora-v1", "name": job["name"], "reference": reference, "trigger_word": job["trigger_word"], "scale": 1.0, "base_model": "flux-dev"}
    atomic_json(Path(folder) / "trained.otstudio-lora.json", library)
    if not weights.exists() or digest(weights) != job.get("weights_sha256"):
        api.download(url, weights)
        job["weights_sha256"] = digest(weights)
        save_job(folder, job)
    return job


def status(folder, api, training_id=None):
    job = load_job(folder)
    selected_id = training_id or job.get("training_id", "")
    require(isinstance(selected_id, str) and REMOTE_ID.fullmatch(selected_id), "No training ID is saved. Check Replicate's dashboard, then run status --training-id ID for this exact job.")
    require(not training_id or not job.get("training_id") or job["training_id"] == training_id, "The supplied ID differs from this job's saved ID.")
    result = api.request("GET", "/v1/trainings/" + selected_id)
    if not job.get("training_id"):
        require(job["status"] in {"submitting", "uncertain"}, "Only an uncertain submission can attach a recovered ID.")
        inputs = result.get("input") or {}
        require(result.get("id") == selected_id and result.get("model") == TRAINER and result.get("version") == VERSION and inputs.get("input_images") == job.get("file_url") and inputs.get("trigger_word") == job["trigger_word"], "This training does not match the prepared job. Nothing was attached.")
        job["training_id"] = selected_id
        save_job(folder, job)
    return apply_result(folder, job, result, api)


def cancel(folder, api):
    job = load_job(folder)
    require(job["status"] not in TERMINAL, "This job is already terminal.")
    training_id = job.get("training_id", "")
    require(REMOTE_ID.fullmatch(training_id), "No saved training ID. Check the provider dashboard.")
    result = api.request("POST", "/v1/trainings/" + training_id + "/cancel", {})
    return apply_result(folder, job, result, api)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    prep = sub.add_parser("prepare", help="Validate and ZIP 1-20 photos locally. No upload or charge.")
    prep.add_argument("--photos", required=True)
    prep.add_argument("--job", required=True)
    prep.add_argument("--name", required=True)
    prep.add_argument("--destination", required=True)
    prep.add_argument("--trigger", default="TOK")
    prep.add_argument("--steps", type=int, default=1000)
    prep.add_argument("--rank", type=int, default=16)
    for command in ("submit", "status", "cancel"):
        child = sub.add_parser(command)
        child.add_argument("--job", required=True)
        if command == "submit":
            child.add_argument("--accept-charge", action="store_true")
            child.add_argument("--upload-photos", action="store_true")
        if command == "status":
            child.add_argument("--training-id", help="Recover only the exact job ID after an ambiguous submission.")
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            job = prepare(args.photos, args.job, args.name, args.destination, args.trigger, args.steps, args.rank)
            print(f"Prepared {job['photo_count']} photos locally. Review photos.zip before submitting. No upload or training was performed.")
        else:
            if args.command == "submit":
                require(args.accept_charge and args.upload_photos, "Submit requires both --accept-charge and --upload-photos.")
            token = os.environ.get("REPLICATE_API_TOKEN") or getpass.getpass("Replicate API token (hidden, not saved): ")
            api = ReplicateApi(token.strip())
            with job_lock(args.job):
                if args.command == "submit":
                    job = submit(args.job, api, args.accept_charge, args.upload_photos)
                elif args.command == "status":
                    job = status(args.job, api, args.training_id)
                else:
                    job = cancel(args.job, api)
            print("Training status:", job["status"])
            if job.get("training_id"):
                print("Training ID:", job["training_id"])
            if job.get("weights_sha256"):
                print("Weights backup and trained.otstudio-lora.json are saved in the job folder.")
            elif job["status"] in {"starting", "processing"}:
                print("Run status later to check and save the output. Closing this script does not cancel provider training.")
    except KeyboardInterrupt:
        print("Stopped locally. Check the saved job and provider dashboard. Training is not canceled automatically.", file=sys.stderr)
        return 130
    except TrainingError as error:
        print(str(error), file=sys.stderr)
        return 1
    except Exception:
        print("Network, file, or provider response failed. No automatic retry was made. Preserve the job folder and inspect the provider dashboard before another submission.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
