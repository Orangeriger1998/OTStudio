<img src="images/otstudio-avatar.jpg" width="72" align="right" alt="">

# Procedure 2: Generate a Photo From a Reference Photo

A reference photo steers a new image toward its layout and colors. FLUX dev starts from the reference and repaints it to match your prompt.

A reference does not lock a face. FLUX keeps the general look but often changes the person. For the same face in every image, train a LoRA with [Procedure 3](Procedure-Create-a-LoRA-Training-Set.md) and [Procedure 4](Procedure-Train-and-Use-a-LoRA.md).

**Before you start:** save your Replicate key under Image in Settings.

**Cost:** $0.025 per image with FLUX dev. The photo upload is free.

## Option A: a photo from your phone

1. Tap **Image**, then **API CALL**.
2. Under **Saved models**, choose `black-forest-labs/flux-dev`. This model has an `image` input for the reference.
3. Scroll to the **image** input card and tap **Choose photo**.
4. Pick a photo in the Android photo picker. OT Studio uploads it to your Replicate account, fills the `image` input, and checks its box. A blue banner confirms the upload.
5. Check **prompt_strength** and set how far the result may move from the reference. At 0.5 the result stays close to the photo. At 0.8, the default, the prompt has more say. Values near 1 mostly ignore the reference.
6. Tap **Apply**.
7. Write a prompt that describes the result you want, not the reference. For example: `the same street at night, neon signs, rain on the pavement`.
8. Tap send. Save the result when the card turns Done.

## Option B: an image you already made

1. On a finished image card, tap **Use as...**.
2. Choose **Reference for the next image**.
3. OT Studio uploads the image and places it in the Image model's `image` input. When the current model lacks that input, OT Studio switches Image to FLUX dev first. A snackbar names the model that received the image.
4. Tap **Open Image** in the snackbar, or tap **Image** at the top.
5. Adjust **prompt_strength** in API CALL if needed, write the prompt, and send.

## Tips

- The reference stays in place for every later image until you change it. Uncheck the **image** box in API CALL to stop using it.
- `black-forest-labs/flux-1.1-pro` has a similar input called `image_prompt`. It steers the style of a new image rather than repainting the reference.
- Uploaded photos live in your Replicate account's file storage, not on a public page.
