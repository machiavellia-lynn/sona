package app.sona.notch

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Home screen: shows whether Sona is running and walks a new user through the few steps Android requires. */
class MainActivity : Activity() {

    private val service by lazy { ComponentName(this, NotchAccessibilityService::class.java) }
    private val listener by lazy { ComponentName(this, NotchListener::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        build()
    }

    override fun onResume() {
        super.onResume()
        if (!isServiceEnabled()) HeadsUp.set(this, false)
        build()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        build()
    }

    /** Rebuilt on resume so every row always reflects the real state, e.g. right after returning from a settings page. */
    private fun build() {
        val accessibilityOn = isServiceEnabled()
        val listenerOn = getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listener)
        val doneCount = listOf(accessibilityOn, listenerOn).count { it }
        val running = accessibilityOn

        screen("Sona", "A little companion for your camera cutout") {
            addView(statusCard(running, doneCount), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16f) })
            if (canWriteSecure()) {
                addView(
                    bigButton(if (running) "Stop Sona" else "Start Sona", if (running) Ui.RED else Ui.GREEN) {
                        setServiceEnabled(!running)
                    },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12f) },
                )
            } else if (!accessibilityOn) {
                addView(
                    bigButton("Open accessibility settings", Ui.BLUE) { openAccessibility() },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12f) },
                )
            }

            caption("Required")
            group(
                status("Accessibility service", accessibilityOn, "Find Sona under Installed apps") { openAccessibility() },
                status("Notification access", listenerOn, "Lets Sona show messages and music") { openListenerSettings() },
            )
            if (!accessibilityOn || !listenerOn) {
                note("A switch greyed out or a \"restricted setting\" message? Open App info, tap ⋮ (top right), choose \"Allow restricted settings\", then try again.")
                group(linkRow("Open App info") { openAppInfo() })
            }

            caption("Recommended")
            group(
                status("Wi-Fi network name", granted(Manifest.permission.ACCESS_FINE_LOCATION), "Uses location only to read the name") {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
                },
                status("Bluetooth device names", granted(Manifest.permission.BLUETOOTH_CONNECT), "Shows which earbuds connected") {
                    requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2)
                },
                status("Unrestricted battery", isBatteryUnrestricted(), "Stops the system from putting Sona to sleep") {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                },
            )

            caption("More")
            group(
                linkRow("Settings") { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) },
                linkRow("Test call indicator", "On / off") { toggleTestCall() },
            )

            caption("Advanced · optional")
            note(
                "One command from a computer (with USB debugging) unlocks the Start button, the Quick Settings tile and " +
                    "hiding One UI's own pop-ups. Sona works without it.",
            )
            note(adbCommand(), monospace = true)
            group(
                status("Advanced features", canWriteSecure(), null) { copyAdbCommand() },
                linkRow("Copy command") { copyAdbCommand() },
            )

            note("Sona ${versionName()} · MIT license\nNo internet permission: nothing leaves your phone.")
        }
    }

    private fun statusCard(running: Boolean, done: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(18f), px(16f), px(18f), px(16f))
        background = GradientDrawable().apply {
            setColor(Ui.SURFACE)
            cornerRadius = px(16f).toFloat()
        }
        addView(
            TextView(context).apply {
                text = if (running) "Sona is running" else "Finish setup"
                textSize = 20f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(if (running) Ui.GREEN else Color.WHITE)
            },
        )
        addView(
            TextView(context).apply {
                text = if (running) {
                    "Tap the pill on your camera, or wait for something to happen."
                } else {
                    "$done of 2 required steps done"
                }
                textSize = 14f
                setTextColor(Ui.SECONDARY)
                setPadding(0, px(2f), 0, 0)
            },
        )
    }

    private fun px(dp: Float) = (dp * resources.displayMetrics.density).toInt()

    private fun status(label: String, ok: Boolean, hint: String?, onClick: () -> Unit) =
        linkRow(label, if (ok) "On" else "Set up", if (ok) Ui.GREEN else Ui.BLUE, hint) { if (!ok) onClick() }

    private fun granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun canWriteSecure() = granted(Manifest.permission.WRITE_SECURE_SETTINGS)

    private fun isBatteryUnrestricted() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun isServiceEnabled(): Boolean = enabledServices().contains(service.flattenToString())

    private fun enabledServices(): List<String> =
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').filter { it.isNotBlank() }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun adbCommand() = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"

    private fun copyAdbCommand() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb", adbCommand()))
        Toast.makeText(this, "Command copied", Toast.LENGTH_SHORT).show()
    }

    private fun openAccessibility() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    /** Opens the page for Sona's own notification-access switch when the system offers one, else the full list. */
    private fun openListenerSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString()),
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun openAppInfo() = startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
    )

    /** Only possible with the optional adb grant; otherwise the user switches the service in system settings. */
    private fun setServiceEnabled(enabled: Boolean) {
        if (!canWriteSecure()) {
            openAccessibility()
            return
        }
        val name = service.flattenToString()
        val updated = enabledServices().filter { it != name }.let { if (enabled) it + name else it }
        Settings.Secure.putString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            updated.joinToString(":"),
        )
        if (enabled) Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        build()
    }

    /** Posts/removes a fake call notification so the call indicator can be tried without calling. */
    private fun toggleTestCall() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.activeNotifications.any { it.id == TEST_CALL_ID }) {
            nm.cancel(TEST_CALL_ID)
            return
        }
        if (!granted(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
            return
        }
        nm.createNotificationChannel(NotificationChannel("call_test", "Test call", NotificationManager.IMPORTANCE_LOW))
        val end = PendingIntent.getBroadcast(
            this, 0, Intent(this, CallTestReceiver::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            TEST_CALL_ID,
            Notification.Builder(this, "call_test")
                .setSmallIcon(R.drawable.ic_phone)
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setUsesChronometer(true)
                .setWhen(System.currentTimeMillis())
                .setContentTitle("Rama")
                .setContentText("Test call")
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_phone), "End", end).build())
                .build(),
        )
    }

    companion object {
        const val TEST_CALL_ID = 4242
    }
}
