package dev.focusgate.phase0

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/** Shared native controls for the selected “墨色专注” design. No execution state lives here. */
object NightUi {
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun color(context: Context, id: Int) = context.getColor(id)
    fun background(context: Context, fill: Int = R.color.night_surface, radius: Int = 24, stroke: Boolean = false) =
        GradientDrawable().apply {
            setColor(color(context, fill))
            cornerRadius = dp(context, radius).toFloat()
            if (stroke) setStroke(dp(context, 1), color(context, R.color.night_line))
        }

    fun column(context: Context, padding: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val inset = dp(context, padding)
        setPadding(inset, inset, inset, inset)
    }

    fun text(context: Context, value: String, size: Float = 15f, muted: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color(context, if (muted) R.color.night_muted else R.color.night_text))
        setLineSpacing(dp(context, 3).toFloat(), 1f)
        setPadding(0, dp(context, 6), 0, dp(context, 6))
    }

    fun styleButton(button: Button, primary: Boolean = false, selected: Boolean = false, quiet: Boolean = false) {
        val context = button.context
        val filled = primary || selected
        val fill = if (filled) R.color.night_accent else if (quiet) R.color.night_background else R.color.night_surface
        button.isAllCaps = false
        button.textSize = 14f
        button.minimumHeight = dp(context, 48)
        button.minHeight = dp(context, 48)
        button.minWidth = 0
        button.minimumWidth = 0
        button.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
        button.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(color(context, R.color.night_muted), color(context, if (filled) R.color.night_on_accent else R.color.night_text))))
        button.backgroundTintList = null
        button.background = RippleDrawable(ColorStateList.valueOf(0x224b7050), background(context, fill, 20), null)
        button.stateListAnimator = null
        button.isSelected = selected
        button.alpha = if (button.isEnabled) 1f else 0.5f
    }

    fun button(context: Context, label: String, primary: Boolean = false, quiet: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = label
        styleButton(this, primary = primary, quiet = quiet)
        setOnClickListener { action() }
    }

    fun addButton(parent: LinearLayout, label: String, primary: Boolean = false, quiet: Boolean = false, action: () -> Unit): Button {
        val button = button(parent.context, label, primary, quiet, action)
        parent.addView(button, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(parent.context, 10) })
        return button
    }

    fun field(input: EditText) {
        val context = input.context
        input.setTextColor(color(context, R.color.night_text))
        input.setHintTextColor(color(context, R.color.night_muted))
        input.textSize = 16f
        input.backgroundTintList = null
        input.background = background(context, radius = 16, stroke = true)
        input.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
        input.minimumHeight = dp(context, 48)
    }

    fun divider(parent: LinearLayout) {
        parent.addView(View(parent.context).apply { setBackgroundColor(color(context, R.color.night_line)) },
            LinearLayout.LayoutParams(-1, dp(parent.context, 1)).apply { topMargin = dp(parent.context, 10) })
    }

    fun space(parent: LinearLayout, height: Int = 12) {
        parent.addView(View(parent.context), LinearLayout.LayoutParams(1, dp(parent.context, height)))
    }

    /** Bars stay outside controls; the scroll area also supports large fonts and compact screens. */
    fun activityShell(activity: Activity): Pair<LinearLayout, LinearLayout> {
        val shell = column(activity).apply { setBackgroundColor(color(activity, R.color.night_background)) }
        shell.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val scroll = ScrollView(activity).apply { isFillViewport = true }
        val content = column(activity, 22)
        scroll.addView(content)
        shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        activity.setContentView(shell)
        shell.requestApplyInsets()
        return shell to content
    }

    fun navigation(parent: LinearLayout, selected: String, action: (String) -> Unit) {
        val context = parent.context
        val row = LinearLayout(context).apply {
            setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8))
            setBackgroundColor(color(context, R.color.night_surface))
        }
        listOf("home" to "首页", "records" to "记录", "settings" to "设置").forEach { (id, name) ->
            row.addView(button(context, name) { action(id) }.apply {
                styleButton(this, selected = id == selected)
                if (id == selected) contentDescription = "$name，当前页面"
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(context, 3); marginEnd = dp(context, 3) })
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
    }
}

/** The arc is decorative. Text stays in native views for font scaling and accessibility. */
class NightDial(context: Context) : FrameLayout(context) {
    private val arc = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val bounds = RectF()
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.strokeWidth = NightUi.dp(context, 5).toFloat()
            val side = minOf(width, height).toFloat()
            val left = (width - side) / 2 + paint.strokeWidth
            val top = (height - side) / 2 + paint.strokeWidth
            bounds.set(left, top, left + side - paint.strokeWidth * 2, top + side - paint.strokeWidth * 2)
            paint.color = NightUi.color(context, R.color.night_soft)
            canvas.drawOval(bounds, paint)
            paint.color = NightUi.color(context, R.color.night_accent)
            if (fraction > 0) canvas.drawArc(bounds, -90f, fraction * 360f, false, paint)
        }
    }
    private var fraction = 0f
    private val title = NightUi.text(context, "", 12f, true)
    private val value = NightUi.text(context, "", 40f).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        setPadding(0, 0, 0, 0)
    }
    private val caption = NightUi.text(context, "", 12f, true)

    init {
        addView(arc, LayoutParams(-1, -1))
        val labels = NightUi.column(context).apply {
            gravity = Gravity.CENTER
            setPadding(NightUi.dp(context, 24), NightUi.dp(context, 22), NightUi.dp(context, 24), NightUi.dp(context, 22))
        }
        listOf(title, value, caption).forEach { it.gravity = Gravity.CENTER; labels.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        addView(labels, LayoutParams(-1, -2, Gravity.CENTER))
        arc.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        labels.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    fun bind(heading: String, main: String, detail: String, progress: Float) {
        title.text = heading
        value.text = main
        caption.text = detail
        fraction = progress.coerceIn(0f, 1f)
        contentDescription = "$heading，$main，$detail"
        arc.invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val scale = resources.configuration.fontScale.coerceAtLeast(1f)
        // Let larger fonts use a taller layout; scrolling keeps all actions reachable.
        val height = maxOf(width, (NightUi.dp(context, 240) * scale).toInt())
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }
}

fun nightClock(milliseconds: Long): String {
    val value = milliseconds.coerceAtLeast(0)
    val seconds = value / 1000 + if (value % 1000 == 0L) 0 else 1
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}
