package dev.focusgate.phase0

import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.ZoneId

/** Advanced diagnostics remain accessible separately from the trial home page. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var target: TextView
    private lateinit var candidate: TextView
    private lateinit var events: TextView
    private lateinit var roundInput: EditText
    private var selectedTarget = TargetCatalog.douyin
    private lateinit var bindButton: Button
    private val refresh = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1000) // Only while this activity is visible.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProbeRuntime.initialize(this)
        val selectedId = savedInstanceState?.getString("selectedTarget") ?: intent.getStringExtra("targetAppId")
        selectedTarget = TargetCatalog.enabled.firstOrNull { it.id.value == selectedId } ?: TargetCatalog.douyin
        val scroll = ScrollView(this).apply { setBackgroundColor(NightUi.color(this@MainActivity, R.color.night_background)) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }
        scroll.addView(content)
        // Android 15 / target 35 edge-to-edge: leave controls clear of all system bars.
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        scroll.requestApplyInsets()

        fun text(value: String, heading: Boolean = false): TextView = TextView(this).apply {
            this.text = value
            setTextColor(NightUi.color(this@MainActivity, if (heading) R.color.night_text else R.color.night_muted))
            textSize = if (heading) 21f else 14f
            setPadding(0, dp(10), 0, dp(8))
            if (heading) setTypeface(typeface, Typeface.BOLD)
            else setTextIsSelectable(true)
            content.addView(this)
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            this.text = label
            NightUi.styleButton(this)
            content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            setOnClickListener { action() }
        }
        text("FocusGate · 诊断与应用绑定", true)
        button("返回试用首页") { startActivity(Intent(this, TrialActivity::class.java)); finish() }
        text("此页用于权限、应用绑定和故障日志。试用限制是否启用，请查看首页；日志和单元测试不能代替真机验证。")
        text("设备与构建", true)
        text("${Build.MANUFACTURER} / ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n构建 ${Build.DISPLAY}\n安全补丁 ${Build.VERSION.SECURITY_PATCH}\n基线：Android 15 / target SDK ${applicationInfo.targetSdkVersion}\nAPK ${BuildConfig.APPLICATION_ID}\n${BuildConfig.VERSION_NAME} / ${BuildConfig.BUILD_ID}\n时区 ${ZoneId.systemDefault().id}\n进程观察标识 ${ProbeRuntime.processSessionId}")
        text("权限与真实连接", true)
        status = text("")
        button("打开无障碍设置（由你手动启用）") { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
        button("打开使用情况访问设置（独立授权）") { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
        button("主动核对当前窗口") {
            ProbeRuntime.service?.requestCheck("manual_diagnostic_check") ?: toast("服务尚未连接，请先启用无障碍服务")
            updateStatus()
        }
        text("应用绑定（绑定不等于开启限制）", true)
        button("选择要核实的应用：抖音 / Bilibili / 小红书") {
            AlertDialog.Builder(this).setTitle("选择应用").setItems(TargetCatalog.enabled.map { it.displayName }.toTypedArray()) { _, index ->
                selectedTarget = TargetCatalog.enabled[index]
                updateStatus()
            }.show()
        }
        target = text("")
        text("核实步骤：先启用服务，打开你要绑定的实际应用，再切回此页。下方只显示近期真实应用窗口观察到的候选；名称本身不作为识别依据。请对照系统应用信息或 ADB 包信息后确认。桌面、输入法和系统窗口不会被用作候选。")
        candidate = text("")
        bindButton = button("核实并绑定近期观察到的应用") { confirmCandidate() }
        button("打开已核实目标（辅助往返验证）") {
            val pkg = ProbeRuntime.targets.binding(selectedTarget.id)?.app?.packageName
            val launch = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
            if (launch == null) toast("尚未核实目标，或该应用已不可用")
            else startActivity(launch)
        }
        button("清除包名绑定（保留原诊断日志）") {
            val chosen = selectedTarget
            AlertDialog.Builder(this).setTitle("清除${selectedTarget.displayName}映射？")
                .setMessage("后续前台观察不再标记为目标；旧日志仍保留原目标归属和当时的包名。")
                .setNegativeButton("取消", null).setPositiveButton("清除") { _, _ ->
                    if (ProbeRuntime.targets.clear(chosen.id)) {
                        ProbeRuntime.record("TARGET_BINDING_CLEARED", associatedTarget = chosen.id)
                        ProbeRuntime.service?.requestCheck("target_binding_cleared", resetPresence = true)
                        updateStatus()
                    } else toast("保存失败，绑定未被确认清除")
                }.show()
        }
        text("实验标记与证据", true)
        roundInput = EditText(this).apply {
            NightUi.field(this)
            hint = "轮次标识（字母、数字、点、横线或下划线，最多24字符）"
            setSingleLine(true)
            setText(ProbeRuntime.round)
            content.addView(this)
        }
        button("标记 E01 环境/授权轮次") { markRound("E01") }
        button("标记 E02 识别/恢复轮次") { markRound("E02") }
        button("导出本地 JSONL 诊断日志") {
            ProbeRuntime.record("EXPORT_REQUESTED")
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/x-ndjson"
                putExtra(Intent.EXTRA_TITLE, "FocusGate-${BuildConfig.BUILD_ID}-${System.currentTimeMillis()}.jsonl")
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, EXPORT_REQUEST)
        }
        text("记录包含包名、窗口类型、屏幕/锁屏、许可和计时状态及双时钟；不包含使用理由、页面文本或视频。日志仅存本机，最多约1.5 MiB轮转，导出只保存仍保留的记录；进程中断和轮转丢弃的区间均不声称完整。请选择本机存储，若你选择云端文档提供方，文件将由该提供方处理。Usage Access 只展示授权状态，尚未读取历史用量。")
        text("近期事件（最新在前，最多100条）", true)
        events = text("")
        events.typeface = Typeface.MONOSPACE
        events.textSize = 11f
    }

    override fun onResume() {
        super.onResume()
        ProbeRuntime.service?.requestCheck("diagnostic_activity_resumed")
        ProbeRuntime.record("DIAGNOSTIC_RESUMED", details = mapOf("usageAccessGranted" to usageAccessGranted(), "accessibilityEnabled" to accessibilityEnabled()))
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun updateStatus() {
        val connected = ProbeRuntime.service != null
        val nowScreen = readScreen(this)
        val age = ProbeRuntime.lastSampleElapsedMs?.let { "${SystemClock.elapsedRealtime() - it} ms前" } ?: "尚无"
        val state = ProbeRuntime.state
        status.setIfChanged("无障碍设置开关：${yesNo(accessibilityEnabled())}\n实际服务实例：${if (connected) "已连接（本进程回调证据）" else "未连接 / 待手动恢复"}\n使用情况访问：${yesNo(usageAccessGranted())}（尚未读取历史用量）\n普通悬浮窗授权：${yesNo(Settings.canDrawOverlays(this))}（不需要）\n当前屏幕可交互：${yesNo(nowScreen.interactive)}；锁屏：${yesNo(nowScreen.locked)}\n用户凭据曾解锁：${yesNo(nowScreen.userUnlocked)}（不同于普通锁屏）\n窗口判定：${state.kind} / ${state.packageName ?: "未知"}\n判定依据：${state.reason}\n最近窗口采样：$age；状态版本：${ProbeRuntime.stateVersion}\n采样年龄不是服务死亡证据；未连接/未知时不宣称前台已确认。\n试用限制：${TargetCatalog.enabled.joinToString { it.displayName + if (TrialRuntime.engine(it.id).enabled) "已开启" else "已关闭" }}；历史统计：不完整。\n日志写入：${ProbeRuntime.persistenceError ?: "未报告错误（缓冲写入，异常中断可能丢失尾部）"}")
        bindButton.text = "核实并绑定近期应用为${selectedTarget.displayName}"
        val binding = ProbeRuntime.targets.binding(selectedTarget.id)
        target.setIfChanged(if (binding == null) "targetAppId=${selectedTarget.id.value}\n包名：未核实 / 未配置；不会猜测包名。" else {
            val current = ProbeRuntime.targets.inspect(binding.app.packageName)
            "targetAppId=${binding.targetAppId.value}\n已核实包名：${binding.app.packageName}\n应用：${binding.app.label} ${binding.app.versionName} (${binding.app.versionCode})\n安装来源：${binding.app.installer ?: "系统未提供"}\n用户核实时间：${Instant.ofEpochMilli(binding.confirmedWallMs).atZone(ZoneId.systemDefault())}\n当前安装状态：${when { current == null -> "未能读取，请重新核实"; current.versionCode != binding.app.versionCode -> "版本已变化，请重新核实"; else -> "可读取，版本与核实记录一致" }}"
        })
        val observed = ProbeRuntime.candidate
        candidate.setIfChanged(observed?.let {
            "最近真实窗口候选：${it.label}\n${it.packageName}\n版本 ${it.versionName} (${it.versionCode})\n观察年龄 ${(SystemClock.elapsedRealtime() - it.observedElapsedMs) / 1000} 秒（超过180秒需重新观察）"
        } ?: "尚无可核实候选。服务连接后，请打开所选实际应用，再返回此页。")
        events.setIfChanged(ProbeRuntime.recentLines())
    }

    private fun confirmCandidate() {
        val chosen = selectedTarget
        val app = ProbeRuntime.candidate
        if (ProbeRuntime.service == null || app == null || SystemClock.elapsedRealtime() - app.observedElapsedMs !in 0..180_000) {
            toast("请先连接服务，打开所选实际应用，再在180秒内返回核实")
            return
        }
        if (ProbeRuntime.targets.boundElsewhere(app.packageName, chosen.id)) {
            toast("此包名已绑定其他应用，不能重复绑定。请先打开正确的应用。")
            return
        }
        AlertDialog.Builder(this).setTitle("你确认这是实际使用的${chosen.displayName}吗？")
            .setMessage("${app.label}\n${app.packageName}\n${app.versionName} (${app.versionCode})\n安装来源：${app.installer ?: "未提供"}\n\n此候选来自真实应用窗口，但仍需你对照系统应用信息核实。名称相同不代表就是目标。确认后映射到 targetAppId=${chosen.id.value}。")
            .setNegativeButton("不是 / 取消", null)
            .setPositiveButton("已核对，确认绑定") { _, _ ->
                if (SystemClock.elapsedRealtime() - app.observedElapsedMs !in 0..180_000 || ProbeRuntime.service == null) {
                    toast("观察已过期或服务已断开，请重新观察")
                } else if (ProbeRuntime.targets.confirm(app, chosen.id)) {
                    ProbeRuntime.record("TARGET_BINDING_VERIFIED", app.packageName, associatedTarget = chosen.id, details = mapOf("verificationMethod" to "observed_window_and_user_confirmation", "observedWallMs" to app.observedWallMs, "versionName" to app.versionName, "versionCode" to app.versionCode))
                    ProbeRuntime.service?.requestCheck("target_binding_verified", resetPresence = true)
                    updateStatus()
                } else toast("保存失败或应用版本变化，未完成绑定，请重新观察")
            }.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selectedTarget", selectedTarget.id.value)
        super.onSaveInstanceState(outState)
    }

    private fun markRound(experiment: String) {
        val value = roundInput.text.toString()
        if (!value.matches(Regex("[A-Za-z0-9._-]{1,24}"))) {
            toast("轮次标识须为1～24个字母、数字、点、横线或下划线")
            return
        }
        ProbeRuntime.experiment = experiment
        ProbeRuntime.round = value
        ProbeRuntime.record("EXPERIMENT_MARKER")
        toast("已标记 $experiment / $value")
        updateStatus()
    }

    private fun accessibilityEnabled(): Boolean {
        val wanted = ComponentName(this, ForegroundProbeService::class.java)
        return Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == wanted } == true
    }

    private fun usageAccessGranted(): Boolean = getSystemService(AppOpsManager::class.java)
        .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED

    private fun openSettings(action: String) {
        runCatching { startActivity(Intent(action)) }.onFailure { toast("系统未提供该设置入口，请手动在系统设置中查找") }
    }

    @Deprecated("Platform activity result used to avoid unrelated UI dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == EXPORT_REQUEST && resultCode == RESULT_OK) data?.data?.let { uri ->
            ProbeRuntime.export(uri) { error -> runOnUiThread { toast(error ?: "已导出保留的本地诊断日志") } }
        }
    }

    private fun TextView.setIfChanged(value: String) { if (text.toString() != value) text = value }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun yesNo(value: Boolean) = if (value) "是" else "否"
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object { private const val EXPORT_REQUEST = 1001 }
}
