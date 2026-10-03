# Privacy and local data

OT Studio has no server of its own. It shows no ads and collects no analytics. Every request goes straight from your phone to the provider you configured, and that provider's terms, billing, logging, and retention rules apply.

## What leaves the phone

A chat message goes to the chat provider together with a limited amount of recent chat history. An image or video prompt goes to the media provider with the inputs you chose in API CALL. A photo you pick with Choose photo, or an image you send with Use as... or Upscale, uploads to your own Replicate account through Replicate's Files API. Suggest setup sends your prompt and the matching models' input lists to your chat provider, and only when you tap it.

## Keys

The app encrypts each key with AES-GCM under a key held in the Android Keystore. Keys never appear in the source code, the APK, the logs, or Android backups. Release builds block screenshots of Settings. Each key is bound to the provider address it was saved with, and the app refuses to send it anywhere else.

Downloads start without a key. Only when Replicate's own delivery host answers 401 or 403 does the app retry with the Replicate key. It never sends a key to a storage host such as Cloudflare R2. A lost or unlocked phone sits outside this protection, as it would for any app.

## What stays on the phone

The app keeps prompts, answers, job IDs, chosen inputs, model profiles, input definitions, LoRA references, and media details in private databases. Input addresses, including signed links, can appear in that history. Generated files download into private app storage.

Save copies a file into the folder you chose, and that copy belongs to you. Share hands a file to the app you pick in Android's share sheet, straight from private storage.

Removing the app, or clearing its storage, deletes the history, the settings, the keys, and every unsaved file. Saved copies stay where you put them. Android cloud backup and device transfer are switched off for this app. Deleting local data does not cancel a job at a provider or refund any charge.

## Limits

Only one image or video job runs at a time. Each media file may be up to 128 MB, and private storage holds up to 512 MB in total. Prompts are limited to 4000 characters, and provider replies to a bounded size. Error messages shown in the app are cleaned of provider payloads, and no key or raw provider error goes to a log.

## The training script

The computer-side `train_lora.py` script builds a photo ZIP on your computer. Only its `submit` command uploads that ZIP to Replicate and starts a paid training run. It reads your token from hidden input or an environment variable and never saves it. Job folders hold your photos, training IDs, result references, and downloaded weights, so keep them private. Importing the finished LoRA file into OT Studio stores only the model reference. Your photos and weights stay on your computer.
