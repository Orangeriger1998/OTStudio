<img src="images/otstudio-avatar.jpg" width="72" align="right" alt="">

# Procedure 1: Generate a Photo

This procedure makes one image from a text prompt and saves it at about 2K.

**Before you start:** save your Replicate key under Image in Settings. [Getting Started](Getting-Started.md) shows how.

**Cost:** FLUX schnell costs $0.003 per image and FLUX dev costs $0.025. Upscaling adds $0.002.

## Steps

1. On the main screen, tap **Image**.
2. Tap **API CALL**. Under **Saved models**, choose a model:
   - `black-forest-labs/flux-schnell` returns an image in a few seconds and costs the least. Use it to explore ideas.
   - `black-forest-labs/flux-dev` takes longer and renders finer detail. Use it for keepers.
3. Under **Inputs**, check **aspect_ratio** and pick a shape. Choose `3:4` or `9:16` for a portrait, `16:9` for a landscape, or `1:1` for a square.
4. To get several versions at once, check **num_outputs** and enter 2, 3, or 4. Each version costs the full price.
5. Tap **Apply**. The setup card on the main screen now shows your model and inputs.
6. Write the prompt. Describe the subject first, then the setting, the light, and the style. For example: `a red fox curled up in fresh snow, early morning light, soft watercolor`.
7. Tap the orange send button. The card shows Sending, then Working. The preview appears when the card turns Done.
8. Tap the preview or **View** to check the detail.
9. Tap **Upscale 2x** to double the width and height. A new card appears with an image of about 2048 by 2048.
10. On the card you want to keep, tap **Save**. The first save asks for a folder. Pick or create one, such as `Pictures/OT Studio`. Later saves go straight there.

## Tips

- Keep the same seed to compare prompts fairly. Check **seed** in API CALL and enter any whole number. With the seed fixed, each prompt change shows only its own effect.
- The send button grays out while a job runs. Only one image or video job runs at a time, which keeps every charge deliberate.
- A card that says Check dashboard lost contact mid-request. Look for the job on your Replicate dashboard before you send again, so you do not pay twice.
