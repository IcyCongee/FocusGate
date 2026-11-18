package dev.focusgate.phase0

import android.app.KeyguardManager
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executors

fun readScreen(context: Context): ScreenSample = ScreenSample(
    context.getSystemService(PowerManager::class.java).isInteractive,
    context.getSystemService(KeyguardManager::class.java).isKeyguardLocked,
    context.getSystemService(UserManager::class.java).isUserUnlocked,
)

/** In-process connection evidence is never persisted as a claim that a service is alive. */
object ProbeRuntime {
    private lateinit var appContext: Context
    lateinit var targets: TargetRepository
        private set
    var service: ForegroundProbeService? = null
        internal set
    var state = ForegroundState(ForegroundKind.UNKNOWN, reason = "service_not_connected")
        internal set
    var screen: ScreenSample? = null
        internal set
    var windows: List<WindowSample> = emptyList()
        internal set
    var lastSampleElapsedMs: Long? = null
        internal set
    var candidate: ObservedApp? = null
        internal set
    var stateVersion = 0L
        internal set
    var experiment = "E02"
    var round = "1"
    val processSessionId: String = UUID.randomUUID().toString()
    @Volatile var persistenceError: String? = null
        private set
    private val writer = Executors.newSingleThreadExecutor()
    private val recent = ArrayDeque<String>()
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        targets = TargetRepository(appContext)
        initialized = true
        TrialRuntime.initialize(appContext)
        record("PROCESS_STARTED", details = mapOf("historyComplete" to false, "note" to "No backfill; previous monitoring gaps remain unknown"))
    }

    fun record(
        type: String,
        sourcePackage: String? = null,
        associatedTarget: TargetAppId? = state.targetAppId ?: TrialRuntime.overlayTargetId,
        details: Map<String, Any?> = emptyMap(),
    ) {
        if (!initialized) return
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val binding = associatedTarget?.let { targets.binding(it) }
        val engine = associatedTarget?.let { TrialRuntime.engines[it] }
        val json = JSONObject()
            .put("schemaVersion", 3).put("buildId", BuildConfig.BUILD_ID)
            .put("processSessionId", processSessionId).put("experimentId", experiment).put("round", round)
            .put("targetAppId", associatedTarget?.value ?: JSONObject.NULL)
            .put("verifiedPackage", binding?.app?.packageName ?: JSONObject.NULL)
            .put("resolvedTargetAppId", state.targetAppId?.value ?: JSONObject.NULL)
            .put("wallTimeMs", wall).put("wallTimeIso", Instant.ofEpochMilli(wall).atZone(ZoneId.systemDefault()).toString())
            .put("timeZone", ZoneId.systemDefault().id).put("elapsedRealtimeMs", elapsed)
            .put("uptimeMs", SystemClock.uptimeMillis()).put("eventType", type)
            .put("sourcePackage", sourcePackage ?: JSONObject.NULL)
            .put("serviceConnected", service != null).put("stateVersion", stateVersion)
            .put("foregroundKind", state.kind.name).put("foregroundPackage", state.packageName ?: JSONObject.NULL)
            .put("reason", state.reason).put("screenInteractive", screen?.interactive ?: JSONObject.NULL)
            .put("keyguardLocked", screen?.locked ?: JSONObject.NULL)
            .put("userUnlocked", screen?.userUnlocked ?: JSONObject.NULL)
            .put("permitId", engine?.session?.id ?: JSONObject.NULL)
            .put("overlayType", if (TrialRuntime.overlayVisible) "ACCESSIBILITY_OVERLAY" else "NONE")
            .put("timerRunning", engine?.running ?: false)
            .put("usageAccounting", "LOCAL_OBSERVATIONS_ONLY_INCOMPLETE")
        val windowArray = JSONArray()
        windows.forEach {
            windowArray.put(JSONObject().put("id", it.id).put("type", it.kind.name).put("layer", it.layer)
                .put("active", it.active).put("focused", it.focused).put("packageName", it.packageName ?: JSONObject.NULL))
        }
        json.put("windows", windowArray)
        val mappings = JSONObject()
        TargetCatalog.enabled.forEach { app -> mappings.put(app.id.value, targets.binding(app.id)?.app?.packageName ?: JSONObject.NULL) }
        json.put("targetMappings", mappings)
        val detailJson = JSONObject()
        details.forEach { (key, value) -> detailJson.put(key, value ?: JSONObject.NULL) }
        json.put("details", detailJson)
        val line = json.toString()
        synchronized(recent) {
            recent.addLast("${Instant.ofEpochMilli(wall).atZone(ZoneId.systemDefault()).toLocalTime()}  $type  ${state.kind}  ${sourcePackage.orEmpty()}")
            while (recent.size > 100) recent.removeFirst()
        }
        Log.i("FocusGateProbe", line)
        writer.execute {
            runCatching {
                val dir = File(appContext.filesDir, "diagnostics").apply { mkdirs() }
                val current = File(dir, "events.jsonl")
                if (current.length() >= 512 * 1024) {
                    val oldest = File(dir, "events.2.jsonl")
                    check(!oldest.exists() || oldest.delete()) { "Cannot rotate oldest diagnostic file" }
                    val previous = File(dir, "events.1.jsonl")
                    check(!previous.exists() || previous.renameTo(oldest)) { "Cannot rotate diagnostic file" }
                    check(current.renameTo(previous)) { "Cannot rotate active diagnostic file" }
                }
                current.appendText("$line\n", Charsets.UTF_8)
            }.onFailure { persistenceError = "诊断日志写入失败：${it.javaClass.simpleName}" }
        }
    }

    fun recentLines(): String = synchronized(recent) { recent.reversed().joinToString("\n") }

    /** The same executor orders export after prior writes. Nothing is uploaded automatically. */
    fun export(uri: Uri, callback: (String?) -> Unit) {
        writer.execute {
            val error = runCatching {
                appContext.contentResolver.openOutputStream(uri, "wt").use { output ->
                    requireNotNull(output) { "Cannot open destination" }
                    listOf("events.2.jsonl", "events.1.jsonl", "events.jsonl").forEach { name ->
                        val file = File(appContext.filesDir, "diagnostics/$name")
                        if (file.exists()) file.inputStream().use { it.copyTo(output) }
                    }
                }
            }.exceptionOrNull()?.let { "导出失败：${it.javaClass.simpleName}" }
            callback(error)
        }
    }
}
