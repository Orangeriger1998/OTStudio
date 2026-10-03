<img src="images/otstudio-avatar.jpg" width="72" align="right" alt="">

# Procedure 6: Keep a Face Consistent Through a Video

This procedure carries one person's face through a whole video. It combines a FLUX LoRA with WAN 2.7 image-to-video.

## How it works

WAN video models cannot load a FLUX LoRA. The two use different model families, so a LoRA trained for FLUX images has no effect on WAN. The face stays consistent another way: every clip starts on a frame that the LoRA made, and ends on one too. The video model only fills in the motion between two frames that already show the right face. Shorter clips leave less room for the face to drift, so five seconds works better than fifteen.

**Before you start:**

- a trained LoRA in the LoRA Library ([Procedure 3](Procedure-Create-a-LoRA-Training-Set.md) and [Procedure 4](Procedure-Train-and-Use-a-LoRA.md))
- the frames-to-video steps ([Procedure 5](Procedure-Frames-to-Video.md))

**Cost for one minute at 720p:** about $6 for twelve WAN 2.7 clips, plus about 42 cents for thirteen LoRA keyframes.

## Part 1: Make the keyframes with the LoRA

1. Write the story as a short list of moments, one per clip boundary. For example: at the window, turning, walking to the door, opening it.
2. Tap **Image**, then **API CALL**. Choose `black-forest-labs/flux-dev-lora` and pick your LoRA under **Saved FLUX dev LoRAs**.
3. Check **aspect_ratio** and choose the video's shape, such as `16:9`. Every keyframe must share it.
4. Check **seed** and enter one number for the whole set. A fixed seed keeps lighting and style steadier from frame to frame.
5. Tap **Apply**.
6. Generate one image per moment. Start each prompt with the trigger word, then keep the words for clothing, hair, setting, and light the same. Change only the pose and the action. For example: `MYFACE standing at a sunlit window, blue sweater, living room, morning light, looking outside`.
7. Regenerate any frame where the face looks off. Keyframes cost about three cents each, while a clip costs fifty, so fix faces here.

## Part 2: Turn each pair of keyframes into a clip

For each clip, in story order:

1. On the keyframe that starts the clip, tap **Use as...** and choose **Starting frame for a video**.
2. On the next keyframe, tap **Use as...** and choose **Ending frame for a video**.
3. Tap **Video**. The setup card should show `wan-video/wan-2.7-i2v`. In API CALL, confirm **resolution** and set **duration** to 5. Tap **Apply**.
4. Write a motion prompt for this clip, such as `she turns away from the window and walks toward the camera, steady handheld shot`.
5. Tap send and wait for Done. Tap **Play** to review, then **Save**.
6. Move to the next pair. The ending keyframe of this clip becomes the starting keyframe of the next.

Each **Use as...** replaces the frame it targets. Before each send, glance at the setup card's summary to confirm that both frames changed.

## Part 3: Finish in a video editor

1. Copy the saved clips from the phone's save folder into a video editor.
2. Place them in order. The joins line up because each clip ends on the frame where the next begins.
3. Trim the first and last few frames of each clip if a join stutters.
4. Mute or replace WAN's generated audio, and add music and titles.

OT Studio stops at the clips. The editor handles joining and music, and any color matching happens there too.
