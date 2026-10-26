package app.sona.notch

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotchListener : NotificationListenerService() {

    override fun onListenerConnected() {
        Prefs.init(this)
        MediaMonitor.attach(this, ComponentName(this, NotchListener::class.java))
    }

    override fun onListenerDisconnected() {
        MediaMonitor.detach()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        CallMonitor.clear(sbn.key)
        LiveMonitor.clear(sbn.key)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Prefs.init(this)
        val n = sbn.notification
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0

        // A call (ongoing, or ringing with answer/decline actions) becomes the call indicator, not a pop-up.
        // The app's own test notification is accepted here so the feature can be tried without calling.
        if (n.category == Notification.CATEGORY_CALL && (ongoing || (n.actions?.size ?: 0) >= 2)) {
            handleCall(sbn)
            return
        }
        // The app's own test notification (Settings > Send test notification) is allowed through.
        if (sbn.packageName == packageName && n.channelId != TEST_CHANNEL) return
        if (Prefs.isMuted(sbn.packageName)) return

        if (ongoing && isLiveActivity(n)) {
            if (Prefs.bool(Prefs.LIVE_ACTIVITIES)) handleLive(sbn)
            return
        }

        val skippedFlags = Notification.FLAG_ONGOING_EVENT or
            Notification.FLAG_GROUP_SUMMARY or
            Notification.FLAG_FOREGROUND_SERVICE
        if (n.flags and skippedFlags != 0) return
        if (n.category in SKIPPED_CATEGORIES) return
        if (!Prefs.bool(Prefs.APP_POPUPS)) return

        // Silent notifications do not need a pop-up.
        val ranking = Ranking()
        if (currentRanking.getRanking(sbn.key, ranking) &&
            ranking.importance < NotificationManager.IMPORTANCE_DEFAULT
        ) return

        if (Prefs.bool(Prefs.RESPECT_DND) &&
            getSystemService(NotificationManager::class.java).currentInterruptionFilter !=
            NotificationManager.INTERRUPTION_FILTER_ALL
        ) return

        val extras = n.extras
        var title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        var text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        if (Prefs.bool(Prefs.HIDE_CONTENT)) {
            title = ""
            text = "New notification"
        }

        val icon: Drawable? = try {
            n.getLargeIcon()?.loadDrawable(this)
        } catch (e: Exception) {
            null
        } ?: appIcon(sbn.packageName)

        NotchBus.onNotification?.invoke(
            NotifItem(
                sbn.key, appLabel(sbn.packageName), title, text, icon, n.contentIntent, collectActions(n, 3),
                convKey = sbn.packageName + "|" + title,
            ),
        )
    }

    /** Ongoing notification with a stopwatch/countdown (voice recorder, ride, screen recording) or navigation. */
    private fun isLiveActivity(n: Notification): Boolean {
        if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_CALL) return false
        val extras = n.extras
        val hasProgress = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 &&
            !extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        return extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) ||
            n.category == Notification.CATEGORY_NAVIGATION || hasProgress
    }

    private fun handleLive(sbn: StatusBarNotification) {
        val n = sbn.notification
        val extras = n.extras
        LiveMonitor.update(
            LiveInfo(
                key = sbn.key,
                pkg = sbn.packageName,
                appName = appLabel(sbn.packageName),
                icon = appIcon(sbn.packageName),
                title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
                whenMs = n.`when`,
                chronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false),
                countDown = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false),
                open = n.contentIntent,
                progress = run {
                    val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
                    if (max > 0 && !extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)) {
                        (extras.getInt(Notification.EXTRA_PROGRESS, 0) * 100 / max).coerceIn(0, 100)
                    } else -1
                },
            ),
        )
    }

    private fun handleCall(sbn: StatusBarNotification) {
        val n = sbn.notification
        val extras = n.extras
        val actions = n.actions.orEmpty()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        // The contact name is the title; if the title is generic or empty, use the text.
        val generic = title.isBlank() || GENERIC_CALL.containsMatchIn(title)
        val name = if (generic && text.isNotBlank()) text else title.ifBlank { "Call" }

        // A notification with a stopwatch means the call is connected; `when` is the stopwatch's zero point.
        val connected = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        val answer = actions.firstOrNull { ANSWER.containsMatchIn(it.title) }?.actionIntent
        val ringing = !connected && answer != null
        val previous = CallMonitor.current?.takeIf { it.key == sbn.key }
        val startMs = when {
            !connected -> 0L
            previous != null && previous.startMs > 0L -> previous.startMs
            else -> n.`when`.takeIf { it > 0L } ?: System.currentTimeMillis()
        }
        val hangUp = actions.firstOrNull { HANG_UP.containsMatchIn(it.title) }?.actionIntent
            ?: if (ringing) null else actions.lastOrNull()?.actionIntent
        // The contact photo (large icon) beats the dialer icon when the dialer provides one.
        val avatar: Drawable? = try {
            n.getLargeIcon()?.loadDrawable(this)
        } catch (e: Exception) {
            null
        }
        val extras2 = collectActions(n, 4)
            .filter { !ANSWER.containsMatchIn(it.label) && !HANG_UP.containsMatchIn(it.label) }
            .take(2)
        CallMonitor.update(
            CallInfo(
                sbn.key, sbn.packageName, name, avatar ?: appIcon(sbn.packageName), startMs, hangUp,
                n.contentIntent, ringing, answer, extras2,
            ),
        )
    }

    /** The notification's own buttons; inline-reply actions carry their RemoteInput. */
    private fun collectActions(n: Notification, limit: Int): List<NotifAction> =
        n.actions.orEmpty().mapNotNull { a ->
            val label = a.title?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NotifAction(label, a.actionIntent, a.remoteInputs?.firstOrNull(), a)
        }.take(limit)

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun appIcon(pkg: String): Drawable? = try {
        packageManager.getApplicationIcon(pkg)
    } catch (e: Exception) {
        null
    }

    companion object {
        const val TEST_CHANNEL = "island_test"
        private val GENERIC_CALL = Regex("(?i)call|panggilan|memanggil|calling")
        private val ANSWER = Regex("(?i)answer|accept|jawab|terima")
        private val HANG_UP = Regex("(?i)\\bend\\b|hang|decline|reject|akhiri|tutup|tolak")
        private val SKIPPED_CATEGORIES = setOf(
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_SYSTEM,
        )
    }
}
