package dev.focusgate.phase0

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import org.json.JSONObject

data class ObservedApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val installer: String?,
    val observedWallMs: Long,
    val observedElapsedMs: Long,
)

data class VerifiedBinding(
    val targetAppId: TargetAppId,
    val app: ObservedApp,
    val confirmedWallMs: Long,
)

/** The only package-to-target mapping. No guessed target package, including as a fallback. */
class TargetRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("verified_targets", Context.MODE_PRIVATE)

    fun binding(targetId: TargetAppId = TargetCatalog.douyin.id): VerifiedBinding? = runCatching {
        val json = JSONObject(prefs.getString(targetId.value, null) ?: return null)
        require(json.getString("targetAppId") == targetId.value)
        VerifiedBinding(
            targetId,
            ObservedApp(
                json.getString("packageName"), json.getString("label"), json.getString("versionName"),
                json.getLong("versionCode"), json.optString("installer").takeIf { it.isNotBlank() },
                json.getLong("observedWallMs"), json.getLong("observedElapsedMs"),
            ), json.getLong("confirmedWallMs"),
        )
    }.getOrNull()

    fun verifiedPackages(): Map<String, TargetAppId> = TargetCatalog.enabled.mapNotNull { binding(it.id) }
        .associate { it.app.packageName to it.targetAppId }

    fun boundElsewhere(pkg: String, targetId: TargetAppId): Boolean = TargetCatalog.enabled.any {
        it.id != targetId && binding(it.id)?.app?.packageName == pkg
    }

    fun inspect(packageName: String): ObservedApp? = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(packageName, 0)
        val application = requireNotNull(info.applicationInfo)
        ObservedApp(
            packageName, context.packageManager.getApplicationLabel(application).toString(),
            info.versionName ?: "unknown", info.longVersionCode,
            context.packageManager.getInstallSourceInfo(packageName).installingPackageName,
            System.currentTimeMillis(), SystemClock.elapsedRealtime(),
        )
    }.getOrNull()

    fun confirm(app: ObservedApp, targetId: TargetAppId = TargetCatalog.douyin.id): Boolean {
        require(TargetCatalog.enabled.any { it.id == targetId })
        if (boundElsewhere(app.packageName, targetId)) return false
        // Do not silently bind an app that changed between observation and confirmation.
        val current = inspect(app.packageName) ?: return false
        if (current.versionCode != app.versionCode) return false
        val json = JSONObject()
            .put("targetAppId", targetId.value)
            .put("packageName", app.packageName).put("label", app.label)
            .put("versionName", app.versionName).put("versionCode", app.versionCode)
            .put("installer", app.installer.orEmpty())
            .put("observedWallMs", app.observedWallMs).put("observedElapsedMs", app.observedElapsedMs)
            .put("confirmedWallMs", System.currentTimeMillis())
        return prefs.edit().putString(targetId.value, json.toString()).commit()
    }

    fun clear(targetId: TargetAppId = TargetCatalog.douyin.id): Boolean = prefs.edit().remove(targetId.value).commit()
}
