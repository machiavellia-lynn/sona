package app.sona.notch

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper

/** Torch state and control. The pill shows a torch glyph while it is on; the Quick page has a brightness slider. */
object Flashlight {
    var on = false
        private set
    var onChange: (() -> Unit)? = null

    private var manager: CameraManager? = null
    private var cameraId: String? = null
    private var maxLevel = 1

    private val callback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (id != cameraId) return
            on = enabled
            onChange?.invoke()
        }
    }

    fun start(context: Context) {
        val cm = context.getSystemService(CameraManager::class.java)
        manager = cm
        val id = cm.cameraIdList.firstOrNull {
            val c = cm.getCameraCharacteristics(it)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return
        cameraId = id
        maxLevel = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
        cm.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
    }

    fun stop() {
        manager?.unregisterTorchCallback(callback)
        manager = null
        on = false
    }

    val available get() = cameraId != null

    /** True when the flash supports several brightness levels. */
    val adjustable get() = maxLevel > 1

    val max get() = maxLevel

    fun toggle(context: Context) {
        val id = cameraId ?: return
        try {
            manager?.setTorchMode(id, !on)
        } catch (e: Exception) {
            // The camera is in use by another app.
        }
    }

    /** Turns the torch on at [level] (1..max). */
    fun setLevel(level: Int) {
        val id = cameraId ?: return
        try {
            manager?.turnOnTorchWithStrengthLevel(id, level.coerceIn(1, maxLevel))
        } catch (e: Exception) {
            // Torch strength is not supported or the camera is busy.
        }
    }
}
