# Sona

A small companion that lives around your Android camera cutout: an always-on-top pill with animated eyes that grows into notification pop-ups, a music player, a timer / stopwatch, call and Wi-Fi / Bluetooth / charging events.

> **Beta.** Built and tested on a Samsung Galaxy S25 (One UI). Other phones may need tweaks, and a phone without a top-centre camera hole is not supported. Bug reports are welcome.

Requires Android 12 (API 31) or newer. No internet permission: see [PRIVACY.md](PRIVACY.md).

## Install

1. Download `Sona-1.0.0.apk` from the Releases page and open it. Allow "install unknown apps" for your browser or file manager if asked. Play Protect may warn about an unknown app; Sona is not distributed through the Play Store.
2. Open **Sona**. It shows a checklist; follow it:
   - **Accessibility service**: Settings → Accessibility → Installed apps → Sona → On. This draws the overlay.
   - **Notification access**: turn Sona on. This feeds pop-ups, media and calls.
3. Optional but recommended: Wi-Fi name (location permission), Bluetooth names, and *Unrestricted* battery so the system does not stop Sona.

### "Restricted setting" / greyed-out switch

On Android 13+ apps installed from a file cannot enable these switches until you allow it: Settings → Apps → Sona → ⋮ (top right) → **Allow restricted settings**, then try again. The app has a shortcut to App info.

### Optional: one `adb` command

With USB debugging on and the phone connected to a computer:

```
adb shell pm grant app.sona.notch android.permission.WRITE_SECURE_SETTINGS
```

This lets Sona switch itself on/off from its Start button, use the Quick Settings tile, and (optionally) hide One UI's own heads-up pop-ups while it is running. Everything else works without it.

If Sona is stopped and heads-up pop-ups ever stay hidden, run:

```
adb shell settings put global heads_up_notifications_enabled 1
```

## Build

JDK 17 and the Android SDK are required.

```
./gradlew assembleDebug
```

For a signed release create `keystore.properties` (gitignored) next to `settings.gradle.kts`:

```
storeFile=/path/to/key.jks
storePassword=...
keyAlias=...
keyPassword=...
```

then `./gradlew assembleRelease`. Keep your keystore safe: updates must be signed with the same key.

## License

MIT, see [LICENSE](LICENSE) and [NOTICE](NOTICE).
