<img src="images/otstudio-avatar.jpg" width="72" align="right" alt="">

# Getting Started

This page takes you from an empty phone to your first image. Plan on about fifteen minutes, most of it spent creating the two provider accounts.

## Install the app

1. On your phone, open the [Releases](https://github.com/Orangeriger1998/OTStudio/releases) page and download the newest `OT-Studio-x.y.z.apk` file.
2. Open the downloaded file. Android asks whether your browser or file app may install apps. Allow it for this one install.
3. Tap Install, then Open.

OT Studio needs Android 10 or later. The Play Store does not list it, so updates also come from the Releases page. A newer APK installs over the old one and keeps your history and keys.

## Get your keys

OT Studio uses two providers, and each one needs its own key.

**OpenRouter** runs the chat models.

1. Create an account at [openrouter.ai](https://openrouter.ai/sign-up).
2. Buy credit on the [Credits](https://openrouter.ai/settings/credits) page. The smallest purchase is $5. Card payments carry a fee of 5.5 percent, with an 80 cent minimum.
3. Open [Keys](https://openrouter.ai/settings/keys) and create a key. Give it a name and, if you like, a spending limit.
4. Copy the key right away.

**Replicate** runs the image and video models.

1. Sign in at [replicate.com](https://replicate.com/signin). Replicate signs you in with a GitHub account, so create a free GitHub account first if you lack one.
2. Open [Billing](https://replicate.com/account/billing), add a payment method, and buy credit. Replicate bills in advance. Its credit stays valid for one year.
3. Open [API tokens](https://replicate.com/account/api-tokens). Your account already has a default token. It starts with `r8_`.

## Put the keys in OT Studio

1. Tap the gear at the top right to open Settings.
2. With Chat selected, paste the OpenRouter key into the API key field. Tap Save chat settings.
3. Tap Image, paste the Replicate key, and tap Save image settings.

Video reuses the image key by default, so it needs no key of its own. The key field empties after each save, and a line under it confirms that the key is saved. Settings also blocks screenshots, so a key never ends up in a screen capture.

## Your first image

Tap Image on the main screen. Type a short description, such as `a tuxedo cat knitting a pastel scarf, soft watercolor`, and tap the orange send button. FLUX schnell answers in a few seconds. Tap Save on the finished card. The first save asks you to choose a folder, and OT Studio remembers that folder for every later save.

## What it costs

Each provider charges per request. These figures come from the providers' own price pages in October 2026. Providers change their prices from time to time.

| Item | Typical price | Source |
|---|---|---|
| Chat with Mistral Nemo | $0.02 per million tokens in, $0.03 per million out (well under a hundredth of a cent per message) | [OpenRouter](https://openrouter.ai/mistralai/mistral-nemo) |
| Chat on the phone | Free after the model download | Hugging Face |
| FLUX schnell image | $0.003 each | [Replicate](https://replicate.com/black-forest-labs/flux-schnell) |
| FLUX dev image | $0.025 each | [Replicate](https://replicate.com/black-forest-labs/flux-dev) |
| FLUX dev LoRA image | $0.032 each | [Replicate](https://replicate.com/black-forest-labs/flux-dev-lora) |
| Upscale 2x (Real-ESRGAN) | $0.002 each | [Replicate](https://replicate.com/nightmareai/real-esrgan) |
| LoRA training run | about $1.50 to $2 | [Replicate](https://replicate.com/ostris/flux-dev-lora-trainer) |
| WAN 2.7 video from an image | $0.10 per second at 720p, $0.15 at 1080p | [Replicate](https://replicate.com/wan-video/wan-2.7-i2v) |
| WAN 2.2 fast video | $0.05 per clip at 480p, $0.11 at 720p | [Replicate](https://replicate.com/wan-video/wan-2.2-i2v-fast) |

The chat prices count tokens, which are pieces of words, so the "words" in the table are approximate.

**Ten matching photos at about 2K.** Matching photos need a LoRA, a small add-on model trained on your own pictures. Training one costs about $1.50 to $2. Each LoRA photo then costs about three cents at 1024 by 1024. Upscaling it to 2048 by 2048 adds a fifth of a cent. The first set of ten therefore comes to about $2.20. Every later set skips the training and costs about 35 cents.

**One minute of video.** Video models make short clips of 2 to 15 seconds, so a minute takes about twelve five-second clips. WAN 2.7 at 720p costs about $6 for that minute, and 1080p raises it to about $9. The faster WAN 2.2 model costs about 60 cents at 480p, with visibly lower quality. A LoRA starting frame for each clip adds about 40 cents.

## What OT Studio does not do

OT Studio makes one photo or one clip at a time. It does not join clips into a longer video. Music and titles also need another app. CapCut handles both on a phone. On a computer, any desktop video editor does the same job.

LoRA training runs on a computer with the `train_lora.py` script from this repository. A phone cannot train a LoRA.

Replicate deletes results made through its API after about an hour. OT Studio downloads each result the moment it finishes, so this rarely matters. Still, save anything you want to keep.

The app keeps no cloud backup. Removing it deletes your history, your saved keys, your LoRA library, and any media you have not saved.
