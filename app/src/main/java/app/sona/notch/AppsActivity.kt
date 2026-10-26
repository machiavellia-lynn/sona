package app.sona.notch

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Lists launcher apps; switching one on mutes its notification pop-ups and live activities. */
class AppsActivity : Activity() {

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .distinctBy { it.first }
            .filter { it.first != packageName }
            .sortedBy { it.second.lowercase() }

        screen("Muted apps", "Turn an app on to stop its pop-ups") {
            caption("Apps")
            group(*apps.map { (pkg, label) -> row(pkg, label) }.toTypedArray())
        }
    }

    private fun row(pkg: String, label: String): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        addView(
            ImageView(context).apply { setImageDrawable(packageManager.getApplicationIcon(pkg)) },
            LinearLayout.LayoutParams(dp(36f), dp(36f)),
        )
        addView(
            TextView(context).apply {
                text = label
                textSize = 16f
                setTextColor(Ui.TEXT)
                setPadding(dp(14f), 0, 0, 0)
                maxLines = 1
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            Switch(context).apply {
                isChecked = Prefs.isMuted(pkg)
                thumbTintList = ColorStateList.valueOf(Color.WHITE)
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(Ui.RED, 0xFF39393D.toInt()),
                )
                setOnCheckedChangeListener { _, muted -> Prefs.setMuted(pkg, muted) }
            },
        )
    }
}
