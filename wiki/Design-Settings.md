<img src="images/otstudio-avatar.jpg" width="72" align="right" alt="">

# Settings

<img src="images/panel-settings-1.png" width="260" alt="Settings showing the provider and key cards"> <img src="images/panel-settings-2.png" width="260" alt="Settings showing the save folder and About cards">

Settings holds what changes rarely: the provider address, the default model, the key, and the save folder. Release builds block screenshots and screen recording on this screen, so a key never lands in a capture.

## Top bar

| Box | What it holds | Why |
|---|---|---|
| Back arrow | Returns to the main screen | Standard Android navigation. The phone's Back gesture does the same. |
| Title | "Settings" | Names the screen |
| Done | Returns to the main screen | A plain exit for users who look for a word rather than an arrow. Done does not save. Each card has its own Save. |

Under the title, one line reminds the user that models and keys stay on this device.

## Mode switch

| Box | What it holds | Why |
|---|---|---|
| Chat, Image, Video | A segmented button | Each mode has its own provider, model, and key, so Settings edits one mode at a time |

## Provider card

| Box | What it holds | Why |
|---|---|---|
| Card title | "OpenRouter, Chat Completions" or "Replicate, Predictions" | Names the protocol this mode speaks |
| Protocol note | Explains that a new provider must speak the same protocol | Changing the address alone cannot add a provider with a different interface. The note prevents a confusing failure. |
| API base URL | The provider address | Allows an OpenRouter-compatible chat service. HTTPS is required. |
| Model ID | The exact model for this mode, with a default reminder for Chat | Sets the model the main screen uses |
| Saved models | A dropdown that copies a saved model into the Model ID field | Saves typing a long ID |

## API key card

| Box | What it holds | Why |
|---|---|---|
| Use text LLM API key | A checkbox on Image and Video | Reuses the chat key when both modes use the same provider address |
| Use image API key | A checkbox on Video, checked by default | Video and images both run on Replicate, so one key covers both. Only one reuse box can be checked. |
| API key | A password field with a show or hide eye, plus a status line | The status line reads "Saved securely. Leave blank to keep." once a key exists. A blank field keeps the stored key, so saving other changes never erases it. The field grays out while a reuse box is checked. |

The card refuses to reuse a key across different provider addresses, because each key works only at its own provider.

## Save row

| Box | What it holds | Why |
|---|---|---|
| Save settings | An orange button, such as "Save image settings" | Commits this mode's provider, model, and key together |
| Remove key | A red text button with a trash icon | Deletes the stored key after a confirmation dialog. The dialog notes that this does not revoke the key at the provider. |
| Status line | "Saved.", "Key removed.", or the reason a save failed | Confirms the result in place |

## Save folder card

| Box | What it holds | Why |
|---|---|---|
| Current folder | The folder name, or "Not chosen yet" | Shows where Save puts files |
| Choose folder | Opens Android's folder picker at the current folder | Changes the folder without starting from the top of storage. Android remembers the permission, so the folder works after a restart. |

## About card

| Box | What it holds | Why |
|---|---|---|
| Tiger avatar | The OT Studio tiger | Brand |
| Version | "OT Studio" and the installed version, such as 1.0.0 | Tells the user which release to compare with the Revision Log |
| Credit | "Created by OrangeTiger" | Names the creator |
| Repository link | `github.com/Orangeriger1998/OTStudio`, in blue | Opens the project page with the wiki and releases |
| Facts | Android version, privacy, costs, and storage limits | Answers the common questions in one place |
