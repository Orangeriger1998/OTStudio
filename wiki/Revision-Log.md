<img src="images/otstudio-avatar.jpg" width="72" align="right" alt="">

# Revision Log

This log lists the main changes from one version to the next. It records what a user would notice, not every code change.

## 1.0.0 (October 2026)

The first release under the OT Studio name. It is a beta, shared with a small group of testers. The [Roadmap](Roadmap.md) lists what may come next.

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
