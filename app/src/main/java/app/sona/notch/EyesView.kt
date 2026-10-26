package app.sona.notch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Content of the compact pill. Idle: the mascot's eyes. They look where your finger is (while touching, dragging,
 * and for a few seconds after), wander on their own otherwise, blink at random (sometimes twice), and react with
 * moods (happy, sleepy, surprised, in love, dizzy, teary, wink, laugh, confused). While something is going on
 * ([primary]) the eyes give way to it: an equalizer for music, the app icon + time for a call or live activity,
 * a timer glyph + time for a timer/stopwatch. All motion is spring-driven.
 */
class EyesView(context: Context) : View(context) {

    enum class Reaction { SURPRISE, LOVE, DIZZY, WINK, TEARS, LAUGH, CONFUSED, HAPPY, ANGRY }

    private enum class Mood { DEFAULT, SLEEPY, ASLEEP, HAPPY, LAUGH, LOVE, DIZZY, ANGRY, SURPRISE, TEARS, CONFUSED, WINK }

    /** Short tap: open the app of the current activity (or the panel when idle). */
    var onTap: (() -> Unit)? = null

    /** Long press: expand the current activity (pet the mascot when idle). */
    var onLongPress: (() -> Unit)? = null

    /** Finger down/up on the pill; x is in this view's coordinates. Used for the press-squash of the pill. */
    var onPress: ((down: Boolean, x: Float) -> Unit)? = null

    var primary: Live? = null

    /** When set, the idle pill shows these (battery, clock) instead of the eyes. */
    var idleLeft: String? = null
    var idleRight: String? = null

    private val dp = resources.displayMetrics.density
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val lidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3.2f * dp
    }
    private val heartPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF5C8A.toInt() }
    private val spiralPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * dp
    }
    private val tearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6EC6FF.toInt() }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11.5f * dp
        typeface = Typeface.DEFAULT_BOLD
        fontFeatureSettings = "tnum"  // fixed-width digits: no jitter, and each digit can roll on its own
    }
    private val rect = RectF()
    private val path = Path()
    private val clip = Path()

    // ---- Springs ----
    private class Spring(var x: Float, var v: Float = 0f) {
        fun step(target: Float, dt: Float, k: Float = 260f, zeta: Float = 0.7f) {
            val d = 2f * zeta * sqrt(k)
            v += (-k * (x - target) - d * v) * dt
            x += v * dt
        }

        fun settled(target: Float) = abs(x - target) < 0.012f && abs(v) < 0.08f
    }

    private val gx = Spring(0f)
    private val gy = Spring(0f)
    private val lid = Spring(0f)
    private val slant = Spring(0f)
    private val sx = Spring(1f)
    private val sy = Spring(1f)
    private val arc = Spring(0f)
    private val heart = Spring(0f)
    private val spiral = Spring(0f)
    private val asymL = Spring(1f)
    private val asymR = Spring(1f)
    private val winkR = Spring(0f)
    private val all = listOf(gx, gy, lid, slant, sx, sy, arc, heart, spiral, asymL, asymR, winkR)

    // ---- Targets ----
    private var tGx = 0f
    private var tGy = 0f
    private var microX = 0f
    private var microY = 0f
    private var tLid = 0f
    private var tSlant = 0f
    private var tSx = 1f
    private var tSy = 1f
    private var tArc = 0f
    private var tHeart = 0f
    private var tSpiral = 0f
    private var tAsymL = 1f
    private var tAsymR = 1f
    private var tWinkR = 0f

    private var mood = Mood.DEFAULT
    private var moodStart = 0L
    private var moodUntil = 0L
    private var blinkStart = -10_000L
    private var lookUntil = 0L
    private var touching = false
    private var lastActive = SystemClock.uptimeMillis()
    private var lastOutside = 0L
    private var lastFrame = 0L
    private var lastTouchTime = 0L
    private var pending: Reaction? = null

    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var longFired = false
    private var moved = false
    private val longPress = Runnable {
        longFired = true
        onLongPress?.invoke()
    }

    // ---- Timers for life-like behaviour ----
    private val blinkTask = object : Runnable {
        override fun run() {
            if (mood != Mood.ASLEEP) {
                blinkStart = SystemClock.uptimeMillis()
                // One time in six it blinks twice, like a real blink reflex.
                if (Random.nextFloat() < 0.17f) handler.postDelayed({ blinkStart = SystemClock.uptimeMillis(); invalidate() }, 240)
                invalidate()
            }
            handler.postDelayed(this, Random.nextLong(2800, 5600))
        }
    }

    private val wanderTask = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (!touching && now >= lookUntil && mood != Mood.ASLEEP) {
                if (Random.nextFloat() < 0.3f) {
                    tGx = 0f
                    tGy = 0f
                } else {
                    tGx = Random.nextFloat() * 1.7f - 0.85f
                    tGy = Random.nextFloat() * 1f - 0.5f
                }
                invalidate()
            }
            handler.postDelayed(this, Random.nextLong(2000, 5200))
        }
    }

    /** Tiny involuntary gaze jitter (micro-saccades) so the eyes are never perfectly still. */
    private val microTask = object : Runnable {
        override fun run() {
            microX = (Random.nextFloat() - 0.5f) * 0.1f
            microY = (Random.nextFloat() - 0.5f) * 0.08f
            invalidate()
            handler.postDelayed(this, Random.nextLong(2200, 4800))
        }
    }

    /** Now and then the mascot does something cute on its own. */
    private val playTask = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (mood == Mood.DEFAULT && !touching && primary == null && now - lastActive < SLEEP_AFTER_MS) {
                react(
                    when (Random.nextInt(100)) {
                        in 0..44 -> Reaction.WINK
                        in 45..69 -> Reaction.HAPPY
                        in 70..84 -> Reaction.CONFUSED
                        else -> Reaction.LAUGH
                    },
                )
            }
            handler.postDelayed(this, Random.nextLong(22_000, 50_000))
        }
    }

    private val sleepTask = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (mood == Mood.DEFAULT && now - lastActive > SLEEP_AFTER_MS) setMood(Mood.SLEEPY, 2500)
            else if (mood == Mood.SLEEPY && moodUntil == 0L) Unit
            handler.postDelayed(this, 4000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.postDelayed(blinkTask, 1800)
        handler.postDelayed(wanderTask, 1200)
        handler.postDelayed(microTask, 600)
        handler.postDelayed(playTask, 15_000)
        handler.postDelayed(sleepTask, 5000)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    // ---- Public behaviour ----

    /** Queue a reaction that plays when the pill is back in view (e.g. hearts after the charger was plugged in). */
    fun queueReaction(r: Reaction) {
        pending = r
    }

    /** Called when returning to compact so the mascot looks awake and happy. */
    fun wake() {
        lastActive = SystemClock.uptimeMillis()
        val r = pending ?: Reaction.HAPPY
        pending = null
        handler.postDelayed({ react(r) }, 160)
    }

    fun react(r: Reaction) {
        lastActive = SystemClock.uptimeMillis()
        when (r) {
            Reaction.SURPRISE -> setMood(Mood.SURPRISE, 900)
            Reaction.LOVE -> setMood(Mood.LOVE, 2800)
            Reaction.DIZZY -> setMood(Mood.DIZZY, 2400)
            Reaction.WINK -> setMood(Mood.WINK, 750)
            Reaction.TEARS -> setMood(Mood.TEARS, 4200)
            Reaction.LAUGH -> setMood(Mood.LAUGH, 1900)
            Reaction.CONFUSED -> setMood(Mood.CONFUSED, 1700)
            Reaction.HAPPY -> setMood(Mood.HAPPY, 1000)
            Reaction.ANGRY -> setMood(Mood.ANGRY, 1800)
        }
    }

    /**
     * Look toward a point given in dp relative to the eye centre (x to the right, y down). The gaze holds there for a
     * few seconds after the finger leaves, so the mascot keeps looking at the last place you touched.
     */
    fun lookAt(dxDp: Float, dyDp: Float) {
        lastTouchTime = SystemClock.uptimeMillis()
        tGx = (dxDp / 55f).coerceIn(-1f, 1f)
        tGy = (dyDp / 38f).coerceIn(-1f, 1f)
        lookUntil = SystemClock.uptimeMillis() + LOOK_HOLD_MS
        lastActive = lookUntil - LOOK_HOLD_MS
        if (mood == Mood.ASLEEP || mood == Mood.SLEEPY) setMood(Mood.SURPRISE, 700)
        invalidate()
    }

    /** A touch happened somewhere else on screen (Android hides where): glance down toward the screen. */
    fun glanceOutside() {
        val now = SystemClock.uptimeMillis()
        if (now - lastOutside < 500 || mood == Mood.ASLEEP) return
        lastOutside = now
        tGx = (Random.nextFloat() - 0.5f) * 0.5f
        tGy = 1f
        lookUntil = now + 1100
        invalidate()
    }

    private fun setMood(m: Mood, holdMs: Long) {
        mood = m
        moodStart = SystemClock.uptimeMillis()
        moodUntil = if (holdMs > 0) moodStart + holdMs else 0L
        invalidate()
    }

    // ---- Touch ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cxv = width / 2f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                downX = event.x
                downY = event.y
                longFired = false
                moved = false
                lookAt((event.x - cxv) / dp, (event.y - 20 * dp) / dp)
                onPress?.invoke(true, event.x)
                handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                lookAt((event.x - cxv) / dp, (event.y - 20 * dp) / dp)
                if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                    moved = true
                    handler.removeCallbacks(longPress)
                }
            }
            MotionEvent.ACTION_UP -> {
                touching = false
                handler.removeCallbacks(longPress)
                onPress?.invoke(false, event.x)
                if (!longFired && !moved) {
                    performClick()
                    onTap?.invoke()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                touching = false
                handler.removeCallbacks(longPress)
                onPress?.invoke(false, event.x)
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    // ---- Per-frame simulation ----

    private fun applyMoodTargets(now: Long) {
        // Defaults
        tLid = 0f; tSlant = 0f; tSx = 1f; tSy = 1f; tArc = 0f; tHeart = 0f; tSpiral = 0f
        tAsymL = 1f; tAsymR = 1f; tWinkR = 0f
        when (mood) {
            Mood.DEFAULT -> Unit
            Mood.SLEEPY -> { tLid = 0.55f; tSy = 0.92f }
            Mood.ASLEEP -> { tLid = 0.93f; tSy = 0.55f }
            Mood.HAPPY -> tArc = 1f
            Mood.LAUGH -> tArc = 1f
            Mood.LOVE -> tHeart = 1f
            Mood.DIZZY -> tSpiral = 1f
            Mood.ANGRY -> { tSlant = 1f; tLid = 0.22f }
            Mood.SURPRISE -> { tSx = 1.18f; tSy = 1.4f }
            Mood.TEARS -> { tSlant = -0.9f; tLid = 0.28f; tSy = 0.95f }
            Mood.CONFUSED -> { tAsymL = 0.78f; tAsymR = 1.18f }
            Mood.WINK -> tWinkR = 1f
        }
        // The curious eye: the eye on the side you look toward grows a little.
    }

    private fun step(dt: Float, now: Long) {
        if (moodUntil != 0L && now > moodUntil) {
            // After sleepy comes asleep; every other mood returns to normal.
            if (mood == Mood.SLEEPY) setMood(Mood.ASLEEP, 0) else setMood(Mood.DEFAULT, 0)
        }
        applyMoodTargets(now)
        gx.step((tGx + microX).coerceIn(-1f, 1f), dt, k = 190f, zeta = 0.78f)
        gy.step((tGy + microY).coerceIn(-1f, 1f), dt, k = 190f, zeta = 0.78f)
        lid.step(tLid, dt)
        slant.step(tSlant, dt)
        sx.step(tSx, dt, k = 320f, zeta = 0.55f)  // lower damping = a springy pop when surprised
        sy.step(tSy, dt, k = 320f, zeta = 0.55f)
        arc.step(tArc, dt, k = 300f, zeta = 0.75f)
        heart.step(tHeart, dt, k = 300f, zeta = 0.6f)
        spiral.step(tSpiral, dt, k = 300f, zeta = 0.75f)
        asymL.step(tAsymL, dt)
        asymR.step(tAsymR, dt)
        winkR.step(tWinkR, dt, k = 380f, zeta = 0.8f)
    }

    private fun settled(): Boolean =
        gx.settled(tGx + microX) && gy.settled(tGy + microY) && lid.settled(tLid) && slant.settled(tSlant) &&
            sx.settled(tSx) && sy.settled(tSy) && arc.settled(tArc) && heart.settled(tHeart) &&
            spiral.settled(tSpiral) && asymL.settled(tAsymL) && asymR.settled(tAsymR) && winkR.settled(tWinkR) &&
            mood !in ANIMATED_MOODS && SystemClock.uptimeMillis() - blinkStart > BLINK_MS + 50

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val cx = width / 2f
        val cy = 20 * dp  // aligned with the camera hole
        val offset = 28 * dp

        var animating = false
        when (val p = primary) {
            null -> {
                val left = idleLeft
                val right = idleRight
                if (left != null && right != null) {
                    drawSideText(canvas, left, cx - offset - 4 * dp, cy, Color.WHITE, 0)
                    drawSideText(canvas, right, cx + offset + 5 * dp, cy, Color.WHITE, 1)
                    animating = odo[1].rolling(now)
                } else {
                    val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0.001f, 0.033f)
                    lastFrame = now
                    step(dt, now)
                    drawEyes(canvas, cx, cy, offset, now)
                    animating = !settled()
                }
            }
            is Live.Call -> {
                drawAppIcon(canvas, p.info.icon, cx - offset, cy)
                drawSideText(canvas, p.text, cx + offset + 5 * dp, cy, CALL_GREEN, 1)
                animating = odo[1].rolling(now)
            }
            is Live.App -> {
                drawAppIcon(canvas, p.info.icon, cx - offset, cy)
                drawSideText(canvas, p.text, cx + offset + 5 * dp, cy, Color.WHITE, 1)
                animating = odo[1].rolling(now)
            }
            is Live.Clock -> {
                val d = context.getDrawable(R.drawable.ic_timer)?.mutate()
                if (d != null) {
                    d.setTint(if (p.urgent) URGENT_RED else TIMER_ORANGE)
                    val half = 10 * dp
                    d.setBounds((cx - offset - half).toInt(), (cy - half).toInt(), (cx - offset + half).toInt(), (cy + half).toInt())
                    d.draw(canvas)
                }
                drawSideText(canvas, p.text, cx + offset + 5 * dp, cy, if (p.urgent) URGENT_RED else TIMER_ORANGE, 1)
                animating = odo[1].rolling(now)
            }
            Live.Media -> {
                drawBars(canvas, cx - offset, cy)
                drawBars(canvas, cx + offset, cy)
                animating = true
            }
            Live.Torch -> {
                val d = context.getDrawable(R.drawable.ic_torch)?.mutate()
                if (d != null) {
                    d.setTint(0xFFFFD60A.toInt())
                    val half = 10 * dp
                    d.setBounds((cx - offset - half).toInt(), (cy - half).toInt(), (cx - offset + half).toInt(), (cy + half).toInt())
                    d.draw(canvas)
                }
                drawSideText(canvas, "On", cx + offset + 5 * dp, cy, 0xFFFFD60A.toInt(), 1)
            }
        }
        if (animating) {
            // Full frame rate only while it is being touched or reacting; calm idle motion is drawn at ~30 fps.
            val fast = touching || mood != Mood.DEFAULT || now - lastTouchTime < 1500
            if (primary == null && !fast) postInvalidateDelayed(33) else if (primary == Live.Media) postInvalidateDelayed(33) else postInvalidateOnAnimation()
        } else {
            lastFrame = 0L
        }
    }

    // ---- Eyes ----

    private fun drawEyes(canvas: Canvas, cx: Float, cy: Float, offset: Float, now: Long) {
        val t = (now - moodStart) / 1000f
        val blinkP = ((now - blinkStart) / BLINK_MS.toFloat()).coerceIn(0f, 1f)
        val blink = if (blinkP >= 1f) 0f else 1f - abs(2f * blinkP - 1f)

        // Moods with their own motion: laughing bounces, confusion shakes, dizziness spins.
        var shakeX = 0f
        var shakeY = 0f
        when (mood) {
            Mood.LAUGH -> shakeY = sin(t * 38f) * 1.4f * dp
            Mood.CONFUSED -> shakeX = sin(t * 30f) * 1.6f * dp * (1f - (t / 1.7f).coerceIn(0f, 1f))
            else -> Unit
        }

        for (side in intArrayOf(-1, 1)) {
            val eyeScaleY = (if (side < 0) asymL.x else asymR.x) *
                (1f + 0.14f * abs(gx.x) * (if (gx.x * side > 0f) 1f else 0f))  // curious: the eye you look toward grows
            val w = EYE_W * dp * sx.x
            val h = EYE_H * dp * sy.x * eyeScaleY * (1f - 0.92f * blink)
            val ex = cx + side * offset + gx.x * 5.4f * dp + shakeX
            val ey = cy + gy.x * 3.4f * dp + shakeY

            val wink = if (side > 0) winkR.x else 0f
            val round = (1f - arc.x - heart.x - spiral.x - wink).coerceIn(0f, 1f)
            if (round > 0.02f) drawRoundEye(canvas, side, ex, ey, w, h, round)
            val arcAmount = (arc.x + wink).coerceIn(0f, 1f)
            if (arcAmount > 0.02f) {
                arcPaint.alpha = (255 * arcAmount).toInt()
                rect.set(ex - EYE_W * dp / 2f, ey - EYE_W * dp / 2f, ex + EYE_W * dp / 2f, ey + EYE_W * dp / 2f)
                canvas.drawArc(rect, 200f, 140f, false, arcPaint)
            }
            if (heart.x > 0.02f) drawHeart(canvas, ex, ey, heart.x, t)
            if (spiral.x > 0.02f) drawSpiral(canvas, ex, ey, spiral.x, t, side)
            if (mood == Mood.TEARS && side > 0) drawTear(canvas, ex, ey + h / 2f, now)
        }
        if (mood == Mood.ASLEEP || mood == Mood.SLEEPY) drawZ(canvas, cx + offset + 12 * dp, cy - 5 * dp, now)
    }

    /** A rounded eye clipped by an eyelid: sleepy lids drop from the top, angry/sad lids are slanted. */
    private fun drawRoundEye(canvas: Canvas, side: Int, ex: Float, ey: Float, w: Float, h: Float, alpha: Float) {
        val lidFrac = lid.x.coerceIn(0f, 1f)
        val top = ey - h / 2f
        eyePaint.alpha = (255 * alpha).toInt()
        rect.set(ex - w / 2f, top, ex + w / 2f, ey + h / 2f)
        canvas.save()
        clip.reset()
        clip.addRoundRect(rect, w / 2f, w / 2f, Path.Direction.CW)
        canvas.clipPath(clip)
        canvas.drawRect(rect, eyePaint)
        if (lidFrac > 0.01f || abs(slant.x) > 0.01f) {
            // Positive slant = angry (inner side lower); negative = sad (outer side lower).
            val inner = if (side < 0) 1 else -1  // which x-direction is "toward the nose"
            val baseY = top + lidFrac * h
            val innerY = baseY + slant.x * 0.42f * h
            val outerY = baseY - slant.x * 0.2f * h
            val xIn = ex + inner * w / 2f
            val xOut = ex - inner * w / 2f
            path.reset()
            path.moveTo(xOut - 1f, top - 2f)
            path.lineTo(xIn + inner * 1f, top - 2f)
            path.lineTo(xIn + inner * 1f, innerY)
            path.lineTo(xOut - 1f, outerY)
            path.close()
            canvas.drawPath(path, lidPaint)
        }
        canvas.restore()
    }

    private fun drawHeart(canvas: Canvas, cx: Float, cy: Float, amount: Float, t: Float) {
        val beat = 1f + 0.14f * sin(t * 11f)
        val s = 6.6f * dp * beat * amount
        path.reset()
        path.moveTo(cx, cy + s * 0.9f)
        path.cubicTo(cx - s * 1.3f, cy + s * 0.2f, cx - s * 0.9f, cy - s * 0.9f, cx, cy - s * 0.3f)
        path.cubicTo(cx + s * 0.9f, cy - s * 0.9f, cx + s * 1.3f, cy + s * 0.2f, cx, cy + s * 0.9f)
        path.close()
        heartPaint.alpha = (255 * amount).toInt()
        canvas.drawPath(path, heartPaint)
    }

    private fun drawSpiral(canvas: Canvas, cx: Float, cy: Float, amount: Float, t: Float, side: Int) {
        val maxR = 7.4f * dp * amount
        val turns = 13f
        val rot = t * 9f * -side
        path.reset()
        var th = 0f
        var first = true
        while (th <= turns) {
            val r = maxR * th / turns
            val x = cx + r * cos(th + rot)
            val y = cy + r * sin(th + rot)
            if (first) path.moveTo(x, y) else path.lineTo(x, y)
            first = false
            th += 0.35f
        }
        spiralPaint.alpha = (255 * amount).toInt()
        canvas.drawPath(path, spiralPaint)
    }

    private fun drawTear(canvas: Canvas, x: Float, y: Float, now: Long) {
        val phase = (now % 1500) / 1500f
        tearPaint.alpha = (255 * (1f - phase * 0.85f)).toInt()
        val ty = y + phase * 11 * dp
        rect.set(x - 1.7f * dp, ty - 2.4f * dp, x + 1.7f * dp, ty + 2.6f * dp)
        canvas.drawOval(rect, tearPaint)
    }

    private fun drawZ(canvas: Canvas, x: Float, y: Float, now: Long) {
        val phase = (now % 2200) / 2200f
        textPaint.color = Color.WHITE
        val saved = textPaint.textSize
        textPaint.textSize = (7f + phase * 4f) * dp
        textPaint.alpha = (255 * (1f - phase)).toInt()
        canvas.drawText("z", x + phase * 6 * dp, y - phase * 9 * dp, textPaint)
        textPaint.textSize = saved
        textPaint.alpha = 255
    }

    // ---- Side content (call, timer, idle clock) ----

    /** Odometer: each digit that changes rolls up while the new one rolls in from below. */
    private class Odometer {
        var prev = ""
        var cur = ""
        var start = 0L

        fun set(text: String, now: Long) {
            if (text != cur) {
                prev = cur
                cur = text
                start = now
            }
        }

        fun rolling(now: Long) = now - start < ROLL_MS
    }

    private val odo = arrayOf(Odometer(), Odometer())

    /**
     * Text in the space between the camera hole and the pill's edge (6dp clear of the edge), shrunk to fit when long.
     * [slot] 0 = left of the camera, 1 = right.
     */
    private fun drawSideText(canvas: Canvas, text: String, centerX: Float, cy: Float, color: Int, slot: Int) {
        val now = SystemClock.uptimeMillis()
        val o = odo[slot]
        o.set(text, now)
        textPaint.color = color
        textPaint.textSize = 11.5f * dp
        val room = 36f * dp
        var total = textPaint.measureText(text)
        if (total > room) {
            textPaint.textSize = maxOf(8.5f * dp, 11.5f * dp * room / total)
            total = textPaint.measureText(text)
        }
        val p = ((now - o.start) / ROLL_MS.toFloat()).coerceIn(0f, 1f)
        val eased = 1f - (1f - p) * (1f - p) * (1f - p)
        val baseY = cy + textPaint.textSize * 0.35f
        val lineH = textPaint.textSize * 1.1f
        var x = centerX - total / 2f
        for (i in text.indices) {
            val ch = text[i].toString()
            val w = textPaint.measureText(ch)
            val changed = p < 1f && o.prev.length == text.length && o.prev[i] != text[i]
            if (changed) {
                textPaint.alpha = (255 * (1f - eased)).toInt()
                canvas.drawText(o.prev[i].toString(), x, baseY - eased * lineH, textPaint)
                textPaint.alpha = (255 * eased).toInt()
                canvas.drawText(ch, x, baseY + (1f - eased) * lineH, textPaint)
                textPaint.alpha = 255
            } else {
                canvas.drawText(ch, x, baseY, textPaint)
            }
            x += w
        }
    }

    /** App icon clipped to a rounded square, 22dp. */
    private fun drawAppIcon(canvas: Canvas, icon: Drawable?, cx: Float, cy: Float) {
        val d = icon ?: return
        val half = 11 * dp
        canvas.save()
        rect.set(cx - half, cy - half, cx + half, cy + half)
        clip.reset()
        clip.addRoundRect(rect, 6 * dp, 6 * dp, Path.Direction.CW)
        canvas.clipPath(clip)
        d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        d.draw(canvas)
        canvas.restore()
    }

    /** Four bars moving up and down with different phases and speeds. */
    private fun drawBars(canvas: Canvas, cx: Float, cy: Float) {
        val barW = 3 * dp
        val gap = 2.5f * dp
        val total = BARS * barW + (BARS - 1) * gap
        val t = SystemClock.uptimeMillis() / 1000f
        for (i in 0 until BARS) {
            val v = 0.5f + 0.5f * sin(t * SPEEDS[i] + PHASES[i])
            val h = (4 + 12 * v) * dp
            val x = cx - total / 2f + i * (barW + gap)
            rect.set(x, cy - h / 2f, x + barW, cy + h / 2f)
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, barPaint)
        }
    }

    private companion object {
        const val EYE_W = 12f
        const val EYE_H = 16f
        const val BLINK_MS = 170L
        const val ROLL_MS = 260L
        const val LOOK_HOLD_MS = 3200L
        const val SLEEP_AFTER_MS = 60_000L
        const val BARS = 4
        val SPEEDS = floatArrayOf(7.2f, 9.1f, 6.3f, 8.4f)
        val PHASES = floatArrayOf(0f, 1.7f, 3.1f, 4.6f)
        val CALL_GREEN = 0xFF34C759.toInt()
        val TIMER_ORANGE = 0xFFFF9F0A.toInt()
        val URGENT_RED = 0xFFFF453A.toInt()
        val ANIMATED_MOODS = setOf(Mood.LAUGH, Mood.CONFUSED, Mood.LOVE, Mood.DIZZY, Mood.TEARS, Mood.ASLEEP, Mood.SLEEPY)
    }
}
