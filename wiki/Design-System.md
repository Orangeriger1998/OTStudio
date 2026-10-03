<img src="images/otstudio-avatar.jpg" width="72" align="right" alt="">

# Design System

OT Studio uses Google's Material 3 components on a pure black canvas. This page records the shared choices that every screen follows. The screen pages build on it.

## Why Material 3 components

Version 1.0 replaced the stock Android widgets of the 0.2 preview with Material Components 1.13. The old widgets dated from 2014 and made the app look like an early Android phone. Material 3 gives current buttons, outlined text fields, cards, chips, segmented buttons, and dialogs.

The screens stay in Kotlin views rather than Jetpack Compose. That choice left the tested provider and storage code untouched during the redesign, along with every safety check. A Compose rewrite would have rebuilt every screen and reopened bugs that live testing had already closed. Material Components still supports Android 5 and later, so the redesign added no new device limit. OT Studio keeps its own floor of Android 10.

## Color

The palette comes straight from the OT Studio logo. The orange and blue were sampled from the logo artwork itself.

| Role | Color | Used for |
|---|---|---|
| Canvas | `#000000` black | Screen backgrounds and both system bars |
| Primary | `#F7781A` tiger orange | The OT Studio title, the send button, Save buttons, checkboxes, progress bars |
| On primary | `#1F0E00` | Text and icons on orange |
| Secondary | `#739FC6` logo blue | Links, notice banners, the selected mode, and the API CALL button |
| Surface | `#161616` | Cards |
| Raised surface | `#202020` | Reply bubbles and cards inside dialogs |
| Text | `#F2F2F2` | Main text |
| Muted text | `#B8B8B8` | Helper lines and captions, including model names |
| Error | `#FFB4AB` on `#5C1A14` | Failed jobs and error banners |
| Success | `#8CD5A5` | The Done status label |

Black was a requirement for the brand. It also saves battery on phones with OLED screens. Generated images stand out against it, with no competing colors around them. Orange marks the one action that matters on a screen. Blue marks selection and information, so the two never fight for attention.

## Type

Text uses the Material 3 type scale in the phone's system font. Screen titles use Title Large. Card headings use Title Medium in bold. Body text uses Body Medium, with Body Large for prompts and replies. Small helper lines use Body Small. Status labels use Label Small. Every size follows the phone's font-size setting, so readers who enlarge text get larger text in OT Studio too.

## Shape

Cards have 20 dp corners. Text fields use 14 dp corners, while the message bar is a full pill at 26 dp. Chat bubbles round three corners and keep one corner tight, pointing toward the speaker. Images in the feed have 16 dp corners. Buttons use the Material 3 pill shape.

## Icons

All icons come from Google's Material Symbols set, outlined style, under the Apache 2.0 license. Each icon-only button carries a spoken label for screen readers, such as Settings or Help. A long press on any of them shows a tooltip.

## Spacing and touch

Screens keep a 16 dp margin on each side. Cards stack with 12 dp between them. Every button meets the 48 dp minimum touch size. The app draws behind the status bar and the navigation bar on every Android version, then pads each screen once for those bars and for the keyboard.

## Feedback

Short confirmations, such as a finished save, appear in a snackbar at the bottom of the screen. A snackbar can carry one action, such as Change folder. Choices that cost money or delete something open a dialog instead. Notices that a screen reader should announce, like an upload result, use a live region.
