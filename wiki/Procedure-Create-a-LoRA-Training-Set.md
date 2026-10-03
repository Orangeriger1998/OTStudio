<img src="images/otstudio-avatar.jpg" width="72" align="right" alt="">

# Procedure 3: Create a Photo Set for LoRA Training

A LoRA learns one subject, such as a face or an art style, from a small set of photos. The quality of the set decides the quality of every later image, so this procedure matters more than the training itself.

**Consent:** train only on photos of yourself, of people who have agreed, or of characters you invented.

## What a good set looks like

The training script accepts 1 to 20 photos in JPG, PNG, or WebP. Replicate recommends at least 10. Each photo may be up to 20 MB, and the whole set must fit in 95 MB.

Aim for 12 to 20 photos of the same subject with plenty of variety around it:

- different angles, from straight on to profile
- different light, indoors and outdoors
- different backgrounds and clothing
- a few close-ups of the face and a few half-body shots

Every photo should be sharp, with the face clearly visible. Leave out photos with other people or heavy filters. Sunglasses hide the eyes, so skip those too. Variety teaches the LoRA what stays the same, and what stays the same is the subject.

## Option A: real photos

1. Pick 12 to 20 photos that follow the guidance above.
2. Copy them into one folder on your computer, such as `my-photos`. Subfolders are ignored.

## Option B: an invented character made in OT Studio

1. Tap **Image**, then **API CALL**, and choose `black-forest-labs/flux-dev`.
2. Write one detailed description of the character and keep it word for word in every prompt. For example: `a woman in her thirties, short silver hair, green eyes, small scar on the left eyebrow, freckles`.
3. Vary only the rest of the prompt: the pose, the angle, the setting, the light, and the clothing.
4. Generate until you have more than 20 candidates. Each costs $0.025.
5. To pull later images closer to your favorite, tap **Use as...** on it and choose **Reference for the next image**. Set **prompt_strength** around 0.6 so the pose can still change.
6. Keep the 12 to 20 images where the character looks most alike. Tap **Save** on each one.
7. Copy the saved images from the phone's save folder to one folder on your computer.

## Captions (optional)

The trainer writes its own captions. To guide it, add a text file beside any photo with the same name, such as `photo1.jpg` and `photo1.txt`. Describe what changes in that photo, such as the setting and the clothing, and leave the subject's fixed features out.

Next: [Procedure 4: Train a LoRA and Use It](Procedure-Train-and-Use-a-LoRA.md).
