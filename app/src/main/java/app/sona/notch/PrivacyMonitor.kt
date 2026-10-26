package app.sona.notch

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper

/**
 * Tracks whether any app is using the camera or the microphone, so the pill can show the same green/orange
 * privacy dot as an iPhone. Uses public callbacks only: camera availability and active audio recordings.
 */
object PrivacyMonitor {
    var camera = false
        private set
    var mic = false
        private set
    var onChange: (() -> Unit)? = null

    private val busyCameras = HashSet<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var cameraManager: CameraManager? = null
    private var audioManager: AudioManager? = null

    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            busyCameras += cameraId
            update()
        }

        override fun onCameraAvailable(cameraId: String) {
            busyCameras -= cameraId
            update()
        }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val active = configs.any { !it.isClientSilenced }
            if (active != mic) {
                mic = active
                onChange?.invoke()
            }
        }
    }

    private fun update() {
        val now = busyCameras.isNotEmpty()
        if (now != camera) {
            camera = now
            onChange?.invoke()
        }
    }

    fun start(context: Context) {
        cameraManager = context.getSystemService(CameraManager::class.java).also {
            it.registerAvailabilityCallback(cameraCallback, handler)
        }
        audioManager = context.getSystemService(AudioManager::class.java).also {
            it.registerAudioRecordingCallback(recordingCallback, handler)
        }
    }

    fun stop() {
        cameraManager?.unregisterAvailabilityCallback(cameraCallback)
        audioManager?.unregisterAudioRecordingCallback(recordingCallback)
        cameraManager = null
        audioManager = null
        busyCameras.clear()
        camera = false
        mic = false
    }
}
