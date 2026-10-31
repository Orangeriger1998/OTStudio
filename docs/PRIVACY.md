# Privacy and local data

OT Studio has no server of its own. It shows no ads and collects no analytics. Every request goes straight from your phone to the provider you configured, and that provider's terms, billing, logging, and retention rules apply.

## What leaves the phone

A chat message goes to the chat provider together with a limited amount of recent chat history. An image or video prompt goes to the media provider with the inputs you chose in API CALL. A photo you pick with Choose photo, or an image you send with Use as... or Upscale, uploads to your own Replicate account through Replicate's Files API. A picture SpicyAPI made is not uploaded anywhere; the app sends its SpicyAPI address instead. Suggest setup sends your prompt and the matching models' input lists to your chat provider, and only when you tap it.

## Chat on the phone, memory, and history

With Chat set to On this phone, messages and replies never leave the phone. The model file comes from Hugging Face, and OT Studio sends no key and no message there. The app keeps the model's processed conversation in its private cache, so a restart does not have to read it again.

With Allow web search on (the default), the chat may send short search words to Wikipedia, or to Tavily when a Tavily key is saved. Only the search words go there, never the conversation. The Tavily key is encrypted like the other keys and goes only to api.tavily.com. Each reply that searched lists the searches under it. Switch Allow web search off to keep every message on the phone.

Memory instructions are stored as plain text in the app's private database. They go first in every chat. With on-device chat they stay on the phone. When Chat uses OpenRouter, the instructions that are on travel to OpenRouter with each message.

Save chat history is on by default. When it is off, new chat messages live only in the app's memory and vanish when the app closes, and the processed conversation is not kept either. Images and videos are still saved. Clear chat history deletes every chat message on the phone.

## OpenRouter pictures and videos

When an Image or Video model from OpenRouter is chosen, the prompt, the model's listed settings, and any picture chosen for it go to openrouter.ai with the OpenRouter key saved for Chat. A picture is redrawn as a new JPEG before it leaves, so its location and camera details stay behind. Nothing is uploaded to Replicate for these models. Finished pictures arrive inside OpenRouter's answer, and finished videos download from openrouter.ai; both are stored like any other result.

## LoRA training

Training starts only from the Train a LoRA form, after the agreement box and a second confirmation. The phone redraws each chosen photo as an upright JPEG, which leaves out its location and camera details, and packs the photos into one ZIP in memory. The ZIP goes to Replicate's Files API with the Replicate key saved for Image, and the training runs in a private model in that Replicate account. Nothing else receives the photos, and the phone keeps no copy of the ZIP. The phone keeps each training's name, trigger word, settings, state, and result; the training log is read for numbers and known stage words, and it is never stored or shown.

## SpicyAPI

When an Image or Video model from SpicyAPI is chosen, the prompt, that model's listed settings, and the addresses of any SpicyAPI pictures chosen for it go to api.spicyapi.com with the SpicyAPI key. SpicyAPI accepts only pictures it made, so no photo from the phone, no Replicate upload, and no LoRA goes there. The one exception is Spicy Image Reference 1, which takes a photo from the phone: the photo is redrawn as a JPEG of at most 2048 pixels, without its location and camera details, and SpicyAPI keeps only its look and draws new people. Finished pictures and videos come back from cdn.spicyapi.com and are stored like any other result. The SpicyAPI key has its own encrypted slot, bound to SpicyAPI's address.

## Keys

The app encrypts each key with AES-GCM under a key held in the Android Keystore. Keys never appear in the source code, the APK, the logs, or Android backups. Release builds block screenshots of Settings. Each key is bound to the provider address it was saved with, and the app refuses to send it anywhere else.

Downloads start without a key. Only when Replicate's own delivery host answers 401 or 403 does the app retry with the Replicate key, and only when openrouter.ai answers that way for an OpenRouter video does it retry with the OpenRouter key. It never sends a key to a storage host such as Cloudflare R2. A lost or unlocked phone sits outside this protection, as it would for any app.

## What stays on the phone

The app keeps prompts, answers, job IDs, chosen inputs, model profiles, input definitions, LoRA references, and media details in private databases. Input addresses, including signed links, can appear in that history. Generated files download into private app storage.

Save copies a file into the folder you chose, and that copy belongs to you. Share hands a file to the app you pick in Android's share sheet, straight from private storage.

Removing the app, or clearing its storage, deletes the history, the settings, the keys, and every unsaved file. Saved copies stay where you put them. Android cloud backup and device transfer are switched off for this app. Deleting local data does not cancel a job at a provider or refund any charge.

## Limits

Only one image or video job runs at a time. Each media file may be up to 128 MB, and private storage holds up to 512 MB in total. Prompts are limited to 4000 characters, and provider replies to a bounded size. Error messages shown in the app are cleaned of provider payloads, and no key or raw provider error goes to a log.

## The training script

The computer-side `train_lora.py` script builds a photo ZIP on your computer. Only its `submit` command uploads that ZIP to Replicate and starts a paid training run. It reads your token from hidden input or an environment variable and never saves it. Job folders hold your photos, training IDs, result references, and downloaded weights, so keep them private. Importing the finished LoRA file into OT Studio stores only the model reference. Your photos and weights stay on your computer.
