<img src="images/otstudio-avatar.jpg" width="72" align="right" alt="">

# Roadmap

This page lists the changes under consideration after the 1.0.0 beta. Nothing here is promised. Each item names the reason it waited and what it would take. The [Revision Log](Revision-Log.md) records what actually shipped.

## Under consideration for 1.1.0

Chat-led creation, editing by instruction, chat on the phone, memory, and web search shipped in 1.0.0. The [Revision Log](Revision-Log.md) describes them.

### LoRA styles for edits

FLUX Kontext dev has a LoRA version, and so does Qwen Image Edit Plus. Whether the FLUX dev LoRAs from the LoRA library work on Kontext has not been tested yet, and Qwen needs LoRAs of its own. Until a paid test shows the result, the LoRA versions stay out of the edit list.

### A companion app for Windows

A Windows app would match the phone app: the same chat on the computer with the same model files, the same job cards, and Replicate for every picture and video. The 1.0.0 logic for plans, search, memory, and the model manager already runs on a plain desktop Java runtime for that reason. A small standalone LoRA trainer for Windows comes first.

### OpenRouter as a second provider for images and video

Images and video come only from Replicate in 1.0.0. OpenRouter now runs its own image and video services, with 57 image models and 30 video models in October 2026. A switch in Settings and at the top of API CALL would choose Replicate or OpenRouter for each mode. Each provider would keep its own key, its own model list, and the last model used. The OpenRouter key that already serves Chat would work here too, because it belongs to the same site.

The main gain is cheaper 720p video. The table compares typical prices from October 2026.

| Job | Replicate | Cheapest on OpenRouter |
|---|---|---|
| One image, about 1 megapixel | FLUX schnell $0.003, FLUX dev $0.025 | FLUX.2 klein $0.014, Seedream 5 Flash $0.018 |
| One minute of video at 480p | WAN 2.2 fast, about $0.60 | HeyGen Video, about $1.20 |
| One minute of video at 720p | WAN 2.7, about $6 | Veo 3.1 Lite without audio, about $3 |

Replicate stays the cheaper choice for images. OpenRouter roughly halves the cost of 720p video.

Some limits would remain. OpenRouter hosts no custom LoRAs, so the consistent-face procedures stay on Replicate. Upscale 2x would also stay on Replicate. OpenRouter has no file upload service either. Choose photo and Use as would therefore place the photo inside the request itself.

No OpenRouter model offers a switch that turns the safety filter off. A few models accept a setting that loosens it:

- `safety_tolerance` on the Black Forest Labs FLUX image and video models
- `moderation` on the OpenAI image models
- `contentModeration` on the Runway video models
- `personGeneration` on the Veo video models

The preloaded OpenRouter list would favor models with one of these settings, and each setting would appear as a normal input card. The provider still decides. When it blocks a prompt, the card shows Failed.

The work covers new request code for both OpenRouter services, input cards built from OpenRouter's own model data, preloaded models, tests, and a short paid live test. The Help screen, the costs in Getting Started, and the design pages would change with it.

## Waiting for a Google Play edition

A Google Play listing is possible later. Play adds rules that a beta shared through a private repository does not need, so these items waited.

| Item | Why it waited |
|---|---|
| Target Android 16 (API level 36) | Play requires this level for new apps and updates from August 31, 2026. The beta targets API level 35 and installs on Android 10 or later. |
| App Bundle (AAB) and Play App Signing | Play accepts bundles only. Uploading the existing OT Studio signing key would let copies installed from GitHub update from Play. |
| Report button for generated content | Play requires apps that generate content with AI to let users report offensive results from inside the app. |
| Safety controls hidden or locked | Play policy limits apps that let users turn off content filters. The Play edition would keep the provider defaults, including the OpenRouter settings above. |
| Public privacy policy page | Play needs a privacy policy at a public web address. The policy exists in the repository today, which is private. |
| Trader contact details | Play shows trader contact details to users in the European Union. |
| Closed test | New personal developer accounts must run a closed test with 12 testers for 14 days before a public listing. The beta testers could fill that role. |

## Waiting for a public repository

The repository is private during the beta. When it opens, these pages move to the GitHub wiki, and the Help screen's wiki button will point there. The page links change at the same time.

## Checks still open in the beta

The 1.0.0 beta passed its automated tests and paid live tests on the Android 15 emulator. On a Pixel 10 Pro XL, chat on the phone, chat-led planning, web search, memory, and the history switch were tested as well. A few checks still need a phone with keys:

1. Choose photo through the phone's own photo picker
2. A second Suggest setup after a failed one
3. API CALL with the full on-screen keyboard open
4. A job card's Generate on the phone itself

Beta testers who try any of these can report the result to OrangeTiger.
