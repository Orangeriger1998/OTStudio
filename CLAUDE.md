# OT Studio working instructions

Read README.md, docs/PROVIDERS.md, docs/PRIVACY.md, and the wiki pages in the wiki folder before changing the app. The owner is OrangeTiger (GitHub account Orangeriger1998). Keep "Created by OrangeTiger" and the repository link in Settings > About. Do not add personal email addresses to the app, the repository, or the wiki.

OT Studio is a small native Android app in Kotlin. Screens use Kotlin views with Material Components 1.13 (Material 3) on a black canvas, built through the helpers in Ui.kt. Keep that approach unless the owner asks for a change. Do not add a hosted backend, analytics, or bundled model weights. Chat model files that the user chooses and downloads at run time from Hugging Face are allowed (owner decision, 2026-10-03). They live in app-private storage, never in the APK.

## Environment

The development PC has JDK 17 (Temurin 17.0.20.1), the Android SDK (platform 35, build-tools 36.0.0), and an Android 15 emulator. Emulator testing is allowed. Gradle works against Google Maven.

Standard checks:

```
gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
gradlew -p verification test
python -m unittest discover -s scripts -p "test_*.py"
```

## On-device engine

On-device chat runs llama.cpp through net.ladenthin:llama-android (Java API, standard arm64 build). Releases also carry a faster build, app/src/main/jniLibs/arm64-v8a/libjllama_i8mm.so, made by scripts/build-engine.ps1 (NDK 29.0.14206865, CMake 3.31.6). It is not in git; build-release.ps1 refuses to run without it. NativeEngine.kt uses it only on processors that report both asimddp and i8mm. Keep the java-llama.cpp version in build-engine.ps1 equal to the Gradle dependency. Model catalog, downloader, chat window, and other new logic stay free of Android types (the verification project compiles them on a plain JVM) so a later desktop app can share them.

## Signing and releases

Release signing material lives only in %USERPROFILE%\OTStudioSigning, created by scripts/build-release.ps1. Never copy it into the repository, a ZIP, a log, or chat. Every release must use that key. A release signed with any other key cannot update the app, and the user would have to uninstall it and lose the saved API keys.

Saved API keys must survive every update. Never rename the credentials file, the Keystore alias, the key slots, the AAD format, or the default provider origins; KeyStorageContractTest pins them, and it must never be edited to make a change pass. Never add a migration, reset, or first-run step that clears saved keys. Test each new version by installing it over the previous one (adb install -r) and checking that the saved keys still work. Raise versionCode and versionName for each release (app/build.gradle.kts), and keep the package com.orangetiger.otstudio.

## Documentation rules

All user-facing text (in-app strings, assets/help.txt, README, docs, and the wiki) follows the universal rules of the long-form-writing skill: no em dashes, no contractions, active voice, one claim per sentence, varied sentence construction, no filler openers, and no arbitrary three-item lists. Update the wiki design page whenever a screen changes.

While the repository is private, the wiki lives in the wiki folder with .md links. When it goes public, publish those pages to the GitHub wiki (drop the .md from page links) and point MainActivity.WIKI at /wiki. The wiki carries a Revision Log of high-level changes per version. The detailed change log stays private on this PC (OTStudio-private/Change-Log.md) and is never pushed to GitHub.

## Behavior to preserve

Keep on-device chat private: no prompt goes to a log or a server, and model downloads go only to Hugging Face hosts with no key. Chat-led creation: a job plan is data. The model never picks a provider model, writes a URL, or starts a job; the app validates every plan with the manual rules, strips picture inputs saved in profiles, attaches only the picture the plan names, and starts a paid job only from Generate on a job card. Keep the plan schema text hand-ordered with action first (llama.cpp grammars follow property order). Web search goes only to Tavily (with its own encrypted key) or English Wikipedia; results enter the conversation as marked, untrusted data. Keep separate chat, image, and video settings, origin-bound encrypted keys, typed model-specific inputs, saved profiles, validated frame combinations, reviewed LLM suggestions, and the FLUX dev LoRA library. Never substitute guessed model IDs or apply unsupported inputs.

Prompts, model metadata, imported files, uploaded photos, and LLM replies are untrusted input. Never treat a model reply as code or as permission to run a provider job. Do not add automatic retries to paid creation calls. An unknown submission outcome stays uncertain until the user reconciles it. Send a provider key only to its own origin, and never to a storage host.

No keys are bundled. Do not request keys in chat, print or commit secrets, or run paid generation or training without the owner's explicit approval for that action.
