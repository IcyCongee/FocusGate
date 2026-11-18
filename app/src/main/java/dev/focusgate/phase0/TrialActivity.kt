package dev.focusgate.phase0

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Selected-app home and records, styled after the approved “墨色专注” preview. */
class TrialActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private lateinit var shell: LinearLayout
    private var selected = TargetCatalog.bilibili.id
    private var page = "home"
    private var restPage = false
    private var status: TextView? = null
    private var dial: NightDial? = null
    private var summary: TextView? = null
    private var primary: Button? = null
    private var pause: Button? = null
    private var history: TextView? = null
    private val rows = mutableMapOf<TargetAppId, TextView>()
    private val refresh = object : Runnable {
        override fun run() { update(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProbeRuntime.initialize(this)
        val requested = savedInstanceState?.getString("selectedTarget") ?: intent.getStringExtra("targetAppId")
        selected = TargetCatalog.enabled.firstOrNull { it.id.value == requested }?.id ?: TargetCatalog.bilibili.id
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        if (page != "records") page = "home"
        restPage = savedInstanceState?.getBoolean("restPage") ?: intent.getBooleanExtra("timeout", false)
        render()
    }

    private fun render() {
        status = null; dial = null; summary = null; primary = null; pause = null; history = null; rows.clear()
        val (root, body) = NightUi.activityShell(this)
        shell = root; content = body
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(NightUi.text(this, "FocusGate", 23f), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(NightUi.button(this, "设置", quiet = true) { openSettings() })
        content.addView(header)
        if (page == "records") buildRecords() else if (restPage) buildRest() else buildHome()
        NightUi.navigation(shell, page) { destination ->
            if (destination == "settings") openSettings()
            else { page = destination; restPage = false; render() }
        }
        update()
    }

    private fun addText(label: String, size: Float = 15f, muted: Boolean = false) =
        NightUi.text(this, label, size, muted).also { content.addView(it) }

    private fun buildHome() {
        addText("每一次打开，都由你决定。", 13f, true)
        val choices = LinearLayout(this).apply {
            background = NightUi.background(this@TrialActivity, radius = 28)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        TargetCatalog.enabled.forEach { app ->
            choices.addView(NightUi.button(this, app.displayName) {
                selected = app.id; restPage = false; render()
            }.apply { NightUi.styleButton(this, selected = selected == app.id) }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        content.addView(choices, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(12) })
        status = addText("", 12f, true)
        addDial()
        primary = NightUi.addButton(content, "打开应用", primary = true) { actOnSelection() }
        pause = NightUi.addButton(content, "暂停此应用限制", quiet = true) { togglePause() }
        summary = addText("", 15f).apply {
            background = NightUi.background(this@TrialActivity)
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        NightUi.space(content)
        addText("应用状态", 13f, true)
        TargetCatalog.enabled.forEach { app ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val name = NightUi.button(this, app.displayName, quiet = true) { selected = app.id; render() }
            name.contentDescription = "查看${app.displayName}状态"
            row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            rows[app.id] = NightUi.text(this, "", 12f, true).apply { gravity = Gravity.END }
            row.addView(rows.getValue(app.id), LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(row)
            NightUi.divider(content)
        }
        addText("仅统计可确认的前台使用，历史可能有缺失。", 12f, true)
    }

    private fun addDial() {
        dial = NightDial(this)
        content.addView(dial, LinearLayout.LayoutParams(minOf(dp(248), resources.displayMetrics.widthPixels - dp(64)), -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12); bottomMargin = dp(16)
        })
    }

    private fun buildRest() {
        NightUi.addButton(content, "‹ 返回首页", quiet = true) { restPage = false; render() }
        addText("${TargetCatalog.find(selected).displayName} · 本次使用已结束", 13f, true)
        addText("到这里，\n刚刚好。", 32f)
        status = addText("", 12f, true)
        addDial()
        summary = addText("", 14f)
        addText("冷却只影响当前应用。离开此页，休息时间也会继续。", 13f, true)
        primary = NightUi.addButton(content, "返回桌面", primary = true) {
            // This is an explicit user action, not a delayed timeout task.
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { toast("请按手机系统Home返回桌面。") }
        }
        pause = NightUi.addButton(content, "暂停此应用限制", quiet = true) { togglePause() }
    }

    private fun buildRecords() {
        addText("最近的主动选择", 26f)
        addText("理由和记录仅保存在手机本地。统计可能不完整。", 13f, true)
        history = addText("", 14f)
        NightUi.addButton(content, "删除已结束记录与理由") {
            AlertDialog.Builder(this).setTitle("删除历史与理由？")
                .setMessage("删除三款应用已结束会话明细和理由。保留各自用量、当前会话及冷却，不重置限制。")
                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                    TrialRuntime.engines.values.forEach { it.history.clear() }
                    if (!TrialRuntime.save()) toast(TrialRuntime.storageError!!)
                    update()
                }.show()
        }
    }

    private fun actOnSelection() {
        val engine = TrialRuntime.engine(selected)
        when {
            !engine.enabled || ProbeRuntime.targets.binding(selected) == null -> openSettings()
            !engine.paused && engine.cooldownRemaining(SystemClock.elapsedRealtime()) > 0 -> { restPage = true; render() }
            else -> {
                val launch = ProbeRuntime.targets.binding(selected)?.app?.packageName?.let { packageManager.getLaunchIntentForPackage(it) }
                if (launch == null) toast("应用无法打开，请在设置中重新核实绑定。") else startActivity(launch)
            }
        }
    }

    private fun togglePause() {
        val id = selected
        val engine = TrialRuntime.engine(id)
        if (!engine.enabled) return
        ProbeRuntime.service?.requestCheck("trial_home_pause_recheck")
        val oldPaused = engine.paused
        engine.setPaused(!oldPaused, System.currentTimeMillis())
        if (TrialRuntime.save()) ProbeRuntime.record(if (engine.paused) "TRIAL_USER_PAUSED" else "TRIAL_RESTRICTIONS_RESUMED", associatedTarget = id)
        else { engine.paused = oldPaused; toast(TrialRuntime.storageError!!) }
        ProbeRuntime.service?.requestCheck("trial_pause_changed")
        update()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        intent.getStringExtra("targetAppId")?.let { value -> TargetCatalog.enabled.firstOrNull { it.id.value == value }?.let { selected = it.id } }
        page = if (intent.getStringExtra("page") == "records") "records" else "home"
        restPage = intent.getBooleanExtra("timeout", false)
        recordTimeoutPage(); render()
    }
    override fun onResume() {
        super.onResume(); recordTimeoutPage()
        ProbeRuntime.service?.requestCheck("trial_home_resumed")
        handler.removeCallbacks(refresh); handler.post(refresh)
    }
    private fun recordTimeoutPage() {
        val exitObserved = intent.getLongExtra("timeoutObservedElapsedMs", -1)
        if (exitObserved >= 0) {
            val id = intent.getStringExtra("targetAppId")?.let(::TargetAppId)
            ProbeRuntime.record("TRIAL_TIMEOUT_PAGE_RESUMED", associatedTarget = id, details = mapOf(
                "permitId" to intent.getStringExtra("timeoutPermitId"), "afterObservedExitMs" to (SystemClock.elapsedRealtime() - exitObserved)))
            intent.removeExtra("timeoutObservedElapsedMs")
        }
    }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selectedTarget", selected.value); outState.putString("page", page); outState.putBoolean("restPage", restPage)
        super.onSaveInstanceState(outState)
    }

    private fun stateLabel(app: TargetApp): String {
        val e = TrialRuntime.engine(app.id)
        return when {
            !e.enabled -> "未开启"
            ProbeRuntime.targets.binding(app.id) == null -> "缺少绑定"
            e.paused -> "已暂停"
            TrialRuntime.storageError != null -> "保存异常"
            ProbeRuntime.service == null -> "服务未连接"
            e.cooldownRemaining(SystemClock.elapsedRealtime()) > 0 -> "冷却中"
            e.session != null -> if (e.running) "使用中" else "会话保留中"
            else -> "限制已开启"
        }
    }

    private fun update() {
        if (!::content.isInitialized) return
        val app = TargetCatalog.find(selected)
        val engine = TrialRuntime.engine(selected)
        val now = SystemClock.elapsedRealtime()
        val cooling = engine.cooldownRemaining(now)
        val age = ProbeRuntime.lastSampleElapsedMs?.let { now - it }
        status?.text = when {
            TrialRuntime.storageError != null -> "限制不可用：${TrialRuntime.storageError}"
            TrialRuntime.engines.values.none { it.enabled } -> "尚未开启限制，请到设置选择应用。"
            ProbeRuntime.service == null -> "限制不可用：无障碍服务未连接"
            TrialUiStatus.overlayError != null -> "限制异常：${TrialUiStatus.overlayError}"
            age == null || age > 5000 -> "待核对：尚无近期窗口采样"
            ProbeRuntime.state.kind == ForegroundKind.UNKNOWN -> "窗口暂时未知，等待核对"
            else -> "服务已连接 · 各应用独立管理"
        }
        val active = engine.session
        val blocked = !engine.enabled || engine.paused || ProbeRuntime.targets.binding(selected) == null
        val value: String
        val detail: String
        val progress: Float
        when {
            !engine.enabled -> { value = "未开启"; detail = "在设置中选择是否开启"; progress = 0f }
            ProbeRuntime.targets.binding(selected) == null -> { value = "待绑定"; detail = "先核实手机中实际应用"; progress = 0f }
            engine.paused -> { value = "已暂停"; detail = "仍记录可确认的前台使用"; progress = 0f }
            TrialRuntime.storageError != null || ProbeRuntime.service == null -> { value = "待恢复"; detail = "请到设置检查运行状态"; progress = 0f }
            cooling > 0 -> { value = nightClock(cooling); detail = "距离可以再次使用"; progress = cooling.toFloat() / TrialEngine.COOLDOWN_MS }
            active != null -> { value = nightClock(engine.remainingMs()); detail = if (engine.running) "本次剩余 · 正在计时" else "本次剩余 · 已暂离"; progress = engine.remainingMs().toFloat() / active.approvedMs.coerceAtLeast(1) }
            restPage -> { value = "休息结束"; detail = "再次打开时需重新确认"; progress = 0f }
            else -> { value = "先想一下"; detail = "把理由留给这次打开"; progress = 0f }
        }
        dial?.bind("${app.displayName} · ${stateLabel(app)}", value, detail, progress)
        primary?.apply {
            text = if (restPage) "返回桌面" else when {
                blocked && !engine.paused -> "管理${app.displayName}限制"
                cooling > 0 && !engine.paused -> "查看休息页"
                active != null -> "返回${app.displayName}"
                else -> "打开${app.displayName}"
            }
        }
        pause?.apply {
            visibility = if (engine.enabled) View.VISIBLE else View.GONE
            text = if (engine.paused) "恢复${app.displayName}限制（需重新确认）" else "暂停${app.displayName}限制"
        }
        val today = LocalDate.now().toString()
        summary?.text = if (restPage) {
            val last = engine.history.lastOrNull { it.endReason == "timeout" }?.session
            if (last != null) "${app.displayName}\n原预计 ${formatTrialDuration(last.expectedMs)} · 批准 ${formatTrialDuration(last.approvedMs)}\n实际使用 ${formatTrialDuration(last.usedMs)}"
            else "${app.displayName} · 独立冷却，不影响其他应用"
        } else {
            val total = TrialRuntime.engines.values.sumOf { it.usageByDay[today] ?: 0 }
            "今日已记录  ${formatTrialDuration(total)}\n统计不完整 · ${app.displayName} ${formatTrialDuration(engine.usageByDay[today] ?: 0)}"
        }
        rows.forEach { (id, text) ->
            val e = TrialRuntime.engine(id)
            val cooldown = e.cooldownRemaining(now)
            text.text = stateLabel(TargetCatalog.find(id)) + if (e.enabled && !e.paused && cooldown > 0) "\n${nightClock(cooldown)}" else ""
        }
        history?.text = TrialRuntime.engines.values.flatMap { it.history }.sortedByDescending { it.endedWallMs }.take(10).joinToString("\n\n────────────\n\n") { record ->
            val s = record.session
            val time = Instant.ofEpochMilli(s.startedWallMs).atZone(ZoneId.systemDefault()).toLocalDateTime()
            "$time · ${TargetCatalog.find(TargetAppId(s.targetId)).displayName}\n\n${s.reason}\n\n预计 ${formatTrialDuration(s.expectedMs)} · 批准 ${formatTrialDuration(s.approvedMs)}\n实际 ${formatTrialDuration(s.usedMs)}\n${trialEndLabel(record.endReason)}"
        }.ifEmpty { "还没有已结束的会话。\n完成一次主动选择后，记录会出现在这里。" }
    }
    private fun openSettings() { startActivity(Intent(this, TrialSettingsActivity::class.java)) }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = NightUi.dp(this, value)
}
