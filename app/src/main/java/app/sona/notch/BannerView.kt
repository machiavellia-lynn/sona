package app.sona.notch

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** One button under an expanded card: Reply, Mark as read, Mute, Speaker, ... */
data class ActionSpec(val label: String, val color: Int, val onClick: () -> Unit)

/**
 * App card. Top row: app icon, then app name + time, sender/title (one line) and message (up to two lines,
 * cut with "…"). Call cards add Decline/Answer or End on the right. An expanded card shows [ActionSpec] buttons below.
 *
 * Spacing follows a 4dp grid with 16dp outer padding; the icon radius is the card radius (26dp) minus that padding,
 * rounded to 10dp, so nested corners stay concentric.
 */
class BannerView(context: Context) : FrameLayout(context) {

    private val icon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, context.dp(10f).toFloat())
            }
        }
    }
    private val appName = TextView(context).apply {
        setTextColor(Color.argb(150, 255, 255, 255))
        textSize = 11.5f
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val time = TextView(context).apply {
        setTextColor(Color.argb(110, 255, 255, 255))
        textSize = 11.5f
        text = "now"
        maxLines = 1
        fontFeatureSettings = "tnum"
    }
    private val title = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val body = TextView(context).apply {
        setTextColor(BODY_COLOR)
        textSize = 13f
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        fontFeatureSettings = "tnum"  // call duration does not jitter every second
    }
    private val endBtn = pill("End", Color.rgb(255, 59, 48)) { onEndCall?.invoke() }
    private val answerBtn = pill("Answer", Color.rgb(52, 199, 89)) { onAnswerCall?.invoke() }
    private val actionsRow = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
    }

    val hasActions: Boolean get() = actionsRow.childCount > 0

    /** Card height in dp that fits this notification: shorter when the message is a single line. */
    fun preferredHeightDp(item: NotifItem): Float {
        if (item.text.isBlank()) return 72f
        val room = (CARD_TEXT_WIDTH_DP * context.resources.displayMetrics.density) * 0.97f
        return if (body.paint.measureText(item.text) <= room) 78f else 92f
    }

    var onTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
    var onEndCall: (() -> Unit)? = null
    var onAnswerCall: (() -> Unit)? = null

    init {
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    LinearLayout(context).apply {
                        addView(
                            icon,
                            LinearLayout.LayoutParams(context.dp(40f), context.dp(40f)).apply { topMargin = context.dp(2f) },
                        )
                        addView(
                            LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(
                                    LinearLayout(context).apply {
                                        addView(appName, LinearLayout.LayoutParams(0, -2, 1f))
                                        addView(time)
                                    },
                                )
                                addView(title)
                                addView(body)
                            },
                            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = context.dp(12f) },
                        )
                        for (button in listOf(endBtn, answerBtn)) {
                            addView(
                                button,
                                LinearLayout.LayoutParams(-2, -2, 0f).apply {
                                    leftMargin = context.dp(8f)
                                    gravity = Gravity.CENTER_VERTICAL
                                },
                            )
                        }
                    },
                )
                addView(actionsRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(10f) })
            },
            LayoutParams(-1, -2, Gravity.TOP).apply {
                setMargins(context.dp(16f), context.dp(13f), context.dp(18f), 0)
            },
        )
        setOnClickListener { onTap?.invoke() }
        setOnLongClickListener {
            onLongPress?.invoke()
            true
        }
    }

    private fun pill(label: String, color: Int, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 12.5f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minHeight = context.dp(36f)
        background = roundBg(context, color, 18f)
        setPadding(context.dp(14f), 0, context.dp(14f), 0)
        visibility = GONE
        setOnClickListener { onClick() }
        pressScale()
    }

    private fun resetButtons() {
        endBtn.visibility = GONE
        answerBtn.visibility = GONE
    }

    /** Buttons for the expanded state; an empty list means this card cannot be expanded. */
    fun setActions(specs: List<ActionSpec>) {
        actionsRow.removeAllViews()
        specs.forEachIndexed { i, spec ->
            actionsRow.addView(
                pill(spec.label, spec.color) { spec.onClick() }.apply { visibility = VISIBLE },
                LinearLayout.LayoutParams(0, context.dp(36f), 1f).apply { if (i > 0) leftMargin = context.dp(8f) },
            )
        }
    }

    /** Right-hand label: "now", "3 new" for a merged conversation, plus how many more pop-ups wait in the queue. */
    fun setWaiting(queued: Int, count: Int) {
        val base = if (count > 1) "$count new" else "now"
        time.text = if (queued > 0) "$base · +$queued" else base
    }

    fun setExpanded(expanded: Boolean) {
        actionsRow.visibility = if (expanded && actionsRow.childCount > 0) VISIBLE else GONE
    }

    fun bind(item: NotifItem) {
        resetButtons()
        time.visibility = VISIBLE
        setWaiting(0, item.count)
        body.setTextColor(BODY_COLOR)
        icon.setImageDrawable(item.icon)
        appName.text = item.appName
        title.text = item.title
        title.visibility = if (item.title.isBlank()) GONE else VISIBLE
        body.text = item.text
        body.visibility = if (item.text.isBlank()) GONE else VISIBLE
    }

    /** Call card: dialer icon, contact name, status text, and Decline/Answer (ringing) or End buttons. */
    fun bindCall(info: CallInfo, status: String) {
        time.visibility = GONE
        icon.setImageDrawable(info.icon)
        appName.text = if (info.ringing) "Incoming call" else "Call"
        setIfChanged(title, info.name)
        title.visibility = VISIBLE
        body.setTextColor(if (info.ringing) Color.WHITE else GREEN)
        body.visibility = VISIBLE
        setIfChanged(body, status)
        endBtn.text = if (info.ringing) "Decline" else "End"
        endBtn.visibility = if (info.hangUp != null) VISIBLE else GONE
        answerBtn.visibility = if (info.ringing && info.answer != null) VISIBLE else GONE
    }

    /** Live-activity card: the app's own title and text, with its live time on the right of the app name. */
    fun bindLive(info: LiveInfo, status: String) {
        resetButtons()
        time.visibility = VISIBLE
        setIfChanged(time, status)
        body.setTextColor(BODY_COLOR)
        icon.setImageDrawable(info.icon)
        appName.text = info.appName
        setIfChanged(title, info.title)
        title.visibility = if (info.title.isBlank()) GONE else VISIBLE
        setIfChanged(body, info.text)
        body.visibility = if (info.text.isBlank()) GONE else VISIBLE
    }

    private fun setIfChanged(view: TextView, value: String) {
        if (view.text.toString() != value) view.text = value
    }

    private companion object {
        /** 335dp card minus padding (16 + 18), icon (40) and gap (12). */
        const val CARD_TEXT_WIDTH_DP = 249f
        val BODY_COLOR = Color.argb(205, 255, 255, 255)
        val GREEN = Color.rgb(52, 199, 89)
    }
}
