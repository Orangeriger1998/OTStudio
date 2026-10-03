<img src="images/otstudio-avatar.jpg" width="72" align="right" alt="">

# Roadmap

This page lists the changes under consideration after the 1.0.0 beta. Nothing here is promised. Each item names the reason it waited and what it would take. The [Revision Log](Revision-Log.md) records what actually shipped.

## Under consideration for 1.1.0

### Chat-led creation

In 1.0.0, Chat, Image, and Video work apart. Chat only talks. A prompt typed in Image or Video goes straight to that model. The chat model links to the media models in one place only: Suggest setup in API CALL.

Chat-led creation would make the chat the main way to work. The user describes what they want in plain words. The chat model turns that into a job plan: the full image or video prompt, the model, its settings, and any reference image or frames. The plan appears in the chat as a job card that shows the model, the exact prompt, and the likely cost. The user taps Generate, and the result lands in the same conversation. The next request can build on it. For example, "now a five-second clip where she stands up" would use the last image as the starting frame.

Image and video models keep no memory between requests. Each one reads a single, complete description. The chat model fills that gap. It rewrites each chat request as a complete description. The user never needs to learn how media prompts work.

#### Edit by conversation

A request such as "change the orange border to black" refers to an earlier image. The app keeps every image it makes, both the file on the phone and its provider link. It would send that image, together with the instruction, to an editing model. An editing model changes only what the instruction names and keeps the rest. FLUX.1 Kontext [pro] on Replicate is one candidate. Its price and inputs still need checking. The FLUX dev Reference in 1.0.0 differs, because it repaints the whole image loosely.

"That image" means the last image in the chat. The user can tap a card to point at a different one.

#### Design rules

1. **The chat model helps, but it never decides.** Each job card shows the exact prompt the media model will receive. The user can edit it before tapping Generate.
2. **The user's own words always remain an option.** Sometimes the chat model declines a request or returns no usable plan. The app then says so plainly and offers to send the user's text exactly as typed. A missing plan never becomes a dead end or a silent failure.
3. **The app keeps track of references.** It decides which image "that" means, carries frames forward, and attaches references itself. These steps never depend on how the chat model reads the request.
4. **Each paid job needs one tap from the user.** The chat model never starts a job on its own. It also stops a runaway loop from using up credit.
5. **The chat model is chosen by testing.** Models differ in how reliably they return a clean plan and in how faithfully they keep the user's request. Before the build, a fixed set of sample requests would measure several OpenRouter chat models on both counts. The 1.0.0 default, MythoMax, has no tool calling but can return structured replies, which is how Suggest setup already works.

The media model applies its own settings and its provider's usage rules. Those are separate from the chat model and stay unchanged.

The chat model reads text only, so it knows what it asked for but not what came back. A model that can read images could compare a result with earlier ones. That would cost more per message and stays optional.

The three modes would stay as the manual path for exact control. Job cards and setup cards would also show plain summaries, such as "Square image" or "5-second clip from your photo," in place of raw setting names like `aspect_ratio=1:1`.

The work reuses Suggest setup, Use as, the frame inputs, and the feed. It is larger than the provider switch below.

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

The 1.0.0 beta passed its automated tests and a paid live test on the Android 15 emulator. Three checks still need a real phone:

1. Choose photo through the phone's own photo picker
2. A second Suggest setup after a failed one
3. API CALL with the full on-screen keyboard open

Beta testers who try any of these can report the result to OrangeTiger.
