# Provider contracts and model controls

Examined in the Codex in-app Browser on 2026-10-02. These are schema/catalog checks, not live authenticated generation results.

## Defaults

Chat: OpenRouter `mistralai/mistral-nemo`, with `qwen/qwen3-30b-a3b-instruct-2507`, `gryphe/mythomax-l2-13b`, and `qwen/qwen3-8b` also offered. The default moved from MythoMax to Mistral Nemo on 2026-10-03, because MythoMax often ignored the job-plan format (see Chat-led creation below). Chat can also run on the phone with a downloaded model. Model IDs remain editable and are remembered when saved.

Image: Replicate `black-forest-labs/flux-schnell`, one output, 1:1. Video: `wan-video/wan-2.7-t2v`, five seconds, 720p, 16:9. OT Studio explicitly chooses 720p instead of the provider's 1080p default. These defaults fill non-secret settings; owners supply their keys.

Text uses Chat Completions, stream=false, no automatic tool execution. max_tokens defaults to 1024, with an app ceiling of 8192. Optional common controls are temperature, top_p, frequency/presence penalties, and seed. Support can vary by text model. Context includes at most 12 prior completed turns and 8000 characters including the prompt.

Sources: [Mistral Nemo](https://openrouter.ai/mistralai/mistral-nemo), [Qwen3 30B-A3B](https://openrouter.ai/qwen/qwen3-30b-a3b-instruct-2507), [MythoMax](https://openrouter.ai/models?q=mythomax), [Qwen3 8B](https://openrouter.ai/models?q=qwen3-8b).

## Chat-led creation

With Create from chat on, every chat request asks for a job plan through `response_format` with a strict JSON schema: `action` (answer, image, edit, or video), `reply`, `prompt`, `reference` (none, last_image, or one listed picture), `shape`, and `seconds`. The properties are written in that order, because constrained decoding follows it, and the model should choose the action before anything else. With an early test order that put `reference`, `seconds`, and `shape` first, the stock Qwen3 4B turned most questions into videos.

On the phone, llama.cpp turns the schema into a grammar, so every reply is valid JSON. Through OpenRouter, the request also sends `provider.require_parameters: true`, so only providers that honor `response_format` serve it. Some still return prose; the app then treats the prose as an ordinary answer.

The model never chooses a provider model or a URL, and never starts a job. The app maps the plan onto the user's models, drops picture inputs saved in model profiles, attaches only the picture the plan names, and validates everything with the manual rules. Generate on the job card is the only way a plan becomes a request.

Test results on 2026-10-03 (26 requests in `OTStudio-private/tools/planner-bench`: questions, new pictures with shapes, edits that must pick the right picture, videos from a picture, follow-ups, small talk):

| Planner | Result |
|---|---|
| Huihui Qwen3 4B Instruct abliterated, i1-Q4_0 (on-device default) | 26 of 26 correct |
| Mistral Nemo through OpenRouter (8 of the requests, on the emulator) | 7 of 8 correct; the mixed request got a prose answer |
| Qwen3 30B-A3B Instruct through OpenRouter (same 8) | 8 of 8 correct |
| MythoMax 13B through OpenRouter (same 8) | about half; it often ignored the format and once invented an image address |

## Image models

| Model | Verified controls |
|---|---|
| [FLUX schnell](https://replicate.com/black-forest-labs/flux-schnell/api/schema) | Aspect, 0.25/1 megapixels, go_fast, seed, 1-4 outputs, 1-4 steps, format/quality, disable_safety_checker |
| [FLUX dev](https://replicate.com/black-forest-labs/flux-dev/api/schema) | Similar controls; 1-50 steps, guidance, image URL, prompt_strength |
| [FLUX 1.1 pro](https://replicate.com/black-forest-labs/flux-1.1-pro/api/schema) | Aspect/custom width and height, seed, image_prompt, format/quality, safety_tolerance, prompt_upsampling. No disable_safety_checker |
| [FLUX dev LoRA](https://replicate.com/black-forest-labs/flux-dev-lora/api/schema) | Dev-style controls, guidance default 3, lora_weights/scale, extra_lora/scale. Scale -1 to 3; go_fast applies a 1.5 multiplier per provider description |

Credential inputs such as hf_api_token and civitai_api_token are excluded. The schema loader also excludes credential-named, password-format, and write-only inputs. Required unsupported inputs block calls.

## Video models and starting frames

| Model | Resolution | Starting frame | Ending frame | Other controls |
|---|---|---|---|---|
| [WAN 2.7 T2V](https://replicate.com/wan-video/wan-2.7-t2v/api/schema) | 720p/1080p | No | No | 2-15 seconds, aspect, seed, negative prompt, prompt expansion, audio URL |
| [WAN 2.7 I2V](https://replicate.com/wan-video/wan-2.7-i2v/api/schema) | 720p/1080p | first_frame | last_frame | 2-15 seconds, audio, seed, negative prompt, prompt expansion, first_clip continuation |
| [WAN 2.2 T2V Fast](https://replicate.com/wan-video/wan-2.2-t2v-fast/api/schema) | 480p/720p | No | No | 81-121 frames, frames_per_second 5-30, interpolation, go_fast, sample_shift, seed, optimize_prompt, safety toggle, two transformer LoRAs |
| [WAN 2.2 I2V Fast](https://replicate.com/wan-video/wan-2.2-i2v-fast/api/schema) | 480p/720p | image, required | last_image | Same frame/rate/fast/shift/seed/LoRA controls; no aspect_ratio or optimize_prompt |
| [WaveSpeed WAN 2.1 480p](https://replicate.com/wavespeedai/wan-2.1-t2v-480p/api/schema) / [720p](https://replicate.com/wavespeedai/wan-2.1-t2v-720p/api/schema) | Model ID chooses resolution | No | No | fast_mode Off/Balanced/Fast, sample_shift/steps/guide_scale, seed, negative prompt, LoRA, safety toggle |
| [WAN 2.1 1.3B](https://replicate.com/wan-video/wan-2.1-1.3b/api/schema) | 480p | No | No | frame_num 17/33/49/65/81 at fixed 16 fps, seed, sample_shift/steps/guide_scale |

For WaveSpeed WAN 2.1, the resolution selector changes the actual model endpoint. No unsupported resolution, duration, or FPS body field is added. fast_mode is an enum, not a Boolean.

WAN 2.2 uses **frames_per_second**, plural. Interpolation can produce 30 fps output. T2V defaults interpolation on; I2V defaults it off. Provider pricing uses duration at 16 fps, so playback FPS is not a billing-duration guarantee.

WAN 2.7 exposes no FPS or disable_safety_checker input in the schemas checked. I2V needs either first_frame or first_clip, never both. last_frame requires first_frame. Frame images are JPG/PNG/BMP/WebP up to 20 MB. Clips are MP4/MOV, 2-10 seconds, up to 100 MB. Audio is WAV/MP3, 3-30 seconds, up to 15 MB. The app validates URL form and input combinations; remote content/size checks remain with Replicate.

## Discovery, dispatch, and recovery

API CALL filters by required features and resolution. **Refresh model inputs** performs a metadata GET to `/models/{owner}/{model}` and parses latest_version.openapi_schema. It makes no generation request. Primitive typed fields, enums, and simple schema references are supported. Unsupported required structures block calls.

Each input card has one control. An input stays at the provider default, and is omitted, until the user changes it; Use default omits it again. An explicit false is retained. The safety switch shows the checker itself and sends the inverse as disable_safety_checker. Profiles are saved per mode, origin, and model. Suggestions call the configured text LLM with the prompt and matching catalog, validate model IDs/types/choices/bounds, and require review. Required media inputs still need to be filled before Generate. Text responses do not automatically invoke media generation.

Creation uses POST `/models/{owner}/{model}/predictions` with only prompt and selected supported inputs. A provider needing a different protocol or community version-hash prediction route needs an adapter change. LoRA references can contain versions because they are inputs to the supported FLUX dev LoRA endpoint.

GET `/predictions/{id}` refreshes; POST `/predictions/{id}/cancel` cancels. One request is active at a time. Polling runs every six seconds while the Activity is resumed and pauses after errors. There is no continuous background execution guarantee, automatic retry of generation, or exactly-once provider promise.

One to four outputs are retained and individually previewed/saved/shared. Completion state and all output links commit in one database transaction. Media limits are 128 MiB per file and 512 MiB total. Every output and redirect must use HTTPS on replicate.delivery, its subdomains, or Replicate's Cloudflare R2 output bucket (`ai-gateway-outputs.0d37909e38d3e99c29fa2cd343ac421a.r2.cloudflarestorage.com`, observed in live testing on 2026-10-02). A refused output names its host, so a future host change is easy to diagnose.

The HTTP reference says file downloads can require Authorization, while the output guide also describes direct URLs. The app first downloads without a credential. On HTTP 401/403 it may retry using the job's current Replicate key, only for a job created at the official api.replicate.com origin and only on a replicate.delivery host. The key never goes to the R2 storage host, whose presigned links answer 403 when they expire. Custom-provider keys never go to Replicate delivery. API redirects are disabled.

Sources: [HTTP reference](https://replicate.com/docs/reference/http), [output files](https://replicate.com/docs/topics/predictions/output-files), [official file client](https://github.com/replicate/replicate-python/blob/main/replicate/file.py).

Version 1.0 was tested live with the owner's own keys: chat, model metadata, FLUX images, downloads, and WAN 2.7 video. Provider restrictions, availability, and normal prices apply. See [LoRA training](LORA-TRAINING.md) for the separate photo-training helper.

## Web search

With Allow web search on, a job plan may ask for a search (action `search`, with the query in `prompt`). The app runs it, adds the results to the conversation as quoted data inside markers with a note that they are untrusted, and asks the model again. A message may search at most three times; after that, or when the results would not fit the on-device context, the search action is no longer offered. Searches run one at a time.

| Service | Request | Notes |
|---|---|---|
| [Tavily](https://docs.tavily.com) (with the user's key) | POST `https://api.tavily.com/search`, `search_depth` basic, `max_results` 5, `include_answer` true | 1,000 free credits a month (October 2026); a basic search uses one. The key goes only to this origin. |
| [Wikipedia](https://www.mediawiki.org/wiki/API:Etiquette) (no key) | GET `https://en.wikipedia.org/w/api.php`: `list=search` (3 titles), then `prop=extracts` with `exintro` | User-Agent `OTStudio/1.0 (github.com/Orangeriger1998/OTStudio)`. Text is CC BY-SA 4.0, credited under the reply. |

No redirects are followed, and replies are capped at 512 KB. Result text is cut to a few hundred characters per source, and only https links appear.

## Editing models

Edit on an image card uploads the image through the Files API, then starts one prediction on the chosen editing model with the instruction as `prompt`. Schemas were checked on 2026-10-03 at each model's `/api/schema` page. Every model listed exposes `disable_safety_checker` (provider default false). OT Studio sends it as true and sends `aspect_ratio` as `match_input_image`. Replicate's usage policy still applies.

| Model | Image input | Price (October 2026) |
|---|---|---|
| [black-forest-labs/flux-kontext-dev](https://replicate.com/black-forest-labs/flux-kontext-dev/api/schema) (default) | `input_image`, one URL, required | $0.025 per image |
| [qwen/qwen-image-edit-plus](https://replicate.com/qwen/qwen-image-edit-plus/api/schema) | `image`, list, required | $0.03 per image |
| [qwen/qwen-image-edit](https://replicate.com/qwen/qwen-image-edit/api/schema) | `image`, one URL, required | $0.03 per image |
| [qwen/qwen-image-edit-2511](https://replicate.com/qwen/qwen-image-edit-2511/api/schema) | `image`, list, required | $0.03 per image |
| [black-forest-labs/flux-2-dev](https://replicate.com/black-forest-labs/flux-2-dev/api/schema) | `input_images`, list | $0.012 to $0.014 per megapixel in and out |
| [black-forest-labs/flux-2-klein-9b](https://replicate.com/black-forest-labs/flux-2-klein-9b/api/schema) | `images`, list | $0.015 per output megapixel |
| [black-forest-labs/flux-2-klein-4b](https://replicate.com/black-forest-labs/flux-2-klein-4b/api/schema) | `images`, list | $1 per 1,000 megapixels in and out |
| [bytedance/seedream-4.5](https://replicate.com/bytedance/seedream-4.5/api/schema) | `image_input`, list | $0.04 per image |
| [prunaai/p-image-edit](https://replicate.com/prunaai/p-image-edit/api/schema) | `images`, list | $0.01 per image |

A list input receives a one-item JSON array. The schema loader now reads arrays of URL strings as one URL field sent that way. Arrays of other types stay unsupported. The LoRA versions of Kontext and Qwen wait until a test shows whether FLUX dev LoRAs work on Kontext. Qwen 2511 is listed without its LoRA inputs.

Excluded because they lack `disable_safety_checker`: FLUX Kontext pro and max, FLUX.2 pro, max, and flex, the nano-banana family, gpt-image, Seedream 4 and 5 lite, WAN 2.7 image, Grok Imagine image, and Bria FIBO edit.

Live test on 2026-10-03 (owner-approved, about $0.07): one watercolor image, one instruction ("Change the white window frame to dark green. Keep everything else the same."). All four tested models succeeded. Kontext dev changed only the frame. Qwen Image Edit Plus and P-Image Edit also darkened the sill below it. FLUX.2 klein 4B also turned part of a wall shadow green.

## Photo uploads and upscaling

Choose photo, Use as..., and Upscale 2x upload an image to POST `/v1/files` as multipart form data. The app accepts only a reply whose `urls.get` equals `https://api.replicate.com/v1/files/{id}` for the returned ID, then passes that URL as the model input. File IDs may contain letters, digits, dots, hyphens, and underscores. Replicate began issuing dotted IDs of about 53 characters in October 2026. An ID that starts with a dot, ends with a dot, or contains two dots in a row is refused, so it can never act as a path step. Uploads require the Replicate origin, a JPG, PNG, or WebP image, and at most 20 MB.

Upscale 2x runs `nightmareai/real-esrgan` with `scale` 2 and no prompt. That model stays out of the model lists because it takes an image instead of a prompt.

## OpenRouter pictures and videos

Owner decision, 2026-10-09, matching OT Studio for Windows: eight OpenRouter models appear in the Image and Video lists under the `openrouter/` prefix. They run at `https://openrouter.ai/api/v1` with the key saved for Chat, and only while Chat's address is OpenRouter's. Settings and prices come from OpenRouter's model lists, checked 2026-10-09.

| Model | Mode | Settings | Price |
|---|---|---|---|
| `bytedance-seed/seedream-4.5` | Image | `aspect_ratio`, `resolution` 1K/2K/4K, `n` 1 to 4, `seed`, up to 14 references | $0.04 per picture |
| `google/gemini-2.5-flash-image` | Image | `aspect_ratio`, up to 3 references | about $0.039 per picture (tokens) |
| `black-forest-labs/flux.2-pro` | Image | `aspect_ratio`, `seed`, up to 8 references | $0.03 per megapixel |
| `qwen/qwen-image-3` | Image | `aspect_ratio`, `resolution` 1K/2K, `n` 1 to 4, `seed`, up to 4 references | $0.03 per picture |
| `alibaba/wan-2.7` | Video | 2 to 10 seconds, 720p/1080p, five shapes, sound, `seed`, first and last frame | $0.10 per second |
| `kwaivgi/kling-v3.0-pro` | Video | 3 to 15 seconds, 720p, three shapes, sound, first and last frame | $0.112 per second, $0.168 with sound |
| `bytedance/seedance-1-5-pro` | Video | 4 to 12 seconds, 480p to 1080p, seven shapes, sound, `seed`, first and last frame | tokens |
| `google/veo-3.1-lite` | Video | 4, 6, or 8 seconds, 720p/1080p, 16:9 or 9:16, sound, `seed`, first and last frame | $0.03 to $0.08 per second |

Pictures: `POST /images` with `model`, `prompt`, the settings, and `input_references` as `[{type: "image_url", image_url: {url}}]`. The answer carries the pictures themselves in `data[].b64_json`; the app accepts up to 64 MB and keeps only PNG, JPG, or WebP files. Videos: `POST /videos` with `frame_images` entries that add `frame_type` `first_frame` or `last_frame`. It answers 202 with a task; the app checks `GET /videos/{id}` about every 30 seconds (`pending`, `in_progress`, `completed` with `unsigned_urls`, `failed`, `cancelled`, `expired`). Tasks cannot be canceled from the app. Finished videos download from openrouter.ai, and the key may follow only to that host.

Photos go inside the request as `data:image/jpeg;base64,` addresses, redrawn upright at up to 2048 pixels. OpenRouter cannot open Replicate's private upload links, so a Replicate upload is refused for these models.

## SpicyAPI (text only)

Owner decision, 2026-10-05: four SpicyAPI models are offered, all working from words alone. They appear in the Image and Video model lists under the `spicyapi/` prefix.

| Model | Mode | Settings the app sends | Price |
|---|---|---|---|
| `spicy-image-1` | Image | `size` (eleven sizes), `n` 1 to 4, `style`, `negative_prompt`, `seed`, `enhance_prompt` | $0.06 per picture |
| `spicy-image-1-pro` | Image | the same | $0.09 per picture |
| `spicy-video-1` | Video | `aspect_ratio` | from $0.20 per second |
| `spicy-cinema-1` | Video | `duration` 3 to 15, `resolution` 480P/720P/1080P, `aspect_ratio` | from $0.14 per second |

Pictures: `POST https://api.spicyapi.com/v1/images/generations`, answered at once with `data[].url`. Videos: `POST /v1/videos/generations` returns a task; the app checks `GET /v1/videos/tasks/{id}` (`queued`, `processing`, `succeeded` with `output.video_url`, `failed` with `error` and `error_code`). Tasks cannot be canceled. Results download from `cdn.spicyapi.com` only.

SpicyAPI's photo-based models (actions, edits, image-to-video, motion transfer, character and reference video, voice cloning) are deliberately not offered: OT Studio works with photos of real people, and it cannot check who is in a photo or whether they agreed. The schemas of the four offered models have no picture inputs, and a test checks that.

