package uniear.android

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import dev.hyperears.integration.NoiseMode
import uniear.core.HeadsetView
import uniear.core.label

/** UniEar's floating panel uses Android's user-granted overlay permission. */
internal class FloatingControls(private val context: Context) {
    private val windows = requireNotNull(context.getSystemService(WindowManager::class.java))
    private var panel: LinearLayout? = null
    private var expanded = false
    private var rendered: Pair<NoiseMode?, Set<NoiseMode>>? = null

    fun render(view: HeadsetView) {
        if (!Surface.CAPSULE.enabled(context) || !view.ready || view.modes.isEmpty() ||
            !Settings.canDrawOverlays(context)) {
            close()
            return
        }
        val key = view.mode to view.modes
        if (rendered == key && panel != null) return
        val root = panel ?: LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.rgb(22, 60, 52))
                cornerRadius = dp(20).toFloat()
            }
            val params = WindowManager.LayoutParams(-2, -2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dp(12)
                y = dp(18)
            }
            try {
                windows.addView(this, params)
            } catch (_: RuntimeException) {
                return
            }
            panel = this
        }
        rendered = key
        root.removeAllViews()
        root.addView(action("UniEar · ${view.mode?.label() ?: "耳机模式"}  ${if (expanded) "▴" else "▾"}") {
            expanded = !expanded
            rendered = null
            render(ControlState.view)
        })
        if (expanded) {
            for (mode in NoiseMode.entries.filter { it in view.modes }) {
                root.addView(action(if (mode == view.mode) "✓ ${mode.label()}" else mode.label()) {
                    ControlState.select(context, Surface.CAPSULE, mode)
                    expanded = false
                    rendered = null
                    render(ControlState.view)
                })
            }
            root.addView(action("打开 UniEar") {
                context.startActivity(QuickControls.appIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
        }
    }

    fun close() {
        panel?.let { runCatching { windows.removeViewImmediate(it) } }
        panel = null
        rendered = null
        expanded = false
    }

    private fun action(label: String, run: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 13f
        setTextColor(Color.WHITE)
        minHeight = dp(44)
        gravity = Gravity.CENTER
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { run() }
    }

    private fun dp(size: Int) = (size * context.resources.displayMetrics.density + .5f).toInt()
}
