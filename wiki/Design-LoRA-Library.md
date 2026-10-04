<img src="images/otstudio-avatar.jpg" width="72" align="right" alt="">

# LoRA Library

<img src="images/panel-lora-library.png" width="260" alt="LoRA library dialog">

A LoRA is a small add-on model trained on your own photos. It teaches FLUX dev one subject, such as a face or an art style. A trigger word in the prompt calls it up. The LoRA Library keeps the LoRAs you have trained so API CALL can apply them with one tap. The library icon on the main screen's top bar opens it.

The library stores each LoRA's provider reference, not its weights. The weights stay on Replicate and on your computer, so the phone never holds a large file.

## Library dialog

| Box | What it holds | Why |
|---|---|---|
| Title | "LoRA library" with a count | Shows how many LoRAs are saved |
| Explanation | How training and importing work | LoRAs train on a computer, which is not obvious from a phone |
| LoRA cards | Name, trigger word, default scale, and the provider reference | Enough to tell LoRAs apart and to remember each trigger word |
| Import file | Opens the phone's file picker for a `.otstudio-lora.json` file | The training script writes this file, so importing avoids typing a long reference |
| Add manually | Opens an empty form | For a LoRA trained elsewhere on Replicate |
| Use for pictures | A button on each saved LoRA, and the choice offered right after a save | Switches Image to FLUX dev LoRA with this LoRA and the safety checker off, and starts the prompt with its trigger word |
| Close | Closes the dialog | Plain exit |

## Review form

Both Import file and Add manually open this form, so every entry is checked by a person before it is saved.

| Box | What it holds | Why |
|---|---|---|
| LoRA name | Up to 80 characters | The name shown in API CALL |
| Reference | `owner/model`, `owner/model/version`, or an HTTPS weights URL | Where Replicate finds the weights. A pinned version keeps results repeatable. |
| Trigger word | The word that calls up the LoRA | Shown again in API CALL as a reminder |
| Default scale | -1 to 3, usually 1 | How strongly the LoRA shapes the image. A comma works as the decimal point. |

Saving a reference that already exists updates its name, trigger, and scale instead of adding a duplicate. The library accepts FLUX dev LoRAs only. WAN video models cannot load them, and the [consistent face procedure](Procedure-Consistent-Face-Video.md) explains how to carry a LoRA face into video anyway.
