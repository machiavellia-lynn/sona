package app.sona.notch

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** What the reply screen should answer; set by the notch right before it starts [ReplyActivity]. */
object ReplyHolder {
    var action: NotifAction? = null
    var title: String = ""
}

/**
 * A small translucent screen with a text field used for inline replies. The notch window cannot take
 * keyboard focus, so typing happens here; the text is delivered through the notification's own RemoteInput.
 */
class ReplyActivity : Activity() {

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = ReplyHolder.action
        val remoteInput = target?.remoteInput
        if (target == null || remoteInput == null) {
            finish()
            return
        }
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN,
        )

        val input = EditText(this).apply {
            hint = "Reply to ${ReplyHolder.title.ifBlank { "message" }}"
            setHintTextColor(Ui.SECONDARY)
            setTextColor(Color.WHITE)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = roundBg(this@ReplyActivity, 0xFF2C2C2E.toInt(), 22f)
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            maxLines = 4
        }

        fun send() {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return
            val results = Bundle().apply { putCharSequence(remoteInput.resultKey, text) }
            val fill = Intent()
            RemoteInput.addResultsToIntent(arrayOf(remoteInput), fill, results)
            try {
                target.intent?.send(this, 0, fill)
            } catch (e: Exception) {
                // The conversation was dismissed in the meantime.
            }
            finish()
        }

        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) send()
            true
        }
        val sendBtn = TextView(this).apply {
            text = "Send"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = roundBg(this@ReplyActivity, Ui.BLUE, 22f)
            setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
            setOnClickListener { send() }
            pressScale()
        }

        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = roundBg(this@ReplyActivity, Color.BLACK, 28f)
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            addView(sendBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8f) })
        }
        // Tapping outside the card dismisses the screen.
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            setOnClickListener { finish() }
            addView(
                card,
                FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(12f), dp(56f), dp(12f), 0) },
            )
        }
        setContentView(root)
        input.requestFocus()
    }
}
