# OT Studio working instructions

Read README.md, docs/PROVIDERS.md, docs/PRIVACY.md, and the wiki pages in the wiki folder before changing the app. The owner is OrangeTiger (GitHub account Orangeriger1998). Keep "Created by OrangeTiger" and the repository link in Settings > About. Do not add personal email addresses to the app, the repository, or the wiki.

OT Studio is a small native Android app in Kotlin. Screens use Kotlin views with Material Components 1.13 (Material 3) on a black canvas, built through the helpers in Ui.kt. Keep that approach unless the owner asks for a change. Do not add a hosted backend, analytics, or bundled model weights.

## Environment

The development PC has JDK 17 (Temurin 17.0.20.1), the Android SDK (platform 35, build-tools 36.0.0), and an Android 15 emulator. Emulator testing is allowed. Gradle works against Google Maven.

Standard checks:

```
gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
gradlew -p verification test
python -m unittest discover -s scripts -p test_train_lora.py
```

## Signing and releases

Release signing material lives only in %USERPROFILE%\OTStudioSigning, created by scripts/build-release.ps1. Never copy it into the repository, a ZIP, a log, or chat. Every release must use that key. Raise versionCode and versionName for each release (app/build.gradle.kts), and keep the package com.orangetiger.otstudio.

## Documentation rules

All user-facing text (in-app strings, assets/help.txt, README, docs, and the wiki) follows the universal rules of the long-form-writing skill: no em dashes, no contractions, active voice, one claim per sentence, varied sentence construction, no filler openers, and no arbitrary three-item lists. Update the wiki design page whenever a screen changes.

While the repository is private, the wiki lives in the wiki folder with .md links. When it goes public, publish those pages to the GitHub wiki (drop the .md from page links) and point MainActivity.WIKI at /wiki. The wiki carries a Revision Log of high-level changes per version. The detailed change log stays private on this PC (OTStudio-private/Change-Log.md) and is never pushed to GitHub.

## Behavior to preserve

Keep separate chat, image, and video settings, origin-bound encrypted keys, typed model-specific inputs, saved profiles, validated frame combinations, reviewed LLM suggestions, and the FLUX dev LoRA library. Never substitute guessed model IDs or apply unsupported inputs.

Prompts, model metadata, imported files, uploaded photos, and LLM replies are untrusted input. Never treat a model reply as code or as permission to run a provider job. Do not add automatic retries to paid creation calls. An unknown submission outcome stays uncertain until the user reconciles it. Send a provider key only to its own origin, and never to a storage host.

No keys are bundled. Do not request keys in chat, print or commit secrets, or run paid generation or training without the owner's explicit approval for that action.
