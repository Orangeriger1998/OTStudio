<img src="images/otstudio-avatar.jpg" width="72" align="right" alt="">

# Procedure 4: Train a LoRA and Use It

Training runs on Replicate. Start it from the phone, or from a Windows computer with the **OT Studio LoRA Trainer**. The phone then uses the finished LoRA through the LoRA Library.

**Before you start:**

- 10 to 20 photos of one face or style ([Procedure 3](Procedure-Create-a-LoRA-Training-Set.md))
- your Replicate key, saved for Image in Settings
- credit on your Replicate account

**Cost and time:** one FLUX dev training run with 1,000 steps costs about $1.50 to $2 and takes about 20 to 30 minutes. The Qwen Image trainer is billed by the second, usually about $1.40 to $2.75.

## Part 1: Train on the phone

1. Tap the **LoRA library** icon, then **Train a LoRA**.
2. Type a **LoRA name**, such as `My face`. Choose the **Trainer**: FLUX dev (standard) or Qwen Image (alternate).
3. Keep the suggested trigger word, such as `MYFACE`, or type another word that means nothing on its own. For Qwen, keep or change the plain words, such as `a person named Anna`.
4. Leave **Steps** at 1000 for a first try.
5. Tap **Choose photos** and select 10 to 20 photos, each at least 512 pixels on its short side.
6. Tick the box that agrees to the upload and the charge, then tap **Train**. Check the summary and tap **Start training**.
7. The library shows a card for the training. It names the stage, then shows the percentage and the minutes left. Keep the app open to follow it; the training runs on at Replicate either way, and the card catches up the next time the app opens.
8. When the card says **Ready**, the LoRA is in your saved LoRAs. Go to Part 3.

## Part 2: Or train on your computer

1. From the [Releases](https://github.com/Orangeriger1998/OTStudio/releases) page, download `OT-Studio-LoRA-Trainer.exe` and open it. It needs no installation. Windows may say it protected your PC, because the program carries no publisher signature. Choose **More info**, then **Run anyway**.
2. Paste your Replicate token and click **Check token**. The window then shows your account name. The token stays in memory and is never saved.
3. Choose **Choose photos...** and select 10 to 20 photos. JPG, PNG, WebP, HEIC, and most other photo files work, each at least 512 pixels on its short side. The trainer leaves out any photo it cannot use and names it with the reason. Choose again to add more, or tap **Clear** to start over.
4. Type a **LoRA name**, such as `My face`. The app suggests a **trigger word**, such as `MYFACE`. Keep it, or type another word that means nothing on its own.
5. Tick the box that agrees to the upload and the charge, then choose **Train LoRA**.
6. The trainer creates a private model for the LoRA in your Replicate account, uploads the photos, and starts the training. A bar at the bottom of the window shows how far the training is, read from Replicate's own log every 30 seconds, with the minutes so far and an estimate of the minutes left.
7. When training finishes, the window shows the **Reference**, with a **Copy** button. The job folder, in `Documents\OT Studio LoRA`, holds `trained.otstudio-lora.json` and a backup of the LoRA weights.

Closing the window does not stop the training at Replicate, which is still charged. **Resume a job...** picks up a job folder later and finishes the download.

The same steps also run from a terminal with `scripts/train_lora.py`, on any computer with Python 3.11 or later. `docs/LORA-TRAINING.md` explains both, including recovery after a lost connection.

## Part 3: Use the LoRA in OT Studio

A LoRA trained on the phone is already in the library: start at step 4.

1. Copy `trained.otstudio-lora.json` to your phone. Or, in step 2, tap **Add manually** and paste the reference from the trainer.
2. In OT Studio, tap the **LoRA library** icon, then **Import file**. Choose the file.
3. Check the name, reference, trigger word, and scale in the review form. Tap **Save**.
4. Tap **Use for pictures**. OT Studio switches Image to the LoRA's own model (FLUX dev LoRA, with its safety checker off, or Qwen Image with a LoRA) and starts the prompt with the trigger word. The library offers the same button on every saved LoRA.
5. Finish the prompt after the trigger word. For example: `MYFACE hiking a mountain trail at sunrise, wide shot`.
6. Tap send. Each image costs about $0.032.
7. Tap **Upscale 2x** on the images you keep to bring them to about 2048 by 2048.

The trigger word also works elsewhere. In Image, a prompt that names a saved trigger word while Image uses another model offers **Switch and generate**. In Chat, a request that names the trigger word or the LoRA's name gives a job card on FLUX dev LoRA with that LoRA.

Your LoRA stays private. OT Studio runs the trained model in your own Replicate account, so the card still says FLUX dev LoRA, and the request goes to your model.

If a LoRA picture fails and the card says the safety checker stopped it, open **API CALL**, tick **disable_safety_checker**, switch it on, and tap **Apply**.

## Tips

- A `lora_scale` of 1 suits most prompts. Raise it toward 1.2 when the face drifts. Lower it toward 0.8 when images look stiff or copy the training photos too closely.
- Put the trigger word near the start of the prompt.
- Ten matching photos at 2K cost about 35 cents once the LoRA exists.
