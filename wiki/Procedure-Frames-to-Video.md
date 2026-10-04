<img src="images/otstudio-avatar.jpg" width="72" align="right" alt="">

# Procedure 5: Turn Frames Into Video

An image-to-video model animates a still image. Give it a starting frame and it moves the scene forward from there. With an ending frame as well, it travels from one picture to the other. That second form is the key to joining clips smoothly.

**Before you start:** save your Replicate key under Image in Settings. Video reuses it unless you set a separate key.

**Cost:** WAN 2.7 image-to-video costs $0.10 per second at 720p and $0.15 at 1080p, so a five-second 720p clip costs 50 cents. WAN 2.2 fast costs 5 cents per clip at 480p.

**Time:** a five-second WAN 2.7 clip at 720p can take ten minutes or more. Keep OT Studio open to watch it finish. If you close the app, the job keeps running at Replicate, and OT Studio picks it up again by its job ID when you return.

## Steps

1. Make or choose the starting image. It can come from OT Studio or from your phone's photos. Match its shape to the video you want: `16:9` for landscape or `9:16` for a phone-shaped video.
2. Set the starting frame.
   - For an image made in OT Studio, tap **Use as...** on its card and choose **Starting frame for a video**.
   - For a photo on your phone, tap **Video**, then the photo button at the left of the message bar. Pick the photo and choose **Starting frame for a video**. API CALL offers the same through **Choose photo** under **first_frame**.

   OT Studio uploads the image and fills `first_frame`. When Video was on a text-only model, OT Studio switches it to WAN 2.7 image-to-video.
3. (Optional) Set an ending frame. Tap **Use as...** on a second image and choose **Ending frame for a video**. This fills `last_frame`. An ending frame needs a starting frame.
4. Tap **Video**, then **API CALL**. Check **resolution** and choose `720p` to save money or `1080p` for detail. Check **duration** and enter 2 to 15 seconds. Five seconds keeps the motion steady.
5. Tap **Apply**.
6. Write a prompt about motion, not appearance, because the frames already show what things look like. Describe what moves and how the camera behaves. For example: `she turns from the window and smiles, slow push-in, soft daylight`.
7. Tap send. The card shows Working while Replicate renders the clip.
8. When the card turns Done, tap **Play** to watch it. Tap **Save** to keep it.

## Make a longer video

WAN 2.7 clips run up to 15 seconds, so a minute takes several clips. OT Studio cannot pull a frame out of a finished video, so plan the joins ahead with keyframes.

1. Make one image for each moment in the story: keyframe A, B, C, D, and so on.
2. Clip 1 runs from A to B: starting frame A, ending frame B.
3. Clip 2 runs from B to C. Use **Use as...** on B as the new starting frame, then on C as the new ending frame. Each **Use as...** replaces the old frame.
4. Repeat until the story is done. Each clip ends on exactly the frame where the next clip starts, so the joins line up.
5. Save every clip, then join them in a video editor such as CapCut on the phone or any desktop editor. Add music and titles there.

At 720p, a minute of WAN 2.7 video costs about $6. Twelve five-second clips need thirteen keyframes.

## Tips

- WAN 2.7 adds generated sound by default. Join clips in an editor that lets you mute or replace the audio.
- Keep the camera wording consistent across clips, so the motion matches at each join.
- For the same face in every clip, make the keyframes with a LoRA. See [Procedure 6](Procedure-Consistent-Face-Video.md).
