package app.sona.notch

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.hardware.SensorManager
import android.os.PowerManager
import android.os.SystemClock
import kotlin.math.sqrt
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.content.res.Configuration
import android.view.accessibility.AccessibilityEvent

/**
 * A TYPE_ACCESSIBILITY_OVERLAY window sits above the status bar, so the notch is never covered.
 * There are two windows: one that draws (fixed size, not touchable) and one transparent window for touches
 * (its size follows the interactive area). The service does not read screen content.
 * Both windows are shifted horizontally to the front camera's real position, so other phones and rotation work.
 */
class NotchAccessibilityService : AccessibilityService() {

    private var layout: NotchLayout? = null
    private var proxy: TouchProxy? = null
    private var drawParams: WindowManager.LayoutParams? = null
    private var touchParams: WindowManager.LayoutParams? = null
    private var systemEvents: SystemEvents? = null
    private var wasLocked = false
    private val shakeSpikes = ArrayDeque<Long>()
    private var lastShake = 0L
    private var headsUpReplaced = false
    private var screenOn = true

    /** Nothing needs to run while the screen is off: stop the ticker and the shake sensor, resume on screen on. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) pauseWork() else resumeWork()
        }
    }

    private fun pauseWork() {
        screenOn = false
        handler.removeCallbacks(ticker)
        getSystemService(SensorManager::class.java).unregisterListener(shakeListener)
    }

    private fun resumeWork() {
        if (screenOn && handler.hasCallbacks(ticker)) return
        screenOn = true
        handler.post(ticker)
        getSystemService(SensorManager::class.java).let { sm ->
            // Batching (0.5 s) lets the sensor hub collect events instead of waking the CPU for each one.
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sm.registerListener(shakeListener, it, SensorManager.SENSOR_DELAY_UI, 500_000)
            }
        }
    }

    /** Three hard jolts within a second = the phone is being shaken: the mascot gets dizzy. */
    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) /
                SensorManager.GRAVITY_EARTH
            if (g < 2.6f) return
            val now = SystemClock.elapsedRealtime()
            shakeSpikes.addLast(now)
            while (shakeSpikes.isNotEmpty() && now - shakeSpikes.first() > 1000) shakeSpikes.removeFirst()
            if (shakeSpikes.size >= 3 && now - lastShake > 4000) {
                lastShake = now
                shakeSpikes.clear()
                layout?.reactEyes(EyesView.Reaction.DIZZY)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            // On the lock screen the notch disappears completely (nothing drawn, no touches captured).
            val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
            // Landscape hides the notch too (video, games), except for a ringing call. Android gives an overlay no
            // reliable way to know that another app went fullscreen, so landscape is the signal we can trust.
            val ringing = CallMonitor.current?.ringing == true
            val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val fullscreen = Prefs.bool(Prefs.HIDE_FULLSCREEN) && landscape && !ringing
            val hidden = locked || fullscreen
            if (hidden != wasLocked) {
                wasLocked = hidden
                layout?.setLocked(hidden)
            }
            // The notch replaces the system's heads-up pop-ups only while it is actually visible.
            val replace = Prefs.bool(Prefs.REPLACE_HEADS_UP) && !hidden
            if (replace != headsUpReplaced) {
                headsUpReplaced = replace
                HeadsUp.set(this@NotchAccessibilityService, replace)
            }
            if (!hidden) {
                if (Timers.consumeFinished()) timerFinished()
                if (Timers.countdownTick()) haptic()
                layout?.tick()
            }
            handler.postDelayed(this, if (layout?.needsFastTick() == false) 1000L else 250L)
        }
    }

    override fun onServiceConnected() {
        Prefs.init(this)
        if (layout != null) return
        addNotch()
        NotchBus.onNotification = { item -> layout?.showBanner(item) }
        NotchBus.onEvent = { item -> layout?.showEvent(item) }
        NotchBus.onReaction = { r -> layout?.reactEyes(r) }
        MediaMonitor.onChange = { layout?.tick() }
        CallMonitor.onChange = { layout?.tick() }
        LiveMonitor.onChange = { layout?.tick() }
        systemEvents = SystemEvents(this) { layout?.showEvent(it) }.also { it.register() }
        PrivacyMonitor.onChange = { layout?.tick() }
        PrivacyMonitor.start(this)
        Flashlight.onChange = { layout?.tick() }
        Flashlight.start(this)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        screenOn = false
        if (getSystemService(PowerManager::class.java).isInteractive) resumeWork()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** Rotation or a fold changes where the camera is; re-centre both windows on it. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.postDelayed({ applyCameraOffset() }, 150)
    }

    /** Horizontal distance from the screen centre to the camera cut-out at the top edge (0 if there is none there). */
    private fun cameraOffsetPx(): Int {
        val metrics = getSystemService(WindowManager::class.java).currentWindowMetrics
        val top = metrics.windowInsets.displayCutout?.boundingRectTop ?: return 0
        if (top.isEmpty) return 0
        return top.centerX() - metrics.bounds.width() / 2
    }

    private fun applyCameraOffset() {
        val draw = drawParams ?: return
        val touch = touchParams ?: return
        val wm = getSystemService(WindowManager::class.java)
        val offset = cameraOffsetPx()
        if (draw.x == offset && touch.x == offset) return
        draw.x = offset
        touch.x = offset
        layout?.let { wm.updateViewLayout(it, draw) }
        proxy?.let { wm.updateViewLayout(it, touch) }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        handler.removeCallbacksAndMessages(null)
        NotchBus.onNotification = null
        NotchBus.onEvent = null
        NotchBus.onReaction = null
        MediaMonitor.onChange = null
        CallMonitor.onChange = null
        LiveMonitor.onChange = null
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: IllegalArgumentException) {
            // Was never registered.
        }
        HeadsUp.set(this, false)
        headsUpReplaced = false
        PrivacyMonitor.onChange = null
        PrivacyMonitor.stop()
        Flashlight.onChange = null
        Flashlight.stop()
        getSystemService(SensorManager::class.java).unregisterListener(shakeListener)
        systemEvents?.unregister()
        systemEvents = null
        val wm = getSystemService(WindowManager::class.java)
        layout?.let { wm.removeView(it) }
        proxy?.let { wm.removeView(it) }
        layout = null
        proxy = null
    }

    private fun baseParams(flags: Int) = WindowManager.LayoutParams(
        0,
        0,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or flags,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private fun addNotch() {
        val wm = getSystemService(WindowManager::class.java)
        val draw = baseParams(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        val touch = baseParams(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)

        val notch = NotchLayout(this, { w, h ->
            touch.width = w
            touch.height = h
            proxy?.let { wm.updateViewLayout(it, touch) }
        }) { openListenerSettings() }

        val (fullW, fullH) = notch.sizeFor(NotchLayout.Mode.PANEL)
        draw.width = fullW
        draw.height = fullH
        val (pillW, pillH) = notch.sizeFor(NotchLayout.Mode.COMPACT)
        touch.width = pillW
        touch.height = pillH

        val offset = cameraOffsetPx()
        draw.x = offset
        touch.x = offset

        val touchProxy = TouchProxy(this, notch) { (draw.width - touch.width) / 2f }
        wm.addView(notch, draw)
        wm.addView(touchProxy, touch)
        layout = notch
        proxy = touchProxy
        drawParams = draw
        touchParams = touch
    }

    private fun openListenerSettings() {
        startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun timerFinished() {
        getSystemService(Vibrator::class.java)
            .vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300, 150, 300), -1))
        layout?.showBanner(
            NotifItem(
                key = "timer",
                appName = "Timer",
                title = "Timer done",
                text = "",
                icon = getDrawable(android.R.drawable.ic_lock_idle_alarm),
                intent = null,
            ),
        )
    }
}
