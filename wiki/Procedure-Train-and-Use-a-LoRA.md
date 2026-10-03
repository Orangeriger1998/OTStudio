<img src="images/otstudio-avatar.jpg" width="72" align="right" alt="">

# Procedure 4: Train a LoRA and Use It

Training runs on Replicate, started from a computer with the `train_lora.py` script in this repository's `scripts` folder. The phone then uses the finished LoRA through the LoRA Library.

**Before you start:**

- a photo set in one folder ([Procedure 3](Procedure-Create-a-LoRA-Training-Set.md))
- a computer with Python 3.11 or later
- your Replicate token
- credit on your Replicate account

**Cost and time:** one training run with the default 1000 steps costs about $1.50 to $2. By Replicate's own estimate, it takes about 20 minutes.

## Part 1: Create a destination model on Replicate

The trained LoRA needs a home in your Replicate account.

1. Open [replicate.com/create](https://replicate.com/create).
2. Name the model, for example `my-face`, and set it to **Private**.
3. Note its full ID, such as `yourname/my-face`.

## Part 2: Train on your computer

1. Download `scripts/train_lora.py` from this repository. Open a terminal in the folder that holds it.
2. Prepare the job. This step runs only on your computer and costs nothing:

   ```
   python train_lora.py prepare --photos ./my-photos --job ./training-jobs/my-face --name "My face" --destination yourname/my-face --trigger MYFACE
   ```

   The trigger word must start with a letter and use only letters, digits, or underscores. Pick a word that means nothing on its own, such as `MYFACE`.
3. Open `training-jobs/my-face/photos.zip` and check that every photo belongs.
4. Start the paid training. The two flags confirm that you accept the upload and the charge:

   ```
   python train_lora.py submit --job ./training-jobs/my-face --upload-photos --accept-charge
   ```

   The script asks for your Replicate token with the input hidden. It never saves the token.
5. Check progress every few minutes:

   ```
   python train_lora.py status --job ./training-jobs/my-face
   ```

6. When status reports success, the job folder holds `trained.otstudio-lora.json` and a backup copy of the LoRA weights. Keep the whole folder.

If the script loses contact during submit, it marks the job uncertain and refuses to submit again. That rule protects you from paying twice. The full recovery steps are in `docs/LORA-TRAINING.md`.

## Part 3: Use the LoRA in OT Studio

1. Copy `trained.otstudio-lora.json` to your phone.
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
