package app.sona.notch

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Small design system for the app's own screens: dark grouped lists, one type scale, a 4dp grid. */
object Ui {
    const val BG = 0xFF000000.toInt()
    const val SURFACE = 0xFF1C1C1E.toInt()
    const val DIVIDER = 0xFF2C2C2E.toInt()
    const val TEXT = Color.WHITE
    const val SECONDARY = 0x99FFFFFF.toInt()
    const val GREEN = 0xFF30D158.toInt()
    const val BLUE = 0xFF0A84FF.toInt()
    const val RED = 0xFFFF453A.toInt()
}

private fun Context.px(dp: Float) = (dp * resources.displayMetrics.density).toInt()

/** Black full-screen scroll container with a large title, padded for the system bars (edge-to-edge). */
fun Activity.screen(title: String, subtitle: String? = null, build: LinearLayout.() -> Unit) {
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(20f), px(12f), px(20f), px(32f))
    }
    column.addView(
        TextView(this).apply {
            text = title
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            setPadding(px(4f), px(8f), 0, 0)
        },
    )
    if (subtitle != null) {
        column.addView(
            TextView(this).apply {
                text = subtitle
                textSize = 14f
                setTextColor(Ui.SECONDARY)
                setPadding(px(4f), px(2f), 0, px(8f))
            },
        )
    }
    column.build()
    val scroll = ScrollView(this).apply {
        setBackgroundColor(Ui.BG)
        isVerticalScrollBarEnabled = false
        addView(column)
        setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
    }
    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Ui.BG))
    setContentView(scroll)
}

/** A short paragraph of secondary text, aligned with the grouped lists. */
fun LinearLayout.note(text: String, monospace: Boolean = false) {
    addView(
        TextView(context).apply {
            this.text = text
            textSize = 12.5f
            setTextColor(Ui.SECONDARY)
            if (monospace) typeface = Typeface.MONOSPACE
            setTextIsSelectable(monospace)
            setPadding(context.px(8f), context.px(8f), context.px(8f), 0)
        },
    )
}

fun LinearLayout.caption(text: String) {
    addView(
        TextView(context).apply {
            this.text = text.uppercase()
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.SECONDARY)
            letterSpacing = 0.06f
            setPadding(context.px(8f), context.px(24f), 0, context.px(8f))
        },
    )
}

/** Rounded group of rows with hairline dividers between them. */
fun LinearLayout.group(vararg rows: View) {
    val box = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Ui.SURFACE)
            cornerRadius = context.px(16f).toFloat()
        }
        clipToOutline = true
    }
    rows.forEachIndexed { i, row ->
        if (i > 0) {
            box.addView(
                View(context).apply { setBackgroundColor(Ui.DIVIDER) },
                LinearLayout.LayoutParams(-1, 1).apply { leftMargin = context.px(16f) },
            )
        }
        box.addView(row)
    }
    addView(box, LinearLayout.LayoutParams(-1, -2))
}

private fun Context.rowBase(): LinearLayout = LinearLayout(this).apply {
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = px(52f)
    setPadding(px(16f), px(10f), px(16f), px(10f))
}

private fun Context.labelColumn(label: String, hint: String?): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    addView(TextView(context).apply { text = label; textSize = 16f; setTextColor(Ui.TEXT) })
    if (hint != null) {
        addView(TextView(context).apply { text = hint; textSize = 12.5f; setTextColor(Ui.SECONDARY) })
    }
}

fun Context.toggleRow(label: String, key: String, hint: String? = null): View = rowBase().apply {
    addView(labelColumn(label, hint), LinearLayout.LayoutParams(0, -2, 1f))
    addView(
        Switch(context).apply {
            isChecked = Prefs.bool(key)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Ui.GREEN, 0xFF39393D.toInt()),
            )
            setOnCheckedChangeListener { _, checked -> Prefs.setBool(key, checked) }
        },
    )
}

/** Row with a trailing value/chevron; [valueColor] lets status rows show green "Granted" or blue "Allow". */
fun Context.linkRow(
    label: String,
    value: String? = "›",
    valueColor: Int = Ui.SECONDARY,
    hint: String? = null,
    onClick: () -> Unit,
): View =
    rowBase().apply {
        addView(labelColumn(label, hint), LinearLayout.LayoutParams(0, -2, 1f))
        if (value != null) {
            addView(TextView(context).apply { text = value; textSize = 15f; setTextColor(valueColor) })
        }
        isClickable = true
        setOnClickListener { onClick() }
        pressScale()
    }

fun Context.sliderRow(label: String, key: String, min: Int, max: Int, default: Int, unit: String = "s"): View {
    val value = Prefs.int(key, default).coerceIn(min, max)
    val title = TextView(this).apply { textSize = 16f; setTextColor(Ui.TEXT) }
    val amount = TextView(this).apply { textSize = 15f; setTextColor(Ui.SECONDARY) }
    fun render(v: Int) {
        title.text = label
        amount.text = if (unit == "h") "%02d:00".format(v) else "$v $unit"
    }
    render(value)
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(16f), px(12f), px(16f), px(8f))
        addView(
            LinearLayout(context).apply {
                addView(title, LinearLayout.LayoutParams(0, -2, 1f))
                addView(amount)
            },
        )
        addView(
            SeekBar(context).apply {
                this.max = max - min
                progress = value - min
                progressTintList = ColorStateList.valueOf(Ui.GREEN)
                thumbTintList = ColorStateList.valueOf(Color.WHITE)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                        render(p + min)
                        Prefs.setInt(key, p + min)
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                })
            },
            LinearLayout.LayoutParams(-1, -2),
        )
    }
}

/** Full-width pill button. */
fun Context.bigButton(label: String, color: Int, onClick: () -> Unit): TextView = TextView(this).apply {
    text = label
    textSize = 17f
    typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    setTextColor(Color.WHITE)
    minHeight = px(54f)
    background = roundBg(context, color, 16f)
    setOnClickListener { onClick() }
    pressScale()
}
