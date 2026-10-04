# Train and use a LoRA

## The desktop trainer

`OT-Studio-LoRA-Trainer.exe` (on the Releases page; built by `scripts/build-trainer.ps1`) is a standalone Windows program for the steps below. It needs no Python. The user pastes the Replicate token, which stays in memory only; chooses 10 to 20 photos; names the LoRA; and agrees to the upload and the charge. The trainer then finds the account name (`GET /v1/account`), creates or reuses a private destination model named `lora-<name>` (`POST /v1/models` with hardware `cpu`, which is free), prepares the ZIP, uploads it, starts training, checks every 30 seconds, and saves the weights and `trained.otstudio-lora.json` in `Documents\OT Studio LoRA\<job>`. A public model with the same name is refused. Resume a job picks up a job folder after the window was closed. Cancel training cancels only the saved training ID. Windows SmartScreen may warn about the unsigned program; More info, then Run anyway, starts it.

Photos may be JPG, PNG, WebP, HEIC, or most other formats, at least 512 pixels on the short side and at most 50 MB each. The trainer screens them as they are chosen, names any it leaves out, and converts the rest to upright JPEG copies of at most 2048 pixels before it builds the ZIP. The originals stay untouched.

The trainer uses `train_lora.py` underneath, so every rule below applies to it as well. The command line remains for other systems and for scripted use.

The correct trainer is **ostris/flux-dev-lora-trainer**. This workflow trains a FLUX dev LoRA, saves its weights on your computer, and creates a library file for OT Studio's **black-forest-labs/flux-dev-lora** dropdown. Training runs on Replicate.

The script uses Python 3.11 or later and the standard library only. It was tested with Python 3.12. No ADB, emulator, Windows installer, or extra Python package is required by the script.

## Prepare locally

Extract the trainer ZIP and put 1-20 JPG, PNG, or WebP photos in one folder. The provider recommends at least 10. Each photo must be at most 20 MiB; the combined ZIP must fit within 95 MiB. Subfolders are not included. Optional UTF-8 captions go beside matching photos, such as `photo.jpg` and `photo.txt`. Autocaptioning is enabled.

Create an empty private destination model in your Replicate account, or use an existing private model intended for this training. Note its `owner/model` ID. The script checks it before uploading; it never changes a model's visibility. (The desktop trainer creates the private model itself.)

Open PowerShell in the extracted script folder and replace the example folder and destination:

```powershell
python .\train_lora.py prepare --photos .\my-photos --job .\training-jobs\my-style --name "My style" --destination YOUR_USERNAME/YOUR_MODEL --trigger MYSTYLE
```

This creates `photos.zip` and `job.json` locally without contacting Replicate. Open the ZIP and review the photos. Files are renamed inside the ZIP, but image bytes and embedded metadata are preserved. Validation checks file signatures; it does not fully decode images or assess their quality.

Defaults: 1000 steps and rank 16. Optional `--steps` accepts 3-6000; `--rank` accepts 1-128. Provider guidance recommends roughly 500-4000 steps. Use a new job folder for each training so prior results are preserved.

## Submit and check training

```powershell
python .\train_lora.py submit --job .\training-jobs\my-style --upload-photos --accept-charge
```

This command uploads your photo ZIP to Replicate and starts billable training. The two flags explicitly enable those actions. Provider pricing applies; step count is not a dollar spending cap. Check the trainer page and account billing before running it.

The script asks for your Replicate token with input hidden and does not save it. It can also read an existing `REPLICATE_API_TOKEN` environment variable. Do not put keys in source, job/library files, or chat.

Keep the whole job folder, which contains the training ID. Check later, repeating as needed:

```powershell
python .\train_lora.py status --job .\training-jobs\my-style
```

After success, the folder contains:

- `trained.otstudio-lora.json`: the small file to transfer to your phone, with model version, name, trigger word, scale, and FLUX dev compatibility.
- `lora-weights.safetensors`, or an archive with the provider's returned extension: your local weights backup. Archives are not automatically extracted.
- Updated `job.json`: training ID, result reference, and weights SHA-256. It contains no API token.

The library file is saved before the weights download. If a download fails, run status again to retry without training again. Downloads are limited to 2 GiB. Socket waits are bounded and streaming checks elapsed time between chunks. Save promptly because provider links can expire.

Closing the script or pressing Ctrl+C stops local waiting, not remote training. To request cancellation of a known job:

```powershell
python .\train_lora.py cancel --job .\training-jobs\my-style
```

Wait for a provider-confirmed canceled status. Charges already incurred can remain.

If submission loses its response, the script marks it uncertain and refuses to resubmit. Find the exact training in the Replicate dashboard, then use:

```powershell
python .\train_lora.py status --job .\training-jobs\my-style --training-id THE_EXACT_TRAINING_ID
```

The helper checks the trainer/version, uploaded input URL, and trigger word before attaching that ID. Do not edit job.json to turn an uncertain job into a prepared job.

## Use the result on Android

1. Transfer `trained.otstudio-lora.json` to your phone.
2. Tap **LoRA library > Import file** in OT Studio, choose that JSON file, review it, and save.
3. Choose **Image**, open **API CALL**, and select `black-forest-labs/flux-dev-lora`.
4. Choose the entry under **Saved FLUX dev LoRAs**. This fills lora_weights and lora_scale. Tap Apply.
5. Include the trigger word in the prompt, for example `MYSTYLE, a quiet garden in morning light`, and generate.

The app stores the provider reference. It does not upload the local weights backup from your phone. A private destination must remain accessible to your Replicate account. **Add manually** accepts an existing owner/model, pinned owner/model/version, or HTTPS weights URL. Saving the same reference updates its details.

FLUX dev LoRAs are not interchangeable with WAN video LoRAs. Optional Hugging Face/CivitAI passwords are excluded from the generic modifier editor.

## Evidence and sources

Local preparation, multipart upload shape, duplicate-submit prevention, recovery, cancellation routing, download recovery, export, locking, and bounds have deterministic tests. No actual photos, provider keys, paid training, or live weights were used. Android import and live inference need phone/account testing.

- [Trainer inputs and pricing](https://replicate.com/ostris/flux-dev-lora-trainer/train)
- [FLUX dev LoRA schema](https://replicate.com/black-forest-labs/flux-dev-lora/api/schema)
- [Training API](https://replicate.com/docs/reference/http#trainings.create)
- [Input files](https://replicate.com/docs/topics/predictions/input-files)
- [Official upload implementation](https://github.com/replicate/replicate-python/blob/main/replicate/file.py)

Trainer version pinned in this delivery: `26dce37af90b9d997eeb970d92e47de3064d46c300504ae376c75bef6a9022d2`. Contracts examined 2026-10-02; provider availability and terms may change.
