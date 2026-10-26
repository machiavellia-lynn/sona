package app.sona.notch

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * While the notch is visible it replaces the system's own heads-up pop-ups, so a message does not show twice.
 * The user's original value is saved before changing it (and an "active" flag survives a crash), then restored
 * whenever the notch hides (lock screen, fullscreen video), stops, or the option is turned off.
 */
object HeadsUp {
    private const val KEY = "heads_up_notifications_enabled"
    private const val ORIGINAL = "heads_up_original"
    private const val ACTIVE = "heads_up_active"

    fun set(context: Context, replace: Boolean) {
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val resolver = context.contentResolver
        try {
            val active = Prefs.long(ACTIVE, 0L) == 1L
            if (replace && !active) {
                Prefs.setLong(ORIGINAL, Settings.Global.getInt(resolver, KEY, 1).toLong())
                Prefs.setLong(ACTIVE, 1L)
                Settings.Global.putInt(resolver, KEY, 0)
            } else if (!replace && active) {
                Settings.Global.putInt(resolver, KEY, Prefs.long(ORIGINAL, 1L).toInt())
                Prefs.setLong(ACTIVE, 0L)
            }
        } catch (e: SecurityException) {
            // Not allowed to change it: leave the system pop-ups alone.
        }
    }
}
