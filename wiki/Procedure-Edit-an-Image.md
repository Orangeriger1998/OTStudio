<img src="images/otstudio-avatar.jpg" width="72" align="right" alt="">

# Procedure 9: Edit an Image

This procedure changes one thing in a finished picture, such as a color or an object, and keeps the rest.

**Before you start:** save your Replicate key under Image in Settings, and have a finished picture in the Image feed.

**Cost:** about 1 to 4 cents per edit, depending on the model. FLUX Kontext dev, the first choice, costs about 2.5 cents.

## Steps

1. On the main screen, tap **Image** and find the picture.
2. Tap **Edit...** on its card.
3. Under **What should change?**, write one short, direct instruction, such as `change the white window frame to dark green`.
4. Keep **FLUX Kontext dev** under **Editing model**, or choose another. The cost line shows the price of one edit.
5. Tap **Edit**. The app uploads the picture to your Replicate account and starts the edit.
6. The edited picture appears as a new card that begins with **Edit of:** and the original's prompt. Save or share it as usual.

## Use reference photos

Seedream 4.5, Qwen Image Edit Plus and 2511, FLUX.2 dev and klein, and P-Image Edit take more than one picture. The picture being edited is image 1, and reference photos from the phone become image 2, image 3, and so on.

1. Tap **Edit...** on the picture and choose one of those models.
2. Tap **Add a reference photo** and pick a photo, such as an outfit, a logo, or a place. Repeat for more, up to the model's limit: 14 pictures in all for Seedream 4.5, 5 for FLUX.2, and 3 for the others.
3. Name the pictures in the instruction, for example: `Dress her in the outfit from image 2. Keep her face and the background the same.`
4. Tap **Edit**.

Say what to take from each reference photo and what to leave. In a test, `Put the tiger logo from image 2 as a small patch on her denim jacket` also borrowed the tiger's glasses and tie; adding `only the logo, nothing else from image 2` keeps the rest out.

In API CALL, these models show each attached picture as Image 1, Image 2, and so on, with Remove on each and **Add another photo**.

## Change the shape of a picture

Editing keeps a picture's own shape. To make a picture wider, taller, or square without changing what is in it, expand it instead.

1. On the picture's card, tap **Expand...**.
2. Under **New shape**, choose a shape, such as **Wide 16:9**.
3. If you like, say what the new area should show, such as `more of the beach and the sky`.
4. Tap **Expand**. The result, about 4 cents, appears as a new card at the original's full resolution. The original picture sits unchanged in its middle.

In Chat, choose the picture with **Talk about this** or the photo button, then write `make it wide` or `make it 9:16`. The job card lets you pick the exact shape before Generate.

## Edit a photo from your phone

1. On the main screen, tap **Image**, then the photo button at the left of the message bar.
2. Pick the photo. It appears in the Image feed as **Your photo**.
3. Choose **Edit it**, then follow steps 3 to 6 above.

In Chat, the same photo button works with plain words. Pick the photo, then send an instruction such as `make it black and white`. The job card names the photo, and Generate starts the edit.

## Tips

- One change per edit works best. For two changes, edit the result again.
- Say what must stay, too. In a test on a selfie, `Change her jacket to a red sweater. Keep her face and the background the same.` changed only the clothing.
- An edit that comes back unchanged usually means the editing model declined the request. The provider still charges for it.
- In a test with the same picture and instruction, FLUX Kontext dev changed only the window frame. Qwen Image Edit Plus and P-Image Edit also darkened the sill below it.
- Repeated edits can slowly change a face. For a face that must stay true, edit the original picture again rather than an edit of an edit.
- Every editing model in the list starts with its safety checker off. Replicate's usage policy still applies.
- In Chat, a request such as `make the last picture black and white` makes the same kind of edit through a job card.
