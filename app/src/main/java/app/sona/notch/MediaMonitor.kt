package app.sona.notch

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock

/**
 * Membaca sesi media aktif. getActiveSessions() hanya boleh dipanggil kalau app
 * adalah notification listener yang aktif, makanya attach() dipanggil dari NotchListener.
 */
object MediaMonitor {

    data class State(
        val title: String? = null,
        val artist: String? = null,
        val art: Bitmap? = null,
        val playing: Boolean = false,
        val duration: Long = 0L,
        val pkg: String? = null,
    )

    var state = State()
        private set
    var hasAccess = false
        private set

    /** Aksi kustom milik aplikasi musik (mis. like, shuffle); ikonnya diambil dari paket aplikasinya. */
    var customActions: List<PlaybackState.CustomAction> = emptyList()
        private set
    var onChange: (() -> Unit)? = null

    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null
    /** Opens the music app (the session activity), used when the pill is tapped while music plays. */
    var sessionActivity: android.app.PendingIntent? = null
        private set

    private var basePos = 0L
    private var baseTime = 0L
    private var speed = 1f

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onSessionDestroyed() = refresh()
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }

    fun attach(context: Context, component: ComponentName) {
        val msm = context.getSystemService(MediaSessionManager::class.java)
        manager = msm
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component)
            hasAccess = true
            pick(msm.getActiveSessions(component))
        } catch (e: SecurityException) {
            hasAccess = false
            onChange?.invoke()
        }
    }

    fun detach() {
        manager?.removeOnActiveSessionsChangedListener(sessionsListener)
        controller?.unregisterCallback(callback)
        controller = null
        hasAccess = false
        state = State()
        onChange?.invoke()
    }

    fun position(): Long {
        if (!state.playing) return basePos
        val now = basePos + ((SystemClock.elapsedRealtime() - baseTime) * speed).toLong()
        return if (state.duration > 0) now.coerceIn(0L, state.duration) else now
    }

    fun playPause() {
        val c = controller ?: return
        if (state.playing) c.transportControls.pause() else c.transportControls.play()
    }

    fun seekTo(ms: Long) = controller?.transportControls?.seekTo(ms)

    fun sendCustom(action: PlaybackState.CustomAction) =
        controller?.transportControls?.sendCustomAction(action, null)

    fun next() = controller?.transportControls?.skipToNext()
    fun prev() = controller?.transportControls?.skipToPrevious()

    private fun pick(list: List<MediaController>?) {
        val chosen = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list?.firstOrNull()
        if (chosen?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = chosen
            controller?.registerCallback(callback)
        }
        refresh()
    }

    private fun refresh() {
        val c = controller
        val meta = c?.metadata
        val ps = c?.playbackState
        if (ps != null) {
            basePos = ps.position
            baseTime = ps.lastPositionUpdateTime.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()
            speed = ps.playbackSpeed
        }
        sessionActivity = c?.sessionActivity
        customActions = ps?.customActions.orEmpty()
        state = State(
            title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            playing = ps?.state == PlaybackState.STATE_PLAYING,
            duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            pkg = c?.packageName,
        )
        onChange?.invoke()
    }
}
