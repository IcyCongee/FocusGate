package dev.focusgate.phase0

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class TrialSettingsActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val switches = mutableMapOf<TargetAppId, Switch>()
    private val summaries = mutableMapOf<TargetAppId, TextView>()
    private var refreshing = false
    private val refresh = object : Runnable {
        override fun run() { update(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProbeRuntime.initialize(this)
        val (shell, column) = NightUi.activityShell(this)
        fun text(label: String, size: Float = 15f, muted: Boolean = false) = NightUi.text(this, label, size, muted).also { column.addView(it) }
        fun button(label: String, action: () -> Unit) = NightUi.addButton(column, label, quiet = true, action = action)
        text("FocusGate", 23f)
        text("应用限制", 28f)
        text("每个应用独立开启，时间和冷却互不影响。", 13f, true)
        TargetCatalog.enabled.forEach { app ->
            NightUi.space(column)
            val card = NightUi.column(this, 18).apply { background = NightUi.background(this@TrialSettingsActivity) }
            val toggle = Switch(this).apply {
                text = app.displayName
                textSize = 17f
                setTextColor(NightUi.color(this@TrialSettingsActivity, R.color.night_text))
                minimumHeight = dp(52)
                setPadding(0, dp(8), 0, dp(8))
                thumbTintList = android.content.res.ColorStateList.valueOf(NightUi.color(this@TrialSettingsActivity, R.color.night_accent))
                trackTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(NightUi.color(this@TrialSettingsActivity, R.color.night_soft), NightUi.color(this@TrialSettingsActivity, R.color.night_line)))
                contentDescription = "开启${app.displayName}限制"
            }
            card.addView(toggle, LinearLayout.LayoutParams(-1, -2))
            switches[app.id] = toggle
            summaries[app.id] = NightUi.text(this, "", 12f, true).also { card.addView(it) }
            toggle.setOnCheckedChangeListener { _, checked -> if (!refreshing) changeEnabled(app, checked) }
            NightUi.addButton(card, "查看 / 更换${app.displayName}绑定", quiet = false) {
                startActivity(Intent(this, MainActivity::class.java).putExtra("targetAppId", app.id.value))
            }
            column.addView(card, LinearLayout.LayoutParams(-1, -2))
        }
        NightUi.space(column, 20)
        text("当前使用规则", 19f)
        listOf("单次使用上限" to "30分钟", "离开后保留会话" to "10分钟", "到时冷却" to "10分钟").forEach { (label, value) ->
            val row = LinearLayout(this)
            row.addView(NightUi.text(this, label, 14f, true), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(NightUi.text(this, value, 14f))
            column.addView(row)
            NightUi.divider(column)
        }
        text("关闭会结束该应用当前会话，保留历史和冷却；暂停仍记录可确认的前台用量。当前规则暂不可编辑。", 12f, true)
        button("权限与运行状态 / 导出日志") { startActivity(Intent(this, MainActivity::class.java)) }
        button("重试保存并主动恢复检查") {
            if (TrialRuntime.save()) {
                ProbeRuntime.service?.requestCheck("trial_manual_recovery")
                toast(if (ProbeRuntime.service == null) "已保存；服务未连接，请在无障碍设置中恢复。" else "已重新保存并核对当前窗口。")
            } else toast(TrialRuntime.storageError!!)
            update()
        }
        text("仅管理普通全屏前台使用。后台音频、投屏、小窗和画中画不计时也不限制。当前没有每日额度和延长机制。", 12f, true)
        text("FocusGate · ${BuildConfig.VERSION_NAME} · 墨色专注", 12f, true)
        NightUi.navigation(shell, "settings") { destination ->
            if (destination != "settings") {
                startActivity(Intent(this, TrialActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("page", destination))
                finish()
            }
        }
    }
    private fun changeEnabled(app: TargetApp, value: Boolean) {
        val engine = TrialRuntime.engine(app.id)
        if (engine.enabled == value) return
        // Undo the visual flip while waiting for the explicit enable action or persistence.
        update()
        if (value) {
            if (ProbeRuntime.targets.binding(app.id) == null || ProbeRuntime.service == null) {
                toast("请先核实绑定${app.displayName}，并确认无障碍服务已连接。")
                return
            }
            if (TrialRuntime.storageError != null) { toast(TrialRuntime.storageError!!); return }
            AlertDialog.Builder(this).setTitle("开启${app.displayName}限制？")
                .setMessage("单次最多30分钟、离开宽限10分钟、到时冷却10分钟。其计时和冷却与其他应用独立。只管理普通全屏前台使用。")
                .setNegativeButton("取消", null).setPositiveButton("开启") { _, _ -> applyEnabled(app, true) }.show()
        } else applyEnabled(app, false)
    }

    private fun applyEnabled(app: TargetApp, value: Boolean) {
        if (value && (ProbeRuntime.targets.binding(app.id) == null || ProbeRuntime.service == null)) {
            toast("绑定或连接已变化，请重新检查。")
            update(); return
        }
        ProbeRuntime.service?.requestCheck("target_toggle_before")
        val engine = TrialRuntime.engine(app.id)
        val previousEnabled = engine.enabled
        val previousPaused = engine.paused
        engine.changeEnabled(value, System.currentTimeMillis())
        if (!TrialRuntime.save()) {
            engine.enabled = previousEnabled
            engine.paused = previousPaused
            toast(TrialRuntime.storageError ?: "保存失败，开关未生效。")
        } else ProbeRuntime.record(if (value) "TRIAL_TARGET_ENABLED" else "TRIAL_TARGET_DISABLED", associatedTarget = app.id)
        ProbeRuntime.service?.requestCheck("target_toggle_after")
        update()
    }

    private fun update() {
        refreshing = true
        TargetCatalog.enabled.forEach { app ->
            val engine = TrialRuntime.engine(app.id)
            switches[app.id]?.isChecked = engine.enabled
            summaries[app.id]?.text = "${ProbeRuntime.targets.binding(app.id)?.let { "已绑定：${it.app.label}" } ?: "尚未绑定，请先核实实际应用"}\n" +
                when { !engine.enabled -> "限制已关闭"; engine.paused -> "已开启，但你已暂停该应用限制"; ProbeRuntime.targets.binding(app.id) == null -> "已开启，但缺少绑定，限制不可用"; ProbeRuntime.service == null -> "已开启，但服务未连接，限制不可用"; TrialRuntime.storageError != null -> "保存异常，请返回首页重试"; else -> "限制已开启" }
        }
        refreshing = false
    }

    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
