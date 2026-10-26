package app.sona.notch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Progress bar pemutar ala One UI: track tipis, bagian terputar berwarna aksen cover, gelombang
 * transparan di atasnya yang bergerak saat musik diputar, dan thumb ber-cincin putih dengan cahaya.
 */
class SeekBarView(context: Context) : View(context) {

    var fraction = 0f
    var accent = Color.WHITE
    var playing = false
    var onSeek: ((Float) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val trackH = 4.5f * dp
    private val inset = 7f * dp
    private val thumbR = 7.5f * dp
    private val waveH = 12f * dp

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * dp
        color = Color.WHITE
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    private var dragging = false
    private var dragFraction = 0f

    /** Posisi yang ditampilkan: jari saat digeser, kalau tidak nilai dari pemutar. */
    val shownFraction get() = if (dragging) dragFraction else fraction

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                dragFraction = fractionAt(e.x)
            }
            MotionEvent.ACTION_MOVE -> dragFraction = fractionAt(e.x)
            MotionEvent.ACTION_UP -> {
                dragFraction = fractionAt(e.x)
                dragging = false
                fraction = dragFraction
                onSeek?.invoke(dragFraction)
            }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        invalidate()
        return true
    }

    private fun fractionAt(x: Float) =
        ((x - inset) / max(1f, width - 2 * inset)).coerceIn(0f, 1f)

    override fun onDraw(canvas: Canvas) {
        val left = inset
        val right = width - inset
        val cy = height / 2f
        val thumbX = left + (right - left) * shownFraction.coerceIn(0f, 1f)

        trackPaint.color = Color.argb(95, 255, 255, 255)
        rect.set(left, cy - trackH / 2, right, cy + trackH / 2)
        canvas.drawRoundRect(rect, trackH / 2, trackH / 2, trackPaint)
        trackPaint.color = accent
        rect.set(left, cy - trackH / 2, thumbX, cy + trackH / 2)
        canvas.drawRoundRect(rect, trackH / 2, trackH / 2, trackPaint)

        drawWaves(canvas, left, thumbX, cy - trackH / 2)

        glowPaint.shader = RadialGradient(
            thumbX, cy, 17 * dp,
            Color.argb(150, Color.red(accent), Color.green(accent), Color.blue(accent)),
            Color.argb(0, Color.red(accent), Color.green(accent), Color.blue(accent)),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(thumbX, cy, 17 * dp, glowPaint)
        thumbPaint.color = accent
        canvas.drawCircle(thumbX, cy, thumbR, thumbPaint)
        canvas.drawCircle(thumbX, cy, thumbR, ringPaint)

        if (playing) postInvalidateOnAnimation()
    }

    /** Tiga lapis gelombang dengan fase berbeda; amplitudo mengecil ke kedua ujung bagian terputar. */
    private fun drawWaves(canvas: Canvas, from: Float, to: Float, baseY: Float) {
        val span = to - from
        if (span < 8 * dp) return
        val t = SystemClock.uptimeMillis() / 1000f * (if (playing) 1f else 0f)
        // Lapisan atas memakai aksen yang diputihkan setengah, seperti gelombang kuning-persik di Samsung.
        val light = Color.rgb(
            (Color.red(accent) + 255) / 2, (Color.green(accent) + 255) / 2, (Color.blue(accent) + 255) / 2,
        )
        val layers = arrayOf(
            Triple(1.00f, 0.0f, Color.argb(215, Color.red(light), Color.green(light), Color.blue(light))),
            Triple(0.78f, 2.1f, Color.argb(150, 255, 255, 255)),
            Triple(0.55f, 4.0f, Color.argb(120, Color.red(accent), Color.green(accent), Color.blue(accent))),
        )
        for ((scale, phase, color) in layers) {
            path.reset()
            path.moveTo(from, baseY)
            var x = from
            while (x <= to) {
                val u = (x - from) / span
                val envelope = sin(PI * u).toFloat()
                val wobble = 0.6f + 0.4f * sin(x / dp * 0.085f + t * 1.6f + phase)
                path.lineTo(x, baseY - waveH * scale * envelope * wobble)
                x += 3 * dp
            }
            path.lineTo(to, baseY)
            path.close()
            wavePaint.color = color
            canvas.drawPath(path, wavePaint)
        }
    }
}
