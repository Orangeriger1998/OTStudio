<img src="images/otstudio-avatar.jpg" width="72" align="right" alt="">

# Revision Log

This log lists the main changes from one version to the next. It records what a user would notice, not every code change.

## LoRA Trainer update (October 2026)

**Real progress.** A bar at the bottom of the window shows how far the training is, from Replicate's own step count, with the time so far and an estimate of the time left. A line there says that closing the window does not stop the training. The rest of the window scrolls, so the bar stays in view.

**Any photo format.** The trainer now accepts JPG, PNG, WebP, HEIC, and most other photo files, and converts them itself, so a photo saved under the wrong file type no longer stops it. Photos it cannot use are left out and named with the reason, and the accepted formats show under Choose photos. Choosing again adds photos, and Clear starts over.

## 1.6.0 (October 2026)

**Several pictures at once.** Models that take more than one picture (Seedream 4.5 up to 14, FLUX.2 up to 5, Qwen Image Edit Plus and P-Image Edit up to 3) now get them all. Edit... has Add a reference photo, so an outfit, logo, or place can join the picture being edited as image 2, image 3, and so on. In API CALL, each attached picture is listed with its own Remove, and Add another photo adds the next.

## 1.5.1 (October 2026)

**Open folder.** After Save, the message offers Open folder beside Change, and the Save folder card in Settings has Open folder too. Android opens the folder in the phone's file manager, such as Files.

## 1.5.0 (October 2026)

**Repeat.** Every picture and video card has Repeat. It brings back the same model and settings, including the starting and ending pictures, and puts the words in the message box to change before sending again. For an edit, an expansion, or a longer video, it opens that dialog again, filled in.

## 1.4.1 (October 2026)

**Upscale explains large pictures.** The upscaler takes pictures up to about 2 megapixels. For a larger picture, such as an expanded one, Upscale 2x now says the picture is already large enough and sends nothing, instead of failing with a GPU error.

## 1.4.0 (October 2026)

**SpicyAPI, words only.** Four adult models from SpicyAPI join the model lists: Spicy Image 1 and Spicy Image 1 Pro for pictures, and Spicy Video 1 and Spicy Cinema 1 for video. They work from words alone and never take a photo. Their key goes in a new SpicyAPI card in Settings, apart from the Replicate key.

## 1.3.1 (October 2026)

**Remove one picture at a time.** Each attached picture has its own line and Remove button on the Image and Video screens, so the ending picture can go while the starting picture stays. Pictures are named plainly: Starting picture and Ending picture.

## 1.3.0 (October 2026)

**Make it longer.** A finished video has Make it longer: say what happens next, and the app makes about 5 more seconds that start from the video's last frame, then joins the parts into one video on the phone. Repeat it for 15, 20, or more seconds. With WAN 2.2 fast each part costs about 5 to 11 cents.

**Length for WAN 2.2.** The simple view of API CALL shows Length in frames for WAN 2.2: 81 frames make 5 seconds, and 121 make 7.5.

## 1.2.10 (October 2026)

**Clear image and video history.** Settings, Image and Video, now have Clear image history and Clear video history, like Clear chat history. Each picture and video card also has Delete. Copies saved with Save stay.

## 1.2.9 (October 2026)

**A clear word on WAN 2.7 refusals.** WAN 2.7 runs on Alibaba's service, which checks every input picture with a check that cannot be switched off. When it refuses a picture, the card now says so and points to WAN 2.2 fast, which runs on Replicate's own servers with its safety checker under your control.

## 1.2.8 (October 2026)

**Attached pictures in plain sight.** When a picture is attached to the Image or Video model, a line under the model says so, with a Remove picture button. An attached picture shapes every request, so it no longer hides in API CALL.

## 1.2.7 (October 2026)

**Private LoRAs work.** A LoRA trained with the OT Studio LoRA Trainer lives in a private model in your Replicate account, which the public FLUX dev LoRA model cannot read ("Failed to download tarball"). OT Studio now runs your trained model itself, with your settings, so the LoRA stays private.

**Pictures from more file servers.** A picture sent as generic data is now recognized by its file name and checked by its contents before it is saved.

## 1.2.6 (October 2026)

**A simple API CALL.** The panel now opens with only the settings most people change, and names each model by what it is for and what it costs. Show all settings opens everything else. An attached picture shows as attached, with a button to remove it.

**Seedream edits work after Apply.** Saved settings now remember inputs that take a list of pictures, so Seedream 4.5 no longer refuses its picture. Settings saved before this version are repaired on their own.

## 1.2.5 (October 2026)

**Simpler API CALL.** Every input now has one control and a plain title, such as "Safety checker" or "Shape", with the provider's name in small type below. Inputs start at the model's default; change one, and Use default puts it back. The safety setting is one switch, Safety checker on or off, instead of two checkboxes with a double negative.

## 1.2.4 (October 2026)

**LoRAs in one tap.** Each saved LoRA, and every new import, offers Use for pictures. It switches Image to FLUX dev LoRA with that LoRA and the safety checker off, and starts the prompt with the trigger word. A prompt in Image that names a saved trigger word on another model offers Switch and generate. In Chat, naming a LoRA's trigger word or name plans the picture on FLUX dev LoRA with it.

**Failures say why.** A job that fails at the provider now shows the provider's own reason. When a safety checker stopped it, the card says so and where to switch it off.

**Chat jobs run with the safety checker off**, on every model that offers the switch, as edits already did.

## 1.2.2 (October 2026)

**Motion becomes a video.** With a picture chosen, a description of motion, such as "she walks toward the camera", now plans a video that starts from the picture instead of an edit. Job cards for a chosen picture offer Make a video from it instead, and a chat message whose job failed offers Try again.

**Edits keep the picture's shape.** An edit no longer carries a shape saved in the model's profile, such as 9:16, which made Seedream 4.5 refuse the request.

**Clearer refusals.** When a provider rejects a request, the card now shows the provider's own reason.

**The chosen picture stays chosen.** "Talking about" now survives an app restart or update.

## 1.2.1 (October 2026)

**Expand keeps full quality.** Expand now uses FLUX Fill dev with its safety checker off. The phone rebuilds the result at the original's full resolution, with the original pixels unchanged in the middle and a soft, color-matched blend into the new area.

## 1.2.0 (October 2026)

**Expand a picture to a new shape.** Image cards gained Expand..., which turns a picture into wide 16:9, tall 9:16, square, or six other shapes. The picture stays exactly as it is, and Bria Expand Image adds new picture around it. The photo button in Image offers it, and Chat plans it from requests such as "make it wide".

## 1.1.3 (October 2026)

**Chat on the phone answers again after a restart.** In 1.0.0 and 1.1.0, the first message after the on-device model loaded could fail with "The model returned no text". That is fixed.

**Chat edits the photo you chose.** With a photo chosen, a request such as "make her jacket red" now becomes an edit of that photo instead of a new picture. A job card for a new picture also offers Edit the chosen picture instead.

**Faster replies with a chosen picture.** On the phone, the first word now takes seconds instead of most of a minute, because the picture list no longer forces the phone to reread the whole conversation.

## 1.1.0 (October 2026)

**Photos from your phone in every mode.** A photo button at the left of the message bar adds a photo from the phone. In Chat, the next message can edit the photo or turn it into a video. In Image, the photo can be edited or used as the reference for the next image. In Video, it becomes the first or the last frame. A small copy of the chosen picture now sits beside "Talking about:".

**Edit with your own words.** When the chat returns no plan and a picture is chosen, the fallback card offers Edit the picture with my words.

**Keys kept across updates.** Saved API keys stay in place when the app is updated. Automated tests now guard the names under which the phone stores them.

**Steadier stop, fewer crashes.** Stop now also ends a web search, the trainer's Cancel waits until Replicate has a training to cancel, and several rare crashes are fixed.

## 1.0.0 (October 2026)

The first release under the OT Studio name. It is a beta, shared with a small group of testers. The [Roadmap](Roadmap.md) lists what may come next.

**Chat on the phone.** Chat can run on the phone itself with a downloaded model, offline and at no cost per message. A new [Model Manager](Design-Model-Manager.md) downloads, checks, and deletes models from Hugging Face. The suggested model is Qwen3 4B Instruct, abliterated. On recent phones the app picks a faster engine build that reads messages about three times faster. Replies stream word by word, Stop ends one at once, and a short line reports the speed. A new install asks where Chat should run.

**Create by chatting.** Ask in plain words for a picture, an edit, or a video. The chat answers with a [job card](Design-Job-Card.md) that shows the exact prompt, the model, and the likely cost, and nothing is paid for until Generate. Results appear in the chat, and the next request can build on them, such as "turn that picture into a video".

**Edit an image.** Image cards gained Edit..., which changes one thing in a picture from a plain instruction. Nine Replicate editing models are offered, with FLUX Kontext dev first.

**Memory.** A new [Memory](Design-Memory.md) page holds standing instructions that open every chat.

**Web search.** The chat can look up current facts on Wikipedia, or on the whole web with a free Tavily key, and lists its sources under the reply.

**Chat history choice.** Save chat history can be switched off for chats that vanish when the app closes. Clear chat history deletes every chat message.

**Thinking.** Models that think before answering can show that thinking in a separate, folded block.

**Plain summaries.** Setup and job cards describe settings in words, such as "Image, square", instead of raw setting names.

**LoRA Trainer for Windows.** A standalone program trains a LoRA from 10 to 20 photos: paste the Replicate token, choose the photos, name the LoRA, and press Train LoRA. It creates the private model, starts the training, and shows the reference to use in OT Studio on any device.

**Default chat model.** New installs use Mistral Nemo through OpenRouter, which follows the job-plan format reliably. MythoMax stays in the list.

**New look.** Every screen moved to Google's Material 3 components on a black canvas, in the tiger orange and logo blue of the OT Studio brand. Chat replies appear as bubbles. Image and video results appear as cards with colored status labels.

**Splash screen and icon.** The app opens on the full OTStudio logo, and the launcher shows the tiger.

**Help screen.** A new in-app guide covers keys, typical costs, the app's limits, and where to find this wiki.

**Photos as inputs.** Image and frame inputs gained a Choose photo button that uploads a phone photo to the user's Replicate account. Finished images gained Use as..., which turns an image into the reference for the next image or a starting or ending frame for a video.

**Upscale 2x.** Image cards can double an image to about 2048 by 2048.

**Remembered save folder.** Save asks for a folder once, then saves there every time. Share sends a file straight from the app without saving a copy first.

**Downloads fixed.** Replicate began delivering results from new Cloudflare storage. Version 1.0 downloads from it, and it never sends the user's key to the storage host.

**Better handling of failures.** A job whose result cannot be used now closes with its job ID kept, instead of blocking new jobs. A Stop tracking button releases any stuck job. A request the provider plainly refused is marked Failed rather than uncertain.

**Smaller fixes.**

- Back from Settings no longer closes the app.
- Turning the phone keeps Settings open.
- Chatty replies to Suggest setup now load correctly.
- Blank optional inputs are no longer sent.
- Models with unusual inputs load instead of failing.
- The API CALL panel no longer slides under the status bar on Android 15.
- Photo uploads accept Replicate's newer file IDs, which contain dots.

**Training script.** The LoRA training script accepts Replicate's current upload links and file IDs. It never sends the token to storage hosts.

## 0.2 (preview, not released)

An unreleased preview under the name Pocket Studio. It introduced the three modes, API CALL, saved model profiles, the LoRA Library, and the training script.
