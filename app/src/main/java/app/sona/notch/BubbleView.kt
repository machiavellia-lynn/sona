package app.sona.notch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.sin

/**
 * The second activity, shown as a small round bubble beside the pill (like two activities on an iPhone).
 * The black circle itself is drawn by [NotchLayout]; this view only draws the minimal glyph inside it.
 */
class BubbleView(context: Context) : View(context) {

    var live: Live? = null

    private val dp = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10.5f * dp
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
    }
    private val rect = RectF()
    private val clip = Path()

    init {
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        when (val l = live) {
            is Live.Call -> drawIcon(canvas, l.info.icon, cx, cy)
            is Live.App -> drawIcon(canvas, l.info.icon, cx, cy)
            is Live.Clock -> {
                textPaint.color = ORANGE
                canvas.drawText(l.text, cx, cy + textPaint.textSize * 0.35f, textPaint)
            }
            Live.Media -> {
                val barW = 2.8f * dp
                val gap = 2.2f * dp
                val total = 3 * barW + 2 * gap
                val t = SystemClock.uptimeMillis() / 1000f
                for (i in 0 until 3) {
                    val v = 0.5f + 0.5f * sin(t * (7f + i * 1.7f) + i * 2.1f)
                    val h = (4 + 10 * v) * dp
                    val x = cx - total / 2f + i * (barW + gap)
                    rect.set(x, cy - h / 2f, x + barW, cy + h / 2f)
                    canvas.drawRoundRect(rect, barW / 2f, barW / 2f, barPaint)
                }
                postInvalidateOnAnimation()
            }
            Live.Torch -> {
                val d = context.getDrawable(R.drawable.ic_torch)?.mutate()
                if (d != null) {
                    d.setTint(0xFFFFD60A.toInt())
                    val half = 9 * dp
                    d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
                    d.draw(canvas)
                }
            }
            null -> Unit
        }
    }

    private fun drawIcon(canvas: Canvas, icon: android.graphics.drawable.Drawable?, cx: Float, cy: Float) {
        val d = icon ?: return
        val half = 10 * dp
        canvas.save()
        rect.set(cx - half, cy - half, cx + half, cy + half)
        clip.reset()
        clip.addRoundRect(rect, 6 * dp, 6 * dp, Path.Direction.CW)
        canvas.clipPath(clip)
        d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        d.draw(canvas)
        canvas.restore()
    }

    private companion object {
        val ORANGE = 0xFFFF9F0A.toInt()
    }
}
