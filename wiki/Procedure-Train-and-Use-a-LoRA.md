<img src="images/otstudio-avatar.jpg" width="72" align="right" alt="">

# Procedure 4: Train a LoRA and Use It

Training runs on Replicate, started from a Windows computer with the **OT Studio LoRA Trainer**. The phone then uses the finished LoRA through the LoRA Library.

**Before you start:**

- 10 to 20 photos of one face or style ([Procedure 3](Procedure-Create-a-LoRA-Training-Set.md))
- a Windows computer
- your Replicate token
- credit on your Replicate account

**Cost and time:** one training run with 1,000 steps costs about $1.50 to $2 and takes about 20 to 30 minutes.

## Part 1: Train on your computer

1. From the [Releases](https://github.com/Orangeriger1998/OTStudio/releases) page, download `OT-Studio-LoRA-Trainer.exe` and open it. It needs no installation. Windows may say it protected your PC, because the program carries no publisher signature. Choose **More info**, then **Run anyway**.
2. Paste your Replicate token and click **Check token**. The window then shows your account name. The token stays in memory and is never saved.
3. Choose **Choose photos...** and select 10 to 20 photos.
4. Type a **LoRA name**, such as `My face`. The app suggests a **trigger word**, such as `MYFACE`. Keep it, or type another word that means nothing on its own.
5. Tick the box that agrees to the upload and the charge, then choose **Train LoRA**.
6. The trainer creates a private model for the LoRA in your Replicate account, uploads the photos, and starts the training. Its status line counts the minutes.
7. When training finishes, the window shows the **Reference**, with a **Copy** button. The job folder, in `Documents\OT Studio LoRA`, holds `trained.otstudio-lora.json` and a backup of the LoRA weights.

Closing the window does not stop the training at Replicate, which is still charged. **Resume a job...** picks up a job folder later and finishes the download.

The same steps also run from a terminal with `scripts/train_lora.py`, on any computer with Python 3.11 or later. `docs/LORA-TRAINING.md` explains both, including recovery after a lost connection.

## Part 3: Use the LoRA in OT Studio

1. Copy `trained.otstudio-lora.json` to your phone. Or, in step 2, tap **Add manually** and paste the reference from the trainer.
2. In OT Studio, tap the **LoRA library** icon, then **Import file**. Choose the file.
3. Check the name, reference, trigger word, and scale in the review form. Tap **Save**.
4. Tap **Image**, then **API CALL**. Under **Saved models**, choose `black-forest-labs/flux-dev-lora`.
5. In the **Saved FLUX dev LoRAs** card, pick your LoRA. OT Studio fills `lora_weights` and `lora_scale`.
6. Tap **Apply**.
7. Write a prompt that includes the trigger word. For example: `MYFACE hiking a mountain trail at sunrise, wide shot`.
8. Tap send. Each image costs about $0.032.
9. Tap **Upscale 2x** on the images you keep to bring them to about 2048 by 2048.

## Tips

- A `lora_scale` of 1 suits most prompts. Raise it toward 1.2 when the face drifts. Lower it toward 0.8 when images look stiff or copy the training photos too closely.
- Put the trigger word near the start of the prompt.
- Ten matching photos at 2K cost about 35 cents once the LoRA exists.
