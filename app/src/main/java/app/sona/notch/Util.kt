package app.sona.notch

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

fun roundBg(context: Context, color: Int, radiusDp: Float): Drawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = context.dp(radiusDp).toFloat()
    }

/** Umpan balik sentuhan: mengecil ke 0,96 saat ditekan, kembali saat dilepas. Tidak mengonsumsi event. */
@SuppressLint("ClickableViewAccessibility")
fun View.pressScale() {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }
        false
    }
}

fun TextView.setIfChanged(value: CharSequence) {
    if (text.toString() != value.toString()) text = value
}

/** 83000 -> "01:23", 3_700_000 -> "1:01:40"; tenths menambahkan ".d". */
fun formatDuration(ms: Long, tenths: Boolean = false): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val base = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    return if (tenths) base + "." + ((ms.coerceAtLeast(0) % 1000) / 100) else base
}

/** A button from a notification (Reply, Mark as read, ...). [remoteInput] is set for inline-reply actions. */
data class NotifAction(
    val label: String,
    val intent: android.app.PendingIntent?,
    val remoteInput: android.app.RemoteInput?,
    val action: android.app.Notification.Action,
)

data class NotifItem(
    val key: String,
    val appName: String,
    val title: String,
    val text: String,
    val icon: Drawable?,
    val intent: android.app.PendingIntent?,
    val actions: List<NotifAction> = emptyList(),
    /** Same app + same sender = same conversation; messages from it are merged into one pop-up. */
    val convKey: String = key,
    val count: Int = 1,
)

/** Jembatan antara listener notifikasi dan overlay. */
object NotchBus {
    var onNotification: ((NotifItem) -> Unit)? = null
    var onEvent: ((EventItem) -> Unit)? = null
    var onReaction: ((EyesView.Reaction) -> Unit)? = null
}

/** Warna aksen dari cover: piksel paling jenuh dan terang, lalu dipastikan cukup cerah di latar gelap. */
fun accentFrom(bitmap: android.graphics.Bitmap?): Int {
    if (bitmap == null) return android.graphics.Color.WHITE
    val small = android.graphics.Bitmap.createScaledBitmap(bitmap, 24, 24, true)
    val hsv = FloatArray(3)
    var best = -1f
    var bestColor = android.graphics.Color.WHITE
    for (y in 0 until small.height) for (x in 0 until small.width) {
        val c = small.getPixel(x, y)
        android.graphics.Color.colorToHSV(c, hsv)
        val score = hsv[1] * hsv[2]
        if (score > best) {
            best = score
            bestColor = c
        }
    }
    android.graphics.Color.colorToHSV(bestColor, hsv)
    hsv[1] = maxOf(hsv[1], 0.6f)
    hsv[2] = maxOf(hsv[2], 0.9f)
    return android.graphics.Color.HSVToColor(hsv)
}

/**
 * Interpolator pegas teredam: ζ < 1 memberi overshoot halus (seperti pegas iOS), ζ = 1 tanpa overshoot.
 * ω menentukan kecepatan; nilai akhir praktis tepat 1 saat t = 1.
 */
class SpringInterpolator(private val zeta: Float = 0.72f, private val omega: Float = 10f) :
    android.view.animation.Interpolator {
    private val wd = if (zeta < 1f) omega * kotlin.math.sqrt(1f - zeta * zeta) else 0f

    override fun getInterpolation(t: Float): Float {
        val e = kotlin.math.exp(-zeta * omega * t)
        return if (zeta < 1f) {
            1f - e * (kotlin.math.cos(wd * t) + zeta * omega / wd * kotlin.math.sin(wd * t))
        } else {
            1f - (1f + omega * t) * e
        }
    }
}

/** Light haptic tick when a pop-up appears (and a firmer click for long-press), if enabled in settings. */
fun Context.haptic(firm: Boolean = false) {
    if (!Prefs.bool(Prefs.HAPTICS)) return
    val effect = if (firm) android.os.VibrationEffect.EFFECT_CLICK else android.os.VibrationEffect.EFFECT_TICK
    getSystemService(android.os.Vibrator::class.java).vibrate(android.os.VibrationEffect.createPredefined(effect))
}
