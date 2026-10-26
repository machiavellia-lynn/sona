package app.sona.notch

import android.content.Context
import android.content.SharedPreferences

/** User settings shared by the settings screen, the notification listener and the overlay service. */
object Prefs {
    const val EVENT_WIFI = "event_wifi"
    const val EVENT_CELL = "event_cell"
    const val EVENT_BLUETOOTH = "event_bluetooth"
    const val EVENT_AIRPLANE = "event_airplane"
    const val EVENT_RINGER = "event_ringer"
    const val EVENT_CHARGING = "event_charging"
    const val APP_POPUPS = "app_popups"
    const val HIDE_CONTENT = "hide_content"
    const val LIVE_ACTIVITIES = "live_activities"
    const val CALL_INDICATOR = "call_indicator"
    const val PRIVACY_DOTS = "privacy_dots"
    const val IDLE_CLOCK = "idle_clock"
    const val EVENT_BATTERY = "event_battery"
    const val REPLACE_HEADS_UP = "replace_heads_up"
    const val HIDE_FULLSCREEN = "hide_fullscreen"
    const val QUIET_ON = "quiet_on"
    const val QUIET_START = "quiet_start"
    const val QUIET_END = "quiet_end"
    const val HAPTICS = "haptics"
    const val RESPECT_DND = "respect_dnd"
    const val EVENT_SECONDS = "event_seconds"
    const val BANNER_SECONDS = "banner_seconds"

    private var sp: SharedPreferences? = null
    private val defaultsOff = setOf(HIDE_CONTENT, RESPECT_DND, IDLE_CLOCK, QUIET_ON)

    fun init(context: Context) {
        if (sp == null) {
            sp = context.applicationContext.getSharedPreferences("notch", Context.MODE_PRIVATE)
            Timers.load()
        }
    }

    fun long(key: String, default: Long): Long = sp?.getLong(key, default) ?: default

    fun setLong(key: String, value: Long) {
        sp?.edit()?.putLong(key, value)?.apply()
    }

    fun string(key: String, default: String): String = sp?.getString(key, default) ?: default

    fun setString(key: String, value: String) {
        sp?.edit()?.putString(key, value)?.apply()
    }

    /** True during the user's quiet hours (app pop-ups and events stay silent; calls and timers still show). */
    fun quietNow(): Boolean {
        if (!bool(QUIET_ON)) return false
        val start = int(QUIET_START, 22)
        val end = int(QUIET_END, 7)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return if (start <= end) hour in start until end else hour >= start || hour < end
    }

    fun bool(key: String): Boolean = sp?.getBoolean(key, key !in defaultsOff) ?: (key !in defaultsOff)

    fun setBool(key: String, value: Boolean) {
        sp?.edit()?.putBoolean(key, value)?.apply()
    }

    fun int(key: String, default: Int): Int = sp?.getInt(key, default) ?: default

    fun setInt(key: String, value: Int) {
        sp?.edit()?.putInt(key, value)?.apply()
    }

    fun eventMs() = int(EVENT_SECONDS, 5) * 1000L

    fun bannerMs() = int(BANNER_SECONDS, 7) * 1000L

    fun isMuted(pkg: String) = sp?.getStringSet("muted", emptySet())?.contains(pkg) == true

    fun setMuted(pkg: String, muted: Boolean) {
        val set = HashSet(sp?.getStringSet("muted", emptySet()).orEmpty())
        if (muted) set += pkg else set -= pkg
        sp?.edit()?.putStringSet("muted", set)?.apply()
    }
}
