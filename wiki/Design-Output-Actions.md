<img src="images/otstudio-avatar.jpg" width="72" align="right" alt="">

# Output Actions

Every image and video card carries two rows of buttons. The first row acts on the finished file. The second row acts on the job and its prompt. Each row scrolls sideways when the buttons do not fit.

## File actions

| Button | What it does | Why |
|---|---|---|
| View (images) | Opens the image full size in a dialog | Checks detail before saving. Tapping the preview does the same. |
| Play (videos) | Plays the clip in a dialog with play controls | Checks a clip without leaving the app |
| Save | Copies the file into the remembered save folder | The first Save asks for a folder. Later saves go straight there, so nobody browses from the top of storage each time. A snackbar confirms the folder and offers Change. |
| Share | Opens Android's share sheet with the file | Sends a result to another app, such as email or a chat app, without saving a copy first |
| Use as... (images) | Uploads the image and places it in a model input of your choice | Chains work together. One image can become the reference for the next image, or a starting or ending frame for a video. |
| Upscale 2x (images) | Doubles the width and height with Real-ESRGAN | FLUX images come out at about 1024 by 1024. Upscaling reaches about 2048 by 2048 for a fifth of a cent. The result appears as a new card. |

<img src="images/action-use-as.png" width="260" alt="The Use this image as dialog with its three roles">

**Use as...** offers three roles:

- **Reference for the next image** fills the `image` input of the Image model. When the current model has no such input, OT Studio switches Image to FLUX dev, which does.
- **Starting frame for a video** fills `first_frame`, or `image` on WAN 2.2. When the current Video model cannot start from an image, OT Studio switches to WAN 2.7 image-to-video. A starting frame replaces any clip set to continue, because the model accepts only one of the two.
- **Ending frame for a video** fills `last_frame`, or `last_image` on WAN 2.2, and uses the same model switch.

The upload goes to the user's own Replicate account through Replicate's Files API. A snackbar names the model that received the photo and offers to open that mode.

## Job actions

| Button | When it shows | What it does | Why |
|---|---|---|---|
| Use prompt | Always | Copies the prompt into the message bar | Makes a variation without retyping |
| Refresh | While Working | Asks the provider for the job's state now | The app already checks every six seconds. This button is for the impatient. |
| Cancel | While Working | Asks the provider to stop the job | Stops paying for a job that is clearly wrong |
| Stop tracking | While Working | Releases a job that cannot be refreshed or canceled | Without it, one stuck job would block every new image and video. A dialog warns that the provider job may still run and charge. |
| Download again | After a failed or skipped download | Retries the download without generating again | Avoids paying twice for a finished result. Replicate keeps API results for only about an hour, so retry soon. |
| Skip download | While Saving | Gives up on the download | Frees the app when a connection is too slow |

OT Studio never retries a paid request on its own. When the outcome of a request is unknown, the card says Check dashboard, and the decision stays with the user.
