# Privacy

Sona runs entirely on your phone.

- **No internet permission.** The app cannot send anything off the device, and it has no analytics, ads or crash reporting.
- **Notifications** are read through Android's notification access only to draw the pop-ups, media card and call indicator. Their content is held in memory while shown and is never written to disk or shared.
- **Accessibility service** is used only to draw the overlay around the camera cutout. Sona does not read screen content (`canRetrieveWindowContent` is off) and listens to no accessibility events.
- **Location permission** (optional) is requested only because Android requires it to read the current Wi-Fi network name.
- **Bluetooth permission** (optional) is used to show the name and battery of a connecting device.
- **Stored on the device:** your settings, muted apps and running timers (SharedPreferences, app-private).

Remove all of it any time by uninstalling the app.
