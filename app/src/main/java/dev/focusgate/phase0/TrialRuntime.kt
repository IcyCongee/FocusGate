package dev.focusgate.phase0

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Main-thread owned, with atomic checkpoints before any new permit is released. */
object TrialRuntime {
    val engines = TargetCatalog.enabled.associate { it.id to TrialEngine(it.id) }.toMutableMap()
    fun engine(id: TargetAppId) = engines.getValue(id)
    var storageError: String? = null
        private set
    var overlayVisible = false
        internal set
    var overlayTargetId: TargetAppId? = null
        internal set
    private lateinit var file: AtomicFile
    private var initialized = false
    private var restoreFailed = false

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        file = AtomicFile(File(context.filesDir, "trial-state.json"))
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return
        runCatching {
            val json = JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
            val restored = TrialStateCodec.decode(json, System.currentTimeMillis(), SystemClock.elapsedRealtime())
            engines.clear()
            engines.putAll(restored)
        }.onFailure {
            restoreFailed = true
            engines.values.forEach { it.enabled = false }
            storageError = "无法读取本地执行状态，限制尚未启用。请保留应用数据并导出诊断日志。"
            ProbeRuntime.record("TRIAL_RESTORE_FAILED", details = mapOf("error" to it.javaClass.simpleName))
        }
    }

    fun save(): Boolean {
        if (restoreFailed) return false // Never overwrite an unreadable state with partial defaults.
        var stream: java.io.FileOutputStream? = null
        return try {
            val json = TrialStateCodec.encode(engines)
            stream = file.startWrite()
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
            storageError = null
            true
        } catch (e: Exception) {
            file.failWrite(stream)
            storageError = "本地保存失败，尚未放行；请返回首页重试。"
            ProbeRuntime.record("TRIAL_SAVE_FAILED", details = mapOf("error" to e.javaClass.simpleName))
            false
        }
    }

}

fun trialEndLabel(reason: String) = when (reason) {
    "timeout" -> "本次时间已用完"
    "grace_expired" -> "离开超过10分钟，会话结束"
    "user_paused" -> "你已暂停限制"
    "restrictions_resumed" -> "恢复限制，旧会话结束"
    "monitoring_interrupted" -> "监控中断，需重新确认"
    "binding_changed" -> "目标绑定发生变化"
    "target_disabled" -> "已关闭该应用限制"
    "save_failed" -> "保存失败，未放行"
    else -> reason
}
