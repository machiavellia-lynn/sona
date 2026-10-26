package app.sona.notch

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.min

/**
 * The pill at the top edge transforms into a pop-up and back:
 *  - EVENT: the pill widens to fit its content (Wi-Fi, Bluetooth, charging, ...): logo left of the camera, detail right.
 *  - BANNER: a 335dp card for app notifications, calls and live activities; swipe down (or long-press) expands it
 *    to show its buttons (Reply, Mark as read, Mute, ...).
 *  - PANEL: a 335x150dp card the user opens (music, time).
 * Cards sit 43dp from the top, so the header is never covered. When two things are going on, the second one splits
 * off the pill as a small bubble (with a gooey neck while it separates); swipe sideways to swap them.
 * Notifications and events that arrive while a pop-up is showing wait in a queue.
 *
 * This view lives in a fixed-size, non-touchable drawing window; touches go through a separate window
 * ([TouchProxy]) whose size follows the interactive area via [onResizeTouch]. The drawing window is never
 * resized, so there are no visible resize artifacts.
 */
class NotchLayout(
    context: Context,
    private val onResizeTouch: (width: Int, height: Int) -> Unit,
    private val onOpenListenerSettings: () -> Unit,
) : FrameLayout(context) {

    enum class Mode { COMPACT, EVENT, BANNER, PANEL }

    /** What the BANNER card is showing; only NOTIF is dismissed by time alone. */
    private enum class Card { NOTIF, CALL, LIVE }

    var mode = Mode.COMPACT
        private set

    private val eyes = EyesView(context)
    private val bubble = BubbleView(context)
    private val banner = BannerView(context)
    private val panel = PanelView(context)
    private val event = EventView(context)

    private val pillW = context.dp(PILL_W).toFloat()
    private val pillH = context.dp(PILL_H).toFloat()
    private val eventMaxW = context.dp(EVENT_MAX_W).toFloat()
    private val cardW = context.dp(CARD_W)
    private val cardTop = context.dp(CARD_TOP).toFloat()
    private val cardRadius = context.dp(CARD_RADIUS).toFloat()
    private val bubbleGap = context.dp(BUBBLE_GAP).toFloat()
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val rect = RectF()
    private val handler = Handler(Looper.getMainLooper())
    private var anim: ValueAnimator? = null
    private var heightAnim: ValueAnimator? = null
    private var bubbleAnim: ValueAnimator? = null
    private var currentItem: NotifItem? = null

    /** 0 = card closed, 1 = fully open (may exceed 1 during the spring's overshoot). */
    private var reveal = 0f
    private var cardH = context.dp(PANEL_H).toFloat()

    /** 0 = normal pill, 1 = pill widened for a system event pop-up; [eventW] is how wide that is. */
    private var eventT = 0f
    private var eventW = eventMaxW

    /** 0..1: the second-activity bubble splitting off the pill. */
    private var bubbleT = 0f
    private var bubbleWanted = false
    private var swapped = false

    /** How many activities were showing at the last tick, and the privacy state drawn (to skip needless redraws). */
    private var activeCount = 0
    private var privacyKey = 0

    /** Press-squash of the pill (0..1) and which side was touched (-1 left .. 1 right); rubber-band stretch in px. */
    private var pressT = 0f
    private var pressSide = 0f
    private var stretch = 0f
    private var pressAnim: ValueAnimator? = null
    private var stretchAnim: ValueAnimator? = null
    private var dragStartY = Float.NaN

    private var card = Card.NOTIF
    private var liveCardInfo: LiveInfo? = null
    private var bannerExpanded = false
    private var bannerBaseH = BANNER_H
    private var lastRingingKey: String? = null
    private var wasRinging = false
    private var locked = false

    private val bannerQueue = ArrayDeque<NotifItem>()
    private val eventQueue = ArrayDeque<EventItem>()

    private val autoCollapse = Runnable { setMode(Mode.COMPACT) }

    /** Gestures: swipe up closes a pop-up, swipe down expands a card or the pill, swipe sideways swaps activities. */
    private val swipe = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val vertical = abs(vy) > abs(vx)
                when {
                    mode != Mode.COMPACT && vertical && vy < -SWIPE_V -> setMode(Mode.COMPACT)
                    mode == Mode.BANNER && vertical && vy > SWIPE_V -> setBannerExpanded(true)
                    mode == Mode.COMPACT && vertical && vy > SWIPE_V -> expand(eyes.primary)
                    mode == Mode.BANNER && card == Card.NOTIF && !vertical && abs(vx) > SWIPE_V -> throwBanner(if (vx > 0) 1 else -1)
                    mode == Mode.COMPACT && !vertical && abs(vx) > SWIPE_V && bubbleWanted -> {
                        swapped = !swapped
                        context.haptic()
                        tick()
                    }
                    // With just music playing, a sideways swipe on the pill skips the track.
                    mode == Mode.COMPACT && !vertical && abs(vx) > SWIPE_V && eyes.primary == Live.Media -> {
                        context.haptic()
                        if (vx < 0) MediaMonitor.next() else MediaMonitor.prev()
                    }
                    // Idle: a sideways swipe makes the mascot dizzy-confused.
                    mode == Mode.COMPACT && !vertical && abs(vx) > SWIPE_V && eyes.primary == null ->
                        eyes.react(EyesView.Reaction.CONFUSED)
                    else -> return false
                }
                return true
            }
        },
    )

    init {
        setWillNotDraw(false)
        addView(eyes, LayoutParams(pillW.toInt(), pillH.toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        addView(bubble, LayoutParams(pillH.toInt(), pillH.toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        addView(event, LayoutParams(eventMaxW.toInt(), pillH.toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        addView(banner, cardParams(BANNER_H))
        addView(panel, cardParams(PANEL_H))
        bubble.translationX = pillW / 2f + bubbleGap + pillH / 2f
        bubble.visibility = INVISIBLE
        banner.visibility = GONE
        panel.visibility = GONE
        event.visibility = INVISIBLE
        event.setOnClickListener { setMode(Mode.COMPACT) }
        applyReveal()

        eyes.onTap = { activate(eyes.primary, fromPill = true) }
        eyes.onPress = { down, x -> pressPill(down, x) }
        eyes.onLongPress = { expand(eyes.primary) }
        bubble.setOnClickListener { activate(bubble.live, fromPill = false) }
        panel.onOpenListenerSettings = {
            setMode(Mode.COMPACT)
            onOpenListenerSettings()
        }
        banner.onTap = {
            val target = when (card) {
                Card.CALL -> CallMonitor.current?.open
                Card.LIVE -> liveCardInfo?.open
                Card.NOTIF -> currentItem?.intent
            }
            target?.let { openIntent(it) }
            setMode(Mode.COMPACT)
        }
        banner.onLongPress = {
            if (banner.hasActions) {
                context.haptic(firm = true)
                setBannerExpanded(!bannerExpanded)
            }
        }
        banner.onEndCall = { CallMonitor.hangUp() }
        banner.onAnswerCall = { CallMonitor.answer() }
    }

    private fun cardParams(heightDp: Float) =
        LayoutParams(cardW, context.dp(heightDp), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = cardTop.toInt()
        }

    private fun bannerTargetH() = context.dp(if (bannerExpanded) BANNER_EXPANDED_H else bannerBaseH).toFloat()

    /** Interactive area for each mode (also the size of the touch window). */
    fun sizeFor(m: Mode): Pair<Int, Int> = when (m) {
        Mode.COMPACT -> (if (bubbleWanted) pillW + 2 * (bubbleGap + pillH) else pillW).toInt() to pillH.toInt()
        Mode.EVENT -> eventW.toInt() to pillH.toInt()
        Mode.BANNER -> cardW to (cardTop + bannerTargetH()).toInt()
        Mode.PANEL -> cardW to (cardTop + context.dp(PANEL_H)).toInt()
    }

    private fun cardView(m: Mode): View? = when (m) {
        Mode.COMPACT, Mode.EVENT -> null
        Mode.BANNER -> banner
        Mode.PANEL -> panel
    }

    /** Screen locked: the notch disappears completely (nothing drawn, no touches captured). */
    fun setLocked(value: Boolean) {
        if (value == locked) return
        locked = value
        if (value) {
            anim?.cancel()
            handler.removeCallbacks(autoCollapse)
            mode = Mode.COMPACT
            reveal = 0f
            eventT = 0f
            card = Card.NOTIF
            bannerExpanded = false
            bannerQueue.clear()
            eventQueue.clear()
            banner.visibility = GONE
            panel.visibility = GONE
            applyReveal()
            visibility = INVISIBLE
            onResizeTouch(1, 1)
        } else {
            visibility = VISIBLE
            val (w, h) = sizeFor(Mode.COMPACT)
            onResizeTouch(w, h)
        }
    }

    // ---- Pop-ups and their queue ----

    fun showBanner(item: NotifItem) {
        if (locked || Prefs.quietNow()) return
        // A new message from the conversation already on screen updates that pop-up instead of stacking another.
        val cur = currentItem
        if (mode == Mode.BANNER && card == Card.NOTIF && cur != null && cur.convKey == item.convKey) {
            presentBanner(item.copy(count = cur.count + 1))
            return
        }
        // Anything else on screen finishes first; the new one waits its turn (merged with a waiting one of the same sender).
        if (mode != Mode.COMPACT) {
            val i = bannerQueue.indexOfFirst { it.convKey == item.convKey }
            if (i >= 0) bannerQueue[i] = item.copy(count = bannerQueue[i].count + 1) else bannerQueue.addLast(item)
            while (bannerQueue.size > MAX_QUEUE) bannerQueue.removeFirst()
            refreshQueueLabel()
            return
        }
        presentBanner(item)
    }

    private fun refreshQueueLabel() {
        if (mode == Mode.BANNER && card == Card.NOTIF) banner.setWaiting(bannerQueue.size, currentItem?.count ?: 1)
    }

    private fun presentBanner(item: NotifItem) {
        currentItem = item
        card = Card.NOTIF
        eyes.queueReaction(EyesView.Reaction.SURPRISE)
        bannerBaseH = banner.preferredHeightDp(item)
        banner.bind(item)
        banner.setWaiting(bannerQueue.size, item.count)
        banner.setActions(item.actions.map { actionSpec(it, item.title) })
        bannerExpanded = false
        banner.setExpanded(false)
        context.haptic()
        if (mode == Mode.BANNER) {
            // Updated in place (same conversation): just fit the card to the new text.
            val (w, h) = sizeFor(Mode.BANNER)
            onResizeTouch(w, h)
            animateCardHeightTo(bannerTargetH())
        }
        setMode(Mode.BANNER)
        handler.removeCallbacks(autoCollapse)
        handler.postDelayed(autoCollapse, Prefs.bannerMs())
    }

    private fun animateCardHeightTo(to: Float) {
        heightAnim?.cancel()
        heightAnim = ValueAnimator.ofFloat(cardH, to).apply {
            duration = 480
            interpolator = SpringInterpolator(0.8f, 10f)
            addUpdateListener {
                cardH = it.animatedValue as Float
                applyReveal()
            }
            start()
        }
    }

    /** System event pop-up: the pill widens to fit, logo left and detail right. */
    fun showEvent(item: EventItem) {
        if (locked || Prefs.quietNow()) return
        if (mode != Mode.COMPACT && mode != Mode.EVENT) {
            eventQueue.addLast(item)
            while (eventQueue.size > MAX_EVENT_QUEUE) eventQueue.removeFirst()
            return
        }
        presentEvent(item)
    }

    private fun presentEvent(item: EventItem) {
        event.bind(item)
        eventW = event.widthFor(item)
        eyes.queueReaction(
            item.reaction ?: when {
                item.kind == EventItem.KIND_CHARGE -> EyesView.Reaction.LOVE
                !item.on -> EyesView.Reaction.CONFUSED
                else -> EyesView.Reaction.HAPPY
            },
        )
        context.haptic()
        if (mode == Mode.EVENT) {
            val (w, h) = sizeFor(Mode.EVENT)
            onResizeTouch(w, h)
            invalidate()
        }
        setMode(Mode.EVENT)
        handler.removeCallbacks(autoCollapse)
        handler.postDelayed(autoCollapse, Prefs.eventMs())
    }

    /** After a pop-up has fully closed, show whatever was waiting. */
    private fun drain() {
        if (locked || mode != Mode.COMPACT) return
        bannerQueue.removeFirstOrNull()?.let { presentBanner(it); return }
        eventQueue.removeFirstOrNull()?.let { presentEvent(it) }
    }

    private fun openCall() {
        val info = CallMonitor.current ?: return
        card = Card.CALL
        bannerBaseH = BANNER_H
        wasRinging = info.ringing
        banner.bindCall(info, CallMonitor.statusText())
        val specs = info.extras.map { actionSpec(it, info.name) }
        banner.setActions(specs)
        bannerExpanded = specs.isNotEmpty() && !info.ringing
        banner.setExpanded(bannerExpanded)
        setMode(Mode.BANNER)
        handler.removeCallbacks(autoCollapse)
        // A ringing call card stays until it is answered or declined.
        if (!info.ringing) handler.postDelayed(autoCollapse, PANEL_MS)
    }

    private fun openLive(info: LiveInfo) {
        card = Card.LIVE
        bannerBaseH = BANNER_H
        liveCardInfo = info
        banner.bindLive(info, LiveMonitor.text(info))
        banner.setActions(emptyList())
        bannerExpanded = false
        banner.setExpanded(false)
        setMode(Mode.BANNER)
        handler.removeCallbacks(autoCollapse)
        handler.postDelayed(autoCollapse, PANEL_MS)
    }

    private fun openPanel(page: Int, timeMode: Int? = null) {
        panel.goTo(page, timeMode)
        setMode(Mode.PANEL)
    }

    /** Expands/collapses the BANNER card to show or hide its buttons. */
    private fun setBannerExpanded(expanded: Boolean) {
        if (mode != Mode.BANNER || !banner.hasActions || expanded == bannerExpanded) return
        bannerExpanded = expanded
        banner.setExpanded(expanded)
        handler.removeCallbacks(autoCollapse)
        handler.postDelayed(autoCollapse, PANEL_MS)
        val (w, h) = sizeFor(Mode.BANNER)
        onResizeTouch(w, h)
        animateCardHeightTo(bannerTargetH())
    }

    /** A notification button: inline replies open the reply screen, everything else fires its PendingIntent. */
    private fun actionSpec(a: NotifAction, title: String): ActionSpec {
        val reply = a.remoteInput != null
        return ActionSpec(a.label, if (reply) Ui.BLUE else 0x33FFFFFF) {
            if (reply) {
                ReplyHolder.action = a
                ReplyHolder.title = title
                context.startActivity(Intent(context, ReplyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                a.intent?.let { openIntent(it) }
            }
            setMode(Mode.COMPACT)
        }
    }

    /** Tap: open the app behind the activity (the panel when idle); tapping an open pop-up closes it. */
    private fun activate(live: Live?, fromPill: Boolean) {
        if (mode != Mode.COMPACT) {
            setMode(Mode.COMPACT)
            return
        }
        when (live) {
            is Live.Call -> live.info.open?.let { openIntent(it) } ?: openCall()
            is Live.App -> live.info.open?.let { openIntent(it) }
            Live.Media -> MediaMonitor.sessionActivity?.let { openIntent(it) } ?: openPanel(0)
            is Live.Clock -> openPanel(1)
            Live.Torch -> Flashlight.toggle(context)
            null -> if (fromPill) openPanel(0)
        }
    }

    /** Long press / swipe down: expand the activity into its card. */
    private fun expand(live: Live?) {
        if (mode != Mode.COMPACT) return
        context.haptic(firm = true)
        when (live) {
            is Live.Call -> openCall()
            is Live.App -> openLive(live.info)
            Live.Media -> openPanel(0)
            is Live.Clock -> openPanel(1)
            Live.Torch -> openPanel(2)
            // Long-pressing the idle pill pets the mascot.
            null -> eyes.react(EyesView.Reaction.LOVE)
        }
    }

    // ---- State ----

    /** Everything going on, most important first: call, live activity, timer/stopwatch, music. */
    private fun currentLives(): List<Live> {
        val out = ArrayList<Live>(4)
        CallMonitor.current?.let { if (Prefs.bool(Prefs.CALL_INDICATOR)) out += Live.Call(it, CallMonitor.statusText()) }
        if (Prefs.bool(Prefs.LIVE_ACTIVITIES)) LiveMonitor.latest()?.let { out += Live.App(it, LiveMonitor.text(it)) }
        when {
            Timers.tRunning -> out += Live.Clock(formatDuration(Timers.tLeft()), Timers.tLeft() <= 10_000L)
            Timers.swRunning -> out += Live.Clock(formatDuration(Timers.swElapsed()))
        }
        if (Flashlight.on) out += Live.Torch
        if (MediaMonitor.state.playing) out += Live.Media
        if (out.size < 2) swapped = false
        if (swapped && out.size >= 2) {
            val first = out[0]
            out[0] = out[1]
            out[1] = first
        }
        return out
    }

    /** false = nothing is happening, so the service can poll once a second instead of four times. */
    fun needsFastTick() = mode != Mode.COMPACT || activeCount > 0 || bubbleT > 0.01f

    fun tick() {
        val lives = currentLives()
        eyes.primary = lives.getOrNull(0)
        // Optionally the idle pill shows battery and clock instead of the eyes.
        val idleText = lives.isEmpty() && Prefs.bool(Prefs.IDLE_CLOCK)
        eyes.idleLeft = if (idleText) "${context.getSystemService(android.os.BatteryManager::class.java)
            .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)}%" else null
        eyes.idleRight = if (idleText) java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()) else null
        bubble.live = lives.getOrNull(1)
        setBubbleWanted(lives.size >= 2)
        activeCount = lives.size
        // Redraw only what actually changes with time; the idle eyes run their own animation loop.
        if (lives.isNotEmpty() || eyes.idleLeft != null) eyes.invalidate()
        if (lives.size >= 2) bubble.invalidate()
        val pk = (if (PrivacyMonitor.camera) 1 else 0) + (if (PrivacyMonitor.mic) 2 else 0)
        if (pk != privacyKey) {
            privacyKey = pk
            invalidate()
        }

        val call = CallMonitor.current
        if (call == null) {
            lastRingingKey = null
        } else if (call.ringing && call.key != lastRingingKey && !locked && mode != Mode.PANEL) {
            lastRingingKey = call.key
            context.haptic()
            openCall()
        }

        if (mode == Mode.PANEL) panel.update()
        if (mode == Mode.BANNER) when (card) {
            Card.CALL -> when {
                // The call ended while its card was open: close it instead of leaving a stale card.
                call == null -> setMode(Mode.COMPACT)
                else -> {
                    banner.bindCall(call, CallMonitor.statusText())
                    if (wasRinging && !call.ringing) {
                        // Answered: let the card show the running timer for a moment, then tuck away.
                        handler.removeCallbacks(autoCollapse)
                        handler.postDelayed(autoCollapse, 3000)
                    }
                    wasRinging = call.ringing
                }
            }
            Card.LIVE -> liveCardInfo?.let { banner.bindLive(it, LiveMonitor.text(it)) }
            Card.NOTIF -> Unit
        }
    }

    private fun setBubbleWanted(want: Boolean) {
        if (want == bubbleWanted) return
        bubbleWanted = want
        if (mode == Mode.COMPACT && !locked) {
            val (w, h) = sizeFor(Mode.COMPACT)
            onResizeTouch(w, h)
        }
        bubbleAnim?.cancel()
        val from = bubbleT
        bubbleAnim = ValueAnimator.ofFloat(from, if (want) 1f else 0f).apply {
            duration = 620
            interpolator = SpringInterpolator(if (want) 0.62f else 1f, 10f)
            addUpdateListener {
                bubbleT = it.animatedValue as Float
                applyReveal()
            }
            start()
        }
    }

    /** User-opened cards (panel, call, live) are closed by touches outside; time-limited pop-ups are not. */
    private fun userCard() = mode == Mode.BANNER && card != Card.NOTIF

    private fun userOpened() =
        mode == Mode.PANEL ||
            (userCard() && !(card == Card.CALL && CallMonitor.current?.ringing == true))

    fun setMode(next: Mode) {
        if (next == mode || !isAttachedToWindow) return
        mode = next
        handler.removeCallbacks(autoCollapse)
        if (next == Mode.PANEL) handler.postDelayed(autoCollapse, PANEL_MS)
        if (next == Mode.COMPACT) {
            card = Card.NOTIF
            bannerExpanded = false
            eyes.wake()
        }

        val incoming = cardView(next)
        // While closing, the card's content stays visible until the animation ends; otherwise only the target card shows.
        if (incoming != null) {
            listOf(banner, panel).forEach { it.visibility = if (it === incoming) VISIBLE else GONE }
            if (next == Mode.PANEL) panel.update()
        }
        val (tw, th) = sizeFor(next)
        if (next != Mode.COMPACT) onResizeTouch(tw, th)

        val fromReveal = reveal
        val fromH = cardH
        val fromEvent = eventT
        val toReveal = if (next == Mode.BANNER || next == Mode.PANEL) 1f else 0f
        val toEvent = if (next == Mode.EVENT) 1f else 0f
        val toH = when (next) {
            Mode.BANNER -> bannerTargetH()
            Mode.PANEL -> context.dp(PANEL_H).toFloat()
            Mode.COMPACT, Mode.EVENT -> cardH
        }
        val closing = next == Mode.COMPACT

        heightAnim?.cancel()
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            // Opening uses a spring with a gentle overshoot; closing a critically damped spring (no bounce),
            // a little shorter. Durations are deliberately long enough for the motion to read.
            duration = when {
                closing -> 520
                next == Mode.EVENT -> 640
                else -> 700
            }
            interpolator = if (closing) SpringInterpolator(1f, 9f) else SpringInterpolator(0.74f, 9.5f)
            addUpdateListener {
                val t = it.animatedValue as Float
                reveal = fromReveal + (toReveal - fromReveal) * t
                cardH = fromH + (toH - fromH) * t
                eventT = fromEvent + (toEvent - fromEvent) * t
                applyReveal()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    // The spring interpolator stops at ~0.999; make sure the final values are exact.
                    reveal = toReveal
                    cardH = toH
                    eventT = toEvent
                    applyReveal()
                    if (!closing) return
                    banner.visibility = GONE
                    panel.visibility = GONE
                    val (w, h) = sizeFor(Mode.COMPACT)
                    onResizeTouch(w, h)
                    handler.postDelayed({ drain() }, 320)
                }
            })
            start()
        }
    }

    /** Called by [TouchProxy] when the user touches outside the interactive area. */
    fun onOutside() {
        eyes.glanceOutside()
        if (userOpened()) setMode(Mode.COMPACT)
    }

    /** Lets the service make the mascot react to things like the phone being shaken. */
    fun reactEyes(r: EyesView.Reaction) {
        if (mode == Mode.COMPACT && !locked) eyes.react(r)
    }

    private fun pressPill(down: Boolean, x: Float) {
        pressSide = ((x - pillW / 2f) / (pillW / 2f)).coerceIn(-1f, 1f)
        pressAnim?.cancel()
        pressAnim = ValueAnimator.ofFloat(pressT, if (down) 1f else 0f).apply {
            duration = if (down) 140 else 420
            interpolator = if (down) android.view.animation.DecelerateInterpolator() else SpringInterpolator(0.45f, 12f)
            addUpdateListener {
                pressT = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Rubber-band: dragging the pill down stretches it with growing resistance and it springs back on release. */
    private fun rubber(dy: Float): Float {
        val max = context.dp(10f)
        return if (dy <= 0f) dy * 0.15f else max * (1f - kotlin.math.exp(-dy / context.dp(34f)))
    }

    private fun releaseStretch() {
        stretchAnim?.cancel()
        stretchAnim = ValueAnimator.ofFloat(stretch, 0f).apply {
            duration = 520
            interpolator = SpringInterpolator(0.42f, 11f)
            addUpdateListener {
                stretch = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Fling a notification away sideways: the card slides out while the pill closes. */
    private fun throwBanner(dir: Int) {
        banner.animate().translationX(dir * cardW * 1.2f).setDuration(260).withEndAction { banner.translationX = 0f }.start()
        context.haptic()
        setMode(Mode.COMPACT)
    }

    /** Card content appears late and grows slightly with the card. */
    private fun applyReveal() {
        val a = ((reveal - 0.3f) / 0.5f).coerceIn(0f, 1f)
        val s = 0.94f + 0.06f * reveal.coerceIn(0f, 1f)
        // Content also un-blurs as it arrives (blur-in), then renders sharp.
        val blur = (1f - a) * 14f
        val effect = if (blur > 0.6f && a > 0f) RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) else null
        listOf(banner, panel).forEach {
            it.alpha = a
            it.scaleX = s
            it.scaleY = s
            it.pivotY = 0f
            it.setRenderEffect(effect)
        }
        // The banner view follows the card height while it expands or collapses.
        if (mode == Mode.BANNER) {
            val lp = banner.layoutParams
            val h = cardH.toInt()
            if (lp.height != h) {
                lp.height = h
                banner.layoutParams = lp
            }
        }
        panel.setReveal(reveal)
        // The pill turns into the pop-up, so its content (eyes/equalizer/time) fades out first.
        val open = maxOf(reveal, eventT)
        val pillAlpha = (1f - open / 0.3f).coerceIn(0f, 1f)
        val eventAlpha = ((eventT - 0.55f) / 0.45f).coerceIn(0f, 1f) * (1f - reveal).coerceIn(0f, 1f)
        event.alpha = eventAlpha
        event.visibility = if (eventAlpha <= 0.01f) INVISIBLE else VISIBLE
        eyes.alpha = pillAlpha
        eyes.visibility = if (pillAlpha <= 0.01f) INVISIBLE else VISIBLE
        val bs = bubbleT.coerceAtLeast(0f)
        bubble.alpha = (((bs - 0.7f) / 0.3f).coerceIn(0f, 1f)) * (1f - open.coerceIn(0f, 1f))
        bubble.visibility = if (bubble.alpha <= 0.02f) INVISIBLE else VISIBLE
        invalidate()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipe.onTouchEvent(ev)
        // The mascot looks wherever the finger is inside our area, and keeps looking at the last spot for a while.
        eyes.lookAt((ev.x - width / 2f) / resources.displayMetrics.density, (ev.y - context.dp(20f)) / resources.displayMetrics.density)
        if (mode == Mode.COMPACT) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> dragStartY = ev.y
                MotionEvent.ACTION_MOVE -> if (!dragStartY.isNaN()) {
                    stretchAnim?.cancel()
                    stretch = rubber(ev.y - dragStartY)
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragStartY = Float.NaN
                    releaseStretch()
                }
            }
        }
        // While interacting, postpone the auto-close of user-opened cards.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && userOpened()) {
            handler.removeCallbacks(autoCollapse)
            handler.postDelayed(autoCollapse, PANEL_MS)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A touch on the empty area beside the pill closes a card the user opened.
        if (userOpened() && event.actionMasked == MotionEvent.ACTION_DOWN) {
            setMode(Mode.COMPACT)
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val pr = pillH / 2f
        val t = reveal.coerceAtLeast(0f)
        // One shape only: the pill widens into the pop-up (and back). Width leads height so it widens like an
        // island instead of dropping like a tower. The pill's top edge extends above the screen to hide its corners.
        val tw = if (t < 1f) 1f - (1f - t) * (1f - t) else t
        val curPillW = lerp(pillW, eventW, eventT)
        // Press squash: the pill shrinks toward the side that was touched (only while it is a plain pill).
        val squash = pressT * (1f - t.coerceIn(0f, 1f)) * (1f - eventT)
        val inL = squash * pillW * (0.02f + 0.05f * kotlin.math.max(0f, -pressSide))
        val inR = squash * pillW * (0.02f + 0.05f * kotlin.math.max(0f, pressSide))
        val l = lerp(cx - curPillW / 2f + inL, 0f, tw)
        val r = lerp(cx + curPillW / 2f - inR, width.toFloat(), tw)
        val top = lerp(-pr, cardTop, t)
        val b = lerp(pillH + stretch - squash * pillH * 0.06f, cardTop + cardH, t)
        val radius = min(lerp(pr, cardRadius, t.coerceAtMost(1f)), (b - top) / 2f)
        rect.set(l, top, r, b)
        canvas.drawRoundRect(rect, radius, radius, bgPaint)

        val open = maxOf(reveal, eventT).coerceIn(0f, 1f)
        val fade = 1f - open
        drawBubble(canvas, cx, pr, fade)
        drawPrivacyDot(canvas, cx, fade)
    }

    /**
     * Second activity: a circle that separates from the pill like a drop. While it is still close, a neck of
     * black connects it to the pill and thins out, which gives the gooey split the iPhone has.
     */
    private fun drawBubble(canvas: Canvas, cx: Float, pr: Float, fade: Float) {
        val bs = bubbleT
        if (bs <= 0.01f || fade <= 0.01f) return
        val edge = cx + pillW / 2f
        val finalX = edge + bubbleGap + pr
        val bx = lerp(edge - pr, finalX, bs.coerceAtMost(1.2f))
        val radius = pr * (0.55f + 0.45f * bs.coerceIn(0f, 1f)) * fade
        canvas.drawCircle(bx, pr, radius, bgPaint)
        val neck = 1f - ((bs - 0.12f) / 0.7f).coerceIn(0f, 1f)
        if (neck > 0.02f) {
            val hh = pr * 0.85f * neck * fade
            rect.set(edge - pr, pr - hh, bx, pr + hh)
            canvas.drawRoundRect(rect, hh, hh, bgPaint)
        }
    }

    /** Green dot while the camera is in use, orange while the microphone is (camera wins), like an iPhone. */
    private fun drawPrivacyDot(canvas: Canvas, cx: Float, fade: Float) {
        if (!Prefs.bool(Prefs.PRIVACY_DOTS) || fade <= 0.05f) return
        val color = when {
            PrivacyMonitor.camera -> Ui.GREEN
            PrivacyMonitor.mic -> 0xFFFF9F0A.toInt()
            else -> return
        }
        dotPaint.color = color
        dotPaint.alpha = (255 * fade).toInt()
        canvas.drawCircle(cx + pillW / 2f - context.dp(11f), context.dp(8f).toFloat(), context.dp(3.2f).toFloat(), dotPaint)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    override fun onDetachedFromWindow() {
        anim?.cancel()
        heightAnim?.cancel()
        bubbleAnim?.cancel()
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun openIntent(pi: PendingIntent) {
        try {
            val options = if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                    )
                    .toBundle()
            } else {
                null
            }
            pi.send(context, 0, null, null, null, null, options)
        } catch (e: PendingIntent.CanceledException) {
            // The notification was already removed by its app.
        }
    }

    companion object {
        // Card size measured from the One UI media pop-up on the S25: 335x150dp, 43dp from the top.
        const val PILL_W = 110f
        const val PILL_H = 40f
        const val EVENT_MAX_W = 260f
        const val CARD_W = 335f
        const val CARD_TOP = 43f
        const val CARD_RADIUS = 26f
        const val PANEL_H = 150f
        const val BANNER_H = 92f
        const val BANNER_EXPANDED_H = 140f
        const val BUBBLE_GAP = 6f

        private const val SWIPE_V = 900f
        private const val PANEL_MS = 12_000L
        private const val MAX_QUEUE = 5
        private const val MAX_EVENT_QUEUE = 3
    }
}
