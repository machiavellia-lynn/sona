package app.sona.notch

import android.app.PendingIntent
import android.graphics.drawable.Drawable

/** Phone call from the dialer's call notification. [startMs] is 0 until the call is connected. */
data class CallInfo(
    val key: String,
    val pkg: String,
    val name: String,
    val icon: Drawable?,
    val startMs: Long,
    val hangUp: PendingIntent?,
    val open: PendingIntent?,
    val ringing: Boolean = false,
    val answer: PendingIntent? = null,
    /** Other call actions from the notification (e.g. Mute, Speaker), at most two. */
    val extras: List<NotifAction> = emptyList(),
)

/** Ongoing notification of another app shown as a live activity (stopwatch/countdown, navigation). */
data class LiveInfo(
    val key: String,
    val pkg: String,
    val appName: String,
    val icon: Drawable?,
    val title: String,
    val text: String,
    val whenMs: Long,
    val chronometer: Boolean,
    val countDown: Boolean,
    val open: PendingIntent?,
    /** 0..100 for a download or other progress notification, -1 if it has none. */
    val progress: Int = -1,
)

/** What the compact pill is currently showing. At most two are shown: primary in the pill, secondary in a bubble. */
sealed class Live {
    data class Call(val info: CallInfo, val text: String) : Live()
    data class App(val info: LiveInfo, val text: String) : Live()
    /** Timer/stopwatch; [urgent] turns the time red for the last ten seconds of a timer. */
    data class Clock(val text: String, val urgent: Boolean = false) : Live()
    object Media : Live()
    object Torch : Live()
}

object CallMonitor {
    var current: CallInfo? = null
        private set
    var onChange: (() -> Unit)? = null

    fun update(info: CallInfo) {
        current = info
        onChange?.invoke()
    }

    fun clear(key: String) {
        if (current?.key == key) {
            current = null
            onChange?.invoke()
        }
    }

    fun hangUp() = send(current?.hangUp)

    fun answer() = send(current?.answer)

    private fun send(pi: PendingIntent?) {
        try {
            pi?.send()
        } catch (e: PendingIntent.CanceledException) {
            // The notification is already gone.
        }
    }

    /** "02:15" once connected, "Incoming" while ringing, "Calling…" before it connects. */
    fun statusText(): String {
        val c = current ?: return ""
        return when {
            c.ringing -> "Incoming"
            c.startMs <= 0L -> "Calling…"
            else -> formatDuration(System.currentTimeMillis() - c.startMs)
        }
    }
}

object LiveMonitor {
    private val items = LinkedHashMap<String, LiveInfo>()
    var onChange: (() -> Unit)? = null

    fun update(info: LiveInfo) {
        items.remove(info.key)
        items[info.key] = info
        onChange?.invoke()
    }

    fun clear(key: String) {
        if (items.remove(key) != null) onChange?.invoke()
    }

    fun latest(): LiveInfo? = items.values.lastOrNull()

    /** Elapsed/remaining time for stopwatch-style notifications, otherwise a short title (e.g. "5 min"). */
    fun text(info: LiveInfo): String {
        if (info.progress >= 0) return "${info.progress}%"
        if (!info.chronometer) return info.title
        val now = System.currentTimeMillis()
        return formatDuration(if (info.countDown) info.whenMs - now else now - info.whenMs)
    }
}

