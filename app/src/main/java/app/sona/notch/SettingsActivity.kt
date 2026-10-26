package app.sona.notch

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.graphics.drawable.Icon
import android.graphics.Color
import android.os.Bundle

/** Settings: which pop-ups appear, privacy, live activities, haptics and how long pop-ups stay. */
class SettingsActivity : Activity() {

    private var previewIndex = 0
    private var moodIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        screen("Settings") {
            caption("Pop-ups")
            group(
                toggleRow("Wi-Fi", Prefs.EVENT_WIFI),
                toggleRow("Mobile data", Prefs.EVENT_CELL),
                toggleRow("Bluetooth", Prefs.EVENT_BLUETOOTH),
                toggleRow("Airplane mode", Prefs.EVENT_AIRPLANE),
                toggleRow("Silent / vibrate / ring", Prefs.EVENT_RINGER),
                toggleRow("Charging", Prefs.EVENT_CHARGING),
                toggleRow("Battery alerts", Prefs.EVENT_BATTERY, "Low, full and power saving"),
                toggleRow("App notifications", Prefs.APP_POPUPS),
            )

            caption("Privacy")
            group(
                toggleRow("Hide message previews", Prefs.HIDE_CONTENT, "Show only \"New notification\""),
                toggleRow("Respect Do Not Disturb", Prefs.RESPECT_DND, "No app pop-ups while it is on"),
                toggleRow("Replace system pop-ups", Prefs.REPLACE_HEADS_UP, "Hide One UI's own heads-up while the notch shows"),
                toggleRow("Hide in landscape", Prefs.HIDE_FULLSCREEN, "For video and games"),
                toggleRow("Quiet hours", Prefs.QUIET_ON, "No app or system pop-ups; calls and timers still show"),
                sliderRow("Quiet from", Prefs.QUIET_START, 0, 23, 22, "h"),
                sliderRow("Quiet until", Prefs.QUIET_END, 0, 23, 7, "h"),
                toggleRow("Camera & microphone dots", Prefs.PRIVACY_DOTS, "Orange dot for the mic, green for the camera"),
                linkRow("Muted apps") { startActivity(Intent(this@SettingsActivity, AppsActivity::class.java)) },
            )

            caption("Live activities")
            group(
                toggleRow("Call indicator", Prefs.CALL_INDICATOR),
                toggleRow("Timers, navigation and recordings", Prefs.LIVE_ACTIVITIES, "From other apps' ongoing notifications"),
            )

            caption("Feel")
            group(
                toggleRow("Haptic feedback", Prefs.HAPTICS),
                sliderRow("Event pop-up stays", Prefs.EVENT_SECONDS, 2, 10, 5),
                sliderRow("Notification pop-up stays", Prefs.BANNER_SECONDS, 3, 15, 7),
            )

            caption("Mascot")
            group(
                toggleRow("Clock and battery when idle", Prefs.IDLE_CLOCK, "Instead of the eyes"),
                linkRow("Preview mascot moods", "Tap to cycle") { nextMood() },
            )

            caption("Preview")
            group(
                linkRow("Preview pop-ups", "Tap to cycle") { previewNext() },
                linkRow("Send test notification", "Reply + actions") { sendTestNotification() },
            )
        }
    }

    /** A real, high-importance notification with Reply and Mark as read, to try the whole path end to end. */
    private fun sendTestNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(NotchListener.TEST_CHANNEL, "Test", NotificationManager.IMPORTANCE_HIGH))
        val echo = Intent(this, ReplyEchoReceiver::class.java)
        val replyPi = PendingIntent.getBroadcast(this, 1, echo, PendingIntent.FLAG_MUTABLE)
        val readPi = PendingIntent.getBroadcast(this, 2, echo, PendingIntent.FLAG_IMMUTABLE)
        val remote = RemoteInput.Builder(ReplyEchoReceiver.KEY_REPLY).setLabel("Reply").build()
        val reply = Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_phone), "Reply", replyPi)
            .addRemoteInput(remote).build()
        val read = Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_phone), "Mark as read", readPi).build()
        nm.notify(
            ReplyEchoReceiver.TEST_ID,
            Notification.Builder(this, NotchListener.TEST_CHANNEL)
                .setSmallIcon(R.drawable.ic_bolt)
                .setContentTitle("Rama")
                .setContentText("Are we still on for tonight? I am already outside and the traffic is terrible.")
                .addAction(reply)
                .addAction(read)
                .build(),
        )
    }

    /** Plays the next mood on the mascot (needs the notch running and the pill idle). */
    private fun nextMood() {
        val all = EyesView.Reaction.values()
        NotchBus.onReaction?.invoke(all[moodIndex % all.size])
        moodIndex++
    }

    /** Fires the next sample pop-up on the notch (needs the notch running). */
    private fun previewNext() {
        val white = Color.WHITE
        val samples = listOf(
            EventItem(0, white, "Home Wi-Fi", EventItem.KIND_WIFI, 4),
            EventItem(0, white, "Disconnected", EventItem.KIND_WIFI, on = false),
            EventItem(0, white, "Operator", EventItem.KIND_CELL, 3),
            EventItem(0, white, "Disconnected", EventItem.KIND_CELL, on = false),
            EventItem(R.drawable.ic_bluetooth, 0xFF4DA3FF.toInt(), "Earbuds"),
            EventItem(R.drawable.ic_bluetooth, 0xFF4DA3FF.toInt(), "Disconnected", on = false),
            EventItem(R.drawable.ic_airplane, 0xFFFF9F0A.toInt(), "Airplane"),
            EventItem(R.drawable.ic_airplane, white, "Airplane off", on = false),
            EventItem(R.drawable.ic_mute, white, "Silent"),
            EventItem(R.drawable.ic_ring, white, "Ring"),
            EventItem(R.drawable.ic_bolt, 0xFF34C759.toInt(), "80%", EventItem.KIND_CHARGE, 80),
        )
        NotchBus.onEvent?.invoke(samples[previewIndex % samples.size])
        previewIndex++
    }
}
