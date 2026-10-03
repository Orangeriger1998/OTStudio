"""Deterministic tests. No provider credentials or network calls."""
import json
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import train_lora as train


class FakeApi:
    def __init__(self):
        self.calls = []
        self.uploads = 0
        self.downloads = 0
        self.visibility = "private"
        self.fail_post = False
        self.fail_download = False
        self.response = {"id": "training1", "status": "processing"}

    def request(self, method, path, body=None):
        self.calls.append((method, path, body))
        if method == "GET" and path == "/v1/models/owner/style":
            return {"owner": "owner", "name": "style", "visibility": self.visibility}
        if method == "POST" and path.endswith("/trainings") and self.fail_post:
            raise TimeoutError("synthetic network failure")
        return self.response

    def upload(self, archive):
        self.uploads += 1
        return "file1", "https://api.replicate.com/v1/files/file1"

    def download(self, url, target):
        train.delivery_url(url)
        self.downloads += 1
        if self.fail_download:
            raise TimeoutError("synthetic download interruption")
        Path(target).write_bytes(b"synthetic weights; not a real LoRA")


class TrainerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.photos = self.root / "photos"
        self.photos.mkdir()
        self.job = self.root / "job"
        self.api = FakeApi()
        self.addCleanup(self.temp.cleanup)

    def images(self, count=10):
        for i in range(count):
            (self.photos / f"source-{i}.png").write_bytes(b"\x89PNG\r\n\x1a\n" + b"synthetic-signature-fixture")

    def prepared(self):
        self.images()
        return train.prepare(self.photos, self.job, "Style", "owner/style", "TOK")

    def submitted(self):
        self.prepared()
        return train.submit(self.job, self.api, True, True)

    def succeeded(self):
        self.api.response = {"id": "training1", "status": "succeeded", "output": {
            "version": "owner/style:" + "a" * 64,
            "weights": "https://replicate.delivery/synthetic/weights.safetensors"}}

    def test_prepare_is_local_and_zips_captions_with_matching_names(self):
        self.images(20)
        (self.photos / "source-0.txt").write_text("TOK in a garden", encoding="utf-8")
        with patch.object(train.ReplicateApi, "request", side_effect=AssertionError("Network forbidden")):
            result = train.prepare(self.photos, self.job, "Style", "owner/style")
        self.assertEqual(20, result["photo_count"])
        self.assertEqual("prepared", result["status"])
        with zipfile.ZipFile(self.job / "photos.zip") as archive:
            self.assertEqual(21, len(archive.namelist()))
            self.assertIn("photo-01.txt", archive.namelist())
            self.assertFalse(any("source-" in name for name in archive.namelist()))

    def test_empty_or_over_twenty_photos_rejected_before_job_creation(self):
        for count in (0, 21):
            with self.subTest(count=count):
                self.images(count)
                with self.assertRaises(train.TrainingError):
                    train.prepare(self.photos, self.job, "Style", "owner/style")
                self.assertFalse(self.job.exists())

    def test_corrupt_signatures_and_oversized_photos_rejected(self):
        image = self.photos / "bad.jpg"
        image.write_bytes(b"this is not an image")
        with self.assertRaises(train.TrainingError):
            train.prepare(self.photos, self.job, "Style", "owner/style")
        with image.open("wb") as stream:
            stream.truncate(20 * 1024 * 1024 + 1)
        with self.assertRaises(train.TrainingError):
            train.prepare(self.photos, self.job, "Style", "owner/style")

    def test_existing_job_is_preserved(self):
        self.prepared()
        before = (self.job / "job.json").read_bytes()
        with self.assertRaises(train.TrainingError):
            train.prepare(self.photos, self.job, "Overwrite", "owner/style")
        self.assertEqual(before, (self.job / "job.json").read_bytes())

    def test_explicit_upload_and_charge_flags_required(self):
        self.prepared()
        for flags in ((False, False), (True, False), (False, True)):
            with self.assertRaises(train.TrainingError):
                train.submit(self.job, self.api, *flags)
        self.assertEqual([], self.api.calls)
        self.assertEqual(0, self.api.uploads)

    def test_modified_zip_and_public_destination_block_upload(self):
        self.prepared()
        self.api.visibility = "public"
        with self.assertRaises(train.TrainingError):
            train.submit(self.job, self.api, True, True)
        self.assertEqual(0, self.api.uploads)
        self.api.visibility = "private"
        with (self.job / "photos.zip").open("ab") as file:
            file.write(b"changed")
        with self.assertRaises(train.TrainingError):
            train.submit(self.job, self.api, True, True)
        self.assertEqual(0, self.api.uploads)

    def test_submit_pins_trainer_and_saves_id_without_credentials(self):
        result = self.submitted()
        self.assertEqual("training1", result["training_id"])
        post = [call for call in self.api.calls if call[0] == "POST"]
        self.assertEqual(1, len(post))
        self.assertEqual(f"/v1/models/{train.TRAINER}/versions/{train.VERSION}/trainings", post[0][1])
        self.assertEqual({"input_images", "trigger_word", "steps", "lora_rank", "autocaption"}, set(post[0][2]["input"]))
        self.assertNotIn("token", (self.job / "job.json").read_text())
        with self.assertRaises(train.TrainingError):
            train.submit(self.job, self.api, True, True)
        self.assertEqual(1, self.api.uploads)

    def test_ambiguous_submit_cannot_be_reposted(self):
        self.prepared()
        self.api.fail_post = True
        with self.assertRaises(TimeoutError):
            train.submit(self.job, self.api, True, True)
        self.assertEqual("uncertain", train.load_job(self.job)["status"])
        with self.assertRaises(train.TrainingError):
            train.submit(self.job, self.api, True, True)
        self.assertEqual(1, len([x for x in self.api.calls if x[0] == "POST"]))

    def test_status_saves_weights_and_app_import_without_resubmission(self):
        self.submitted()
        self.succeeded()
        result = train.status(self.job, self.api)
        self.assertEqual("succeeded", result["status"])
        self.assertTrue((self.job / "lora-weights.safetensors").is_file())
        library = json.loads((self.job / "trained.otstudio-lora.json").read_text())
        self.assertEqual("owner/style/" + "a" * 64, library["reference"])
        self.assertEqual("TOK", library["trigger_word"])
        self.assertEqual("flux-dev", library["base_model"])
        train.status(self.job, self.api)
        self.assertEqual(1, self.api.downloads)
        self.assertEqual(1, len([x for x in self.api.calls if x[0] == "POST"]))

    def test_download_failure_can_resume_without_training_again(self):
        self.submitted()
        self.succeeded()
        self.api.fail_download = True
        with self.assertRaises(TimeoutError):
            train.status(self.job, self.api)
        self.assertEqual("succeeded", train.load_job(self.job)["status"])
        self.api.fail_download = False
        train.status(self.job, self.api)
        self.assertTrue((self.job / "trained.otstudio-lora.json").exists())
        self.assertEqual(1, len([x for x in self.api.calls if x[0] == "POST"]))

    def test_wrong_job_or_destination_does_not_download(self):
        self.submitted()
        self.succeeded()
        self.api.response["id"] = "wrong"
        with self.assertRaises(train.TrainingError):
            train.status(self.job, self.api)
        self.api.response["id"] = "training1"
        self.api.response["output"]["version"] = "other/style:" + "a" * 64
        with self.assertRaises(train.TrainingError):
            train.status(self.job, self.api)
        self.assertEqual(0, self.api.downloads)

    def test_only_exact_uncertain_job_can_recover_id(self):
        self.prepared()
        self.api.fail_post = True
        with self.assertRaises(TimeoutError):
            train.submit(self.job, self.api, True, True)
        with self.assertRaises(train.TrainingError):
            train.status(self.job, self.api, "training1")
        self.api.response.update(model=train.TRAINER, version=train.VERSION, input={"input_images": "https://api.replicate.com/v1/files/file1", "trigger_word": "TOK"})
        train.status(self.job, self.api, "training1")
        self.assertEqual("training1", train.load_job(self.job)["training_id"])

    def test_cancel_targets_only_saved_id_and_preserves_failure(self):
        self.submitted()
        self.api.response["status"] = "canceled"
        train.cancel(self.job, self.api)
        self.assertEqual("/v1/trainings/training1/cancel", self.api.calls[-1][1])
        self.assertEqual("canceled", train.load_job(self.job)["status"])
        with self.assertRaises(train.TrainingError):
            train.cancel(self.job, self.api)

    def test_delivery_auth_cannot_leave_provider(self):
        for url in ("http://replicate.delivery/x", "https://replicate.delivery.evil.test/x", "https://key@replicate.delivery/x", "https://replicate.delivery:444/x", "file:///tmp/x"):
            with self.subTest(url=url), self.assertRaises(train.TrainingError):
                train.delivery_url(url)
        self.assertEqual("pbxt.replicate.delivery", train.delivery_url("https://pbxt.replicate.delivery/x").hostname)

    def test_replicate_storage_weights_are_accepted_but_never_receive_the_token(self):
        r2 = "https://" + train.REPLICATE_R2_OUTPUTS + "/weights.tar?X-Amz-Signature=abc"
        self.assertEqual(train.REPLICATE_R2_OUTPUTS, train.delivery_url(r2).hostname)
        for url in ("https://ai-gateway-outputs.ffffffffffffffffffffffffffffffff.r2.cloudflarestorage.com/x", "https://evil.r2.cloudflarestorage.com/x"):
            with self.assertRaises(train.TrainingError):
                train.delivery_url(url)
        sent = {}
        class Response:
            status = 200
            def getheader(self, name):
                return "4" if name == "Content-Length" else None
            def read1(self, size=-1):
                data, self.body = getattr(self, "body", b"data"), b""
                return data
        class Connection:
            def request(self, method, path, headers=None):
                sent["headers"] = dict(headers or {})
            def getresponse(self):
                return Response()
            def close(self):
                pass
        self.job.mkdir(parents=True, exist_ok=True)
        target = self.job / "weights.tar"
        with patch.object(train.http.client, "HTTPSConnection", return_value=Connection()):
            train.ReplicateApi("synthetic-test-only").download(r2, target)
        self.assertNotIn("Authorization", sent["headers"])

    def test_dotted_replicate_file_ids_are_accepted_but_never_as_a_path_step(self):
        dotted = "ZmlsZS0xMjM0NTY3ODkw.YWJjZGVmZ2hpams.bG1ub3BxcnN0dXZ3"
        self.assertTrue(train.valid_file_id(dotted))
        self.assertEqual("/v1/files/" + dotted, train.uploaded_file_url("https://api.replicate.com/v1/files/" + dotted, dotted).path)
        for bad in ("..", "a..b", ".abc", "abc.", "a/b", "", None):
            self.assertFalse(train.valid_file_id(bad))

    def test_uploaded_file_url_must_be_the_exact_replicate_files_resource(self):
        self.assertEqual("/v1/files/file1", train.uploaded_file_url("https://api.replicate.com/v1/files/file1", "file1").path)
        for url in ("https://api.replicate.com/v1/files/other", "http://api.replicate.com/v1/files/file1", "https://api.replicate.com.evil.test/v1/files/file1",
                    "https://key@api.replicate.com/v1/files/file1", "https://api.replicate.com:444/v1/files/file1", "https://api.replicate.com/v1/files/file1?x=1",
                    "https://replicate.delivery/input/photos.zip", None):
            with self.assertRaises(train.TrainingError):
                train.uploaded_file_url(url, "file1")

    def test_job_lock_prevents_concurrent_operations(self):
        self.prepared()
        with train.job_lock(self.job):
            with self.assertRaises(train.TrainingError):
                with train.job_lock(self.job):
                    self.fail("Concurrent lock granted")
        with train.job_lock(self.job):
            pass

    def test_bounded_response_rejects_oversized_invalid_or_timed_out_data(self):
        for raw in (b"x" * 1048577, b"[]", b"null"):
            with self.subTest(size=len(raw)), self.assertRaises(train.TrainingError):
                train.read_json_response(io.BytesIO(raw), float("inf"))
        with self.assertRaises(train.TrainingError):
            train.read_json_response(io.BytesIO(b"{}"), 0)

    def test_http_adapter_uses_verified_multipart_and_never_follows_redirect(self):
        self.prepared()
        class Response(io.BytesIO):
            status = 201
        class Connection:
            def __init__(self):
                self.headers = {}
                self.body = bytearray()
                self.closed = False
            def putrequest(self, method, path):
                self.request_line = (method, path)
            def putheader(self, name, value):
                self.headers[name] = value
            def endheaders(self):
                pass
            def send(self, body):
                self.body.extend(body)
            def getresponse(self):
                return Response(b'{"id":"file1","urls":{"get":"https://api.replicate.com/v1/files/file1"}}')
            def close(self):
                self.closed = True
        connection = Connection()
        with patch.object(train.http.client, "HTTPSConnection", return_value=connection) as factory:
            file_id, url = train.ReplicateApi("synthetic-test-only").upload(self.job / "photos.zip")
        self.assertEqual("file1", file_id)
        self.assertEqual("https://api.replicate.com/v1/files/file1", url)
        self.assertEqual("api.replicate.com", factory.call_args.args[0])
        self.assertEqual(("POST", "/v1/files"), connection.request_line)
        self.assertEqual(len(connection.body), int(connection.headers["Content-Length"]))
        self.assertIn(b'name="content"; filename="photos.zip"', connection.body)
        self.assertIn((self.job / "photos.zip").read_bytes(), connection.body)
        self.assertTrue(connection.closed)
        Response.status = 302
        with patch.object(train.http.client, "HTTPSConnection", return_value=connection) as factory:
            with self.assertRaises(train.TrainingError):
                train.ReplicateApi("synthetic-test-only").upload(self.job / "photos.zip")
            self.assertEqual(1, factory.call_count)

    def test_modified_training_settings_are_validated_before_network(self):
        job = self.prepared()
        for key, value in (("steps", 6001), ("lora_rank", "16"), ("photo_count", 21), ("trigger_word", "bad\ntrigger")):
            with self.subTest(key=key):
                changed = dict(job)
                changed[key] = value
                train.save_job(self.job, changed)
                with self.assertRaises(train.TrainingError):
                    train.submit(self.job, self.api, True, True)
        self.assertEqual([], self.api.calls)


if __name__ == "__main__":
    unittest.main()
