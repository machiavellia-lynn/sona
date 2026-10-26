# Changelog

## 1.0.0 (2026-10-26)

First public release. Beta: built and tested on a Samsung Galaxy S25 (One UI), Android 12+.

### The pill
- Always-on-top pill around the camera cutout, drawn above the status bar with an accessibility overlay.
- Animated mascot eyes: they follow your finger, blink, wander and sleep, and show nine moods (surprise, love, dizzy, wink, tears, laugh, confused, happy, angry). Shaking the phone makes them dizzy.
- The pill changes with what is happening: eyes when idle, an equalizer for music, a running time for a timer or call, a dot for camera / microphone use.
- Spring-based morphing between pill, event pill and card, with a gooey second bubble when two activities run at once.
- Hidden on the lock screen and in landscape.

### Notifications
- Pop-up card with app icon, title and message, shortened with "…".
- Reply and action buttons, conversation merging ("3 new"), a queue, quiet hours and per-app muting.
- Swipe up to dismiss, swipe down to expand, swipe sideways to throw away.
- Optionally hides One UI's own heads-up pop-ups while running.

### Music
- Player card modelled on Samsung's media pop-up: blurred cover background, marquee title, wave seek bar, play / previous / next and the app's custom actions.
- Works with any media app that shows a media notification.

### Time and quick controls
- Timer and stopwatch in one page, with laps; timers survive the app being killed.
- Quick page with flashlight (brightness slider) and ringer buttons.
- Quick Settings tile.

### System events
- Wi-Fi (name and signal), mobile data, Bluetooth (device name and battery), charging (with time to full), low and full battery, power saving, airplane mode and ringer mode, each with its own icon.

### Calls and live activities
- Call indicator with the caller name and a running duration, and end / answer / decline buttons.
- Live activities from apps that post progress notifications.

### App
- Guided setup screen: accessibility service, notification access, "Allow restricted settings" help, optional permissions, and an optional one-line adb command.
- Settings for event types, durations, haptics, quiet hours, privacy dots and more, with live previews.
- English UI.

### Under the hood
- Kotlin, no third-party libraries, no INTERNET permission. About 2% of one CPU core idle with the screen on, near zero with the screen off.
- Signed release build with R8 shrinking.
- MIT licensed.

### Known limits
- Only tested on one phone; other phones and foldables may need tweaks.
- Not yet tested with real incoming calls, live activities from other apps, and Bluetooth battery on all devices.
- No auto-update (the app has no internet access); download new versions from GitHub Releases.
