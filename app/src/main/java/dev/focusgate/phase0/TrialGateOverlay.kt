package dev.focusgate.phase0

import android.graphics.PixelFormat
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Opaque, touchable and focusable accessibility window. System Home/Recents remain available. */
class TrialGateOverlay(
    private val service: ForegroundProbeService,
    private val approve: (TargetAppId, String, Long) -> String?,
    private val cancel: (TargetAppId) -> Unit,
    private val pause: (TargetAppId) -> Unit,
    private val continueSession: (TargetAppId, String) -> String?,
) {
    private val context = ContextThemeWrapper(service, R.style.AppTheme)
    private val manager = service.getSystemService(WindowManager::class.java)
    private var root: ScrollView? = null
    private var mode: String? = null
    private var message: TextView? = null
    private var cooldownDial: NightDial? = null
    var targetId: TargetAppId? = null
        private set
    val visible get() = root != null

    fun show(id: TargetAppId, kind: String, detail: String) {
        if (targetId == id && mode == kind && root != null) { message?.text = detail; if (kind == "cooldown") bindCooldown(id); return }
        hide()
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(NightUi.color(context, R.color.night_background))
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        scroll.addView(column, FrameLayout.LayoutParams(-1, -1))
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        fun text(value: String, size: Float = 15f, muted: Boolean = false): TextView = NightUi.text(context, value, size, muted).also { column.addView(it) }
        fun button(label: String, primary: Boolean = false, quiet: Boolean = false, action: () -> Unit) = NightUi.addButton(column, label, primary, quiet, action)
        val app = TargetCatalog.find(id)
        text("FocusGate", 20f)
        NightUi.space(column, 16)
        text("${app.displayName} · ${when (kind) { "confirm" -> "使用前确认"; "resume" -> "继续本次使用"; else -> "先休息一下" }}", 13f, true)
        text(when (kind) { "confirm" -> "先想清楚，\n再打开。"; "resume" -> "准备好，\n再继续。"; "cooldown" -> "到这里，刚刚好。"; else -> "暂时无法放行" }, 30f)
        message = text(detail, 13f, true)
        if (kind == "resume") {
            val permitId = TrialRuntime.engine(id).session?.id
            val error = text("", 13f).apply { setTextColor(NightUi.color(context, R.color.night_warning)) }
            button("继续", primary = true) {
                error.text = permitId?.let { continueSession(id, it) } ?: "本次时段已结束，请重新进入。"
            }
        }
        if (kind == "cooldown") {
            cooldownDial = NightDial(context).also {
                column.addView(it, LinearLayout.LayoutParams(minOf(dp(248), context.resources.displayMetrics.widthPixels - dp(64)), -2).apply {
                    gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12); bottomMargin = dp(16)
                })
            }
            bindCooldown(id)
            text("冷却只影响${app.displayName}。离开此页，休息时间也会继续。", 13f, true)
        }
        if (kind == "confirm") {
            text("这次的理由", 14f)
            val reason = EditText(context).apply {
                hint = "例如：看完收藏的一节摄影课"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = 3
                maxLines = 5
                gravity = Gravity.TOP
                this.id = View.generateViewId()
                NightUi.field(this)
                column.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
            text("预计使用多久？（不预选）", 14f)
            val duration = EditText(context).apply {
                hint = "或自定义分钟，正整数"
                inputType = InputType.TYPE_CLASS_NUMBER
                setSingleLine(true)
                this.id = View.generateViewId()
                NightUi.field(this)
            }
            val shortcuts = LinearLayout(context)
            column.addView(shortcuts)
            val choiceButtons = mutableMapOf<Int, Button>()
            listOf(5, 10, 15, 20, 30).forEach { minutes ->
                val choice = NightUi.button(context, "${minutes}分") { duration.setText(minutes.toString()) }.apply {
                    contentDescription = "预计使用${minutes}分钟"
                    setPadding(0, dp(10), 0, dp(10))
                }
                choiceButtons[minutes] = choice
                shortcuts.addView(choice, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            }
            column.addView(duration, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            val preview = text("请选择时长，不会自动替你决定。", 13f).apply {
                background = NightUi.background(context, R.color.night_soft, 16)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            val error = text("", 13f).apply {
                setTextColor(NightUi.color(context, R.color.night_warning))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            val start = button("确认时长，开始使用", primary = true) {
                val minutes = duration.text.toString().toLongOrNull()
                error.text = if (minutes == null || minutes !in 1..TrialEngine.MAX_INPUT_MINUTES) "请主动选择有效的正整数分钟。"
                    else approve(id, reason.text.toString(), minutes).orEmpty()
            }.apply { isEnabled = false; NightUi.styleButton(this, primary = true) }
            duration.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, begin: Int, before: Int, count: Int) {
                    val minutes = s?.toString()?.toLongOrNull()
                    val valid = minutes != null && minutes in 1..TrialEngine.MAX_INPUT_MINUTES
                    preview.text = if (!valid) "请选择有效的正整数分钟。"
                    else "本次实际批准：${formatTrialDuration(minOf(minutes!! * 60_000L, TrialEngine.SINGLE_LIMIT_MS))}" +
                        if (minutes > 30) "\n受单次30分钟上限限制，点击开始表示确认此批准时长。" else ""
                    choiceButtons.forEach { (value, choice) ->
                        NightUi.styleButton(choice, selected = minutes == value.toLong())
                        choice.setPadding(0, dp(10), 0, dp(10))
                    }
                    start.isEnabled = valid
                    NightUi.styleButton(start, primary = true)
                    error.text = ""
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        button(if (kind == "confirm") "不看了，返回桌面" else "返回桌面", primary = kind != "confirm" && kind != "resume") { cancel(id) }
        button("暂停${app.displayName}限制", quiet = true) { pause(id) }
        text("当前仅提供单次管理；每日额度和延长尚未提供。此页停留不计时，底层可能仍有声音。", 12f, true)
        scroll.isFocusableInTouchMode = true
        scroll.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { cancel(id); true } else false
        }
        val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            0, PixelFormat.OPAQUE).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            title = "FocusGate use confirmation"
        }
        runCatching {
            manager.addView(scroll, params)
            root = scroll
            mode = kind
            targetId = id
            TrialRuntime.overlayVisible = true
            TrialRuntime.overlayTargetId = id
            TrialUiStatus.overlayError = null
            scroll.requestApplyInsets()
            ProbeRuntime.record("TRIAL_OVERLAY_ATTACHED", associatedTarget = id, details = mapOf("mode" to kind))
        }.onFailure {
            ProbeRuntime.record("TRIAL_OVERLAY_FAILED", associatedTarget = id, details = mapOf("error" to it.javaClass.simpleName))
            // Make the loss visible on the home page instead of reporting active restrictions.
            TrialUiStatus.overlayError = "确认窗口未能显示，请暂停限制并导出日志。"
        }
    }

    fun hide() {
        val view = root ?: return
        val oldTarget = targetId
        service.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
        runCatching { manager.removeViewImmediate(view) }.onFailure {
            ProbeRuntime.record("TRIAL_OVERLAY_REMOVE_FAILED", details = mapOf("error" to it.javaClass.simpleName))
        }
        root = null
        mode = null
        message = null
        cooldownDial = null
        targetId = null
        TrialRuntime.overlayVisible = false
        TrialRuntime.overlayTargetId = null
        ProbeRuntime.record("TRIAL_OVERLAY_REMOVED", associatedTarget = oldTarget)
    }

    private fun bindCooldown(id: TargetAppId) {
        val remaining = TrialRuntime.engine(id).cooldownRemaining(android.os.SystemClock.elapsedRealtime())
        cooldownDial?.bind("${TargetCatalog.find(id).displayName} · 冷却中", nightClock(remaining), "距离可以再次使用", remaining.toFloat() / TrialEngine.COOLDOWN_MS)
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}

object TrialUiStatus { var overlayError: String? = null }
