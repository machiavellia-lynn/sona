package app.sona.notch

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.exp
import kotlin.math.sin

/** System event pop-up: logo left of the camera, detail right of it. */
data class EventItem(
    val iconRes: Int,
    val tint: Int,
    val text: String,
    /** Which logo to draw on the left: an icon, the Wi-Fi fan, the cellular bars or the charging ring. */
    val kind: Int = KIND_ICON,
    /** Signal level 1..4 for [KIND_WIFI] and [KIND_CELL]; battery percent 0..100 for [KIND_CHARGE]. */
    val signal: Int = 4,
    /** false = feature turned off / disconnected: the logo dims and is struck through with an animation. */
    val on: Boolean = true,
    /** How the mascot reacts once the pill is back (hearts for charging, tears for low battery, ...). */
    val reaction: EyesView.Reaction? = null,
) {
    companion object {
        const val KIND_ICON = 0
        const val KIND_WIFI = 1
        const val KIND_CELL = 2
        const val KIND_CHARGE = 3
    }
}

class EventView(context: Context) : View(context) {

    private val dp = resources.displayMetrics.density
    private var item: EventItem? = null
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.3f * dp
    }
    private val slashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.2f * dp
        color = Color.WHITE
    }
    private val cutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 5f * dp
        color = Color.BLACK
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12.5f * dp
        typeface = Typeface.DEFAULT_BOLD
        fontFeatureSettings = "tnum"
    }
    private val rect = RectF()

    /** Half of the pill's current width; the logo sits 27dp inside the left edge, the text starts right of the camera. */
    private var half = MIN_W * dp / 2f

    /** 0..1 (may exceed 1 on spring overshoot): the logo pops in, then the text follows. */
    private var appear = 0f

    /** 0..1: the strike-through draws itself for "off" events. */
    private var slash = 0f

    /** 0..1: a soft ring pulses outward from the logo once when something turns on. */
    private var pulse = 0f

    /** 0..1: charging ring fills up to the battery level. */
    private var ring = 0f

    /** 0..1: the bell swings and settles. */
    private var wiggle = 0f
    private val anims = ArrayList<ValueAnimator>()

    init {
        isClickable = true
    }

    /** Width in px this pill needs for [event]: wide enough for the text, never narrower than a compact event. */
    fun widthFor(event: EventItem): Float {
        val textW = textPaint.measureText(event.text)
        val halfW = maxOf(MIN_W * dp / 2f, (TEXT_START + 16) * dp + textW)
        return minOf(MAX_W * dp, 2f * halfW)
    }

    fun bind(event: EventItem) {
        item = event
        half = widthFor(event) / 2f
        anims.forEach { it.cancel() }
        anims.clear()
        appear = 0f
        slash = 0f
        pulse = 0f
        ring = 0f
        wiggle = 0f

        run(650, SpringInterpolator(0.62f, 11f)) { appear = it }
        if (event.on && event.kind != EventItem.KIND_CHARGE) run(750, DecelerateInterpolator(1.4f), delay = 160) { pulse = it }
        if (event.kind == EventItem.KIND_CHARGE) run(1000, DecelerateInterpolator(1.6f), delay = 120) { ring = it }
        if (event.iconRes == R.drawable.ic_ring && event.on) run(900, DecelerateInterpolator(1f), delay = 100) { wiggle = it }
        if (!event.on) run(380, DecelerateInterpolator(1.6f), delay = 420) { slash = it }
        invalidate()
    }

    private fun run(ms: Long, interp: android.view.animation.Interpolator, delay: Long = 0, set: (Float) -> Unit) {
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = ms
        a.startDelay = delay
        a.interpolator = interp
        a.addUpdateListener {
            set(it.animatedValue as Float)
            invalidate()
        }
        anims += a
        a.start()
    }

    override fun onDraw(canvas: Canvas) {
        val e = item ?: return
        val cx = width / 2f
        val cy = 20 * dp

        val lead = cx - half + LEAD_INSET * dp
        val s = (0.45f + 0.55f * appear).coerceAtLeast(0f)
        val alpha = (appear * 1.8f).coerceIn(0f, 1f) * (if (e.on) 1f else 1f - 0.45f * slash)

        if (pulse > 0f && pulse < 1f) {
            arcPaint.color = Color.argb((110 * (1f - pulse)).toInt(), Color.red(e.tint), Color.green(e.tint), Color.blue(e.tint))
            canvas.drawCircle(lead, cy, (11 + 12 * pulse) * dp, arcPaint)
        }

        canvas.save()
        canvas.scale(s, s, lead, cy)
        if (e.iconRes == R.drawable.ic_ring && e.kind == EventItem.KIND_ICON) {
            // The bell swings from the top and settles: a decaying sine.
            canvas.rotate(22f * sin(wiggle * 16f) * exp(-3.5f * wiggle), lead, cy - 9 * dp)
        }
        when (e.kind) {
            EventItem.KIND_WIFI -> drawWifi(canvas, lead, cy, e.signal, alpha, e.on)
            EventItem.KIND_CELL -> drawBars(canvas, lead, cy, e.signal, alpha, e.on)
            EventItem.KIND_CHARGE -> drawCharge(canvas, e, lead, cy, alpha)
            else -> drawIcon(canvas, e, lead, cy, alpha, 11f)
        }
        if (!e.on && slash > 0f) drawSlash(canvas, lead, cy)
        canvas.restore()

        // Detail right of the camera slides in just after the logo; long text ends with "…".
        val ta = ((appear - 0.35f) / 0.5f).coerceIn(0f, 1f)
        textPaint.color = if (e.on) Color.WHITE else Color.argb(185, 255, 255, 255)
        textPaint.alpha = (textPaint.alpha * ta).toInt()
        val room = half - (TEXT_START + 14) * dp
        val label = TextUtils.ellipsize(e.text, textPaint, room, TextUtils.TruncateAt.END).toString()
        canvas.drawText(label, cx + TEXT_START * dp + (1f - ta) * 8 * dp, cy + textPaint.textSize * 0.35f, textPaint)
    }

    private fun drawIcon(canvas: Canvas, e: EventItem, cx: Float, cy: Float, alpha: Float, halfDp: Float) {
        val d = context.getDrawable(e.iconRes)?.mutate() ?: return
        d.setTint(e.tint)
        d.alpha = (255 * alpha).toInt()
        val h = halfDp * dp
        d.setBounds((cx - h).toInt(), (cy - h).toInt(), (cx + h).toInt(), (cy + h).toInt())
        d.draw(canvas)
    }

    /** Charging logo: a ring that fills to the battery level with a bolt in its centre. */
    private fun drawCharge(canvas: Canvas, e: EventItem, cx: Float, cy: Float, alpha: Float) {
        val r = 10.5f * dp
        rect.set(cx - r, cy - r, cx + r, cy + r)
        arcPaint.color = Color.argb((60 * alpha).toInt(), Color.red(e.tint), Color.green(e.tint), Color.blue(e.tint))
        canvas.drawArc(rect, 0f, 360f, false, arcPaint)
        arcPaint.color = Color.argb((255 * alpha).toInt(), Color.red(e.tint), Color.green(e.tint), Color.blue(e.tint))
        canvas.drawArc(rect, -90f, 360f * e.signal.coerceIn(0, 100) / 100f * ring, false, arcPaint)
        drawIcon(canvas, e, cx, cy, alpha, 6.5f)
    }

    /** Cellular logo: four ascending bars; [level] of them are filled. */
    private fun drawBars(canvas: Canvas, cx: Float, cy: Float, level: Int, alpha: Float, on: Boolean) {
        val w = 3.6f * dp
        val gap = 2.2f * dp
        val total = 4 * w + 3 * gap
        for (i in 0 until 4) {
            val h = (6 + i * 3.4f) * dp
            val x = cx - total / 2f + i * (w + gap)
            val filled = on && i < level
            barPaint.color = Color.argb(((if (filled) 255 else 80) * alpha).toInt(), 255, 255, 255)
            rect.set(x, cy + 8 * dp - h, x + w, cy + 8 * dp)
            canvas.drawRoundRect(rect, w / 2f, w / 2f, barPaint)
        }
    }

    /** Wi-Fi logo: a dot and three arcs fanning upward; the dot plus [level]-1 arcs are filled. */
    private fun drawWifi(canvas: Canvas, cx: Float, cy: Float, level: Int, alpha: Float, on: Boolean) {
        val baseY = cy + 8.5f * dp
        val radii = floatArrayOf(5.2f * dp, 9.6f * dp, 14f * dp)
        for (i in radii.indices) {
            val filled = on && level - 1 > i
            arcPaint.color = Color.argb(((if (filled) 255 else 80) * alpha).toInt(), 255, 255, 255)
            val r = radii[i]
            rect.set(cx - r, baseY - r, cx + r, baseY + r)
            canvas.drawArc(rect, 225f, 90f, false, arcPaint)
        }
        barPaint.color = Color.argb(((if (on) 255 else 80) * alpha).toInt(), 255, 255, 255)
        canvas.drawCircle(cx, baseY - 0.4f * dp, 1.9f * dp, barPaint)
    }

    /** Diagonal strike-through with a black gap around it so it reads on top of the logo. */
    private fun drawSlash(canvas: Canvas, cx: Float, cy: Float) {
        val r = 11 * dp
        val x1 = cx - r
        val y1 = cy - r
        val x2 = x1 + (2 * r) * slash
        val y2 = y1 + (2 * r) * slash
        canvas.drawLine(x1, y1, x2, y2, cutPaint)
        canvas.drawLine(x1, y1, x2, y2, slashPaint)
    }

    private companion object {
        const val MIN_W = 150f
        const val MAX_W = 260f
        const val LEAD_INSET = 28f
        const val TEXT_START = 26f
    }
}
