package dev.focusgate.phase0

import org.json.JSONArray
import org.json.JSONObject

/** Schema 1 belongs only to Douyin. Decode into fresh objects before replacing live state. */
object TrialStateCodec {
    fun decode(json: JSONObject, wallMs: Long, elapsedMs: Long): Map<TargetAppId, TrialEngine> {
        val version = json.getInt("schemaVersion")
        require(version in 1..2)
        val result = TargetCatalog.enabled.associate { it.id to TrialEngine(it.id) }
        if (version == 1) readEngine(json, result.getValue(TargetCatalog.douyin.id), wallMs, elapsedMs)
        else {
            val items = json.getJSONObject("targets")
            TargetCatalog.enabled.forEach { app ->
                readEngine(items.getJSONObject(app.id.value), result.getValue(app.id), wallMs, elapsedMs)
            }
        }
        return result
    }

    fun encode(engines: Map<TargetAppId, TrialEngine>): JSONObject {
        val items = JSONObject()
        TargetCatalog.enabled.forEach { items.put(it.id.value, writeEngine(engines.getValue(it.id))) }
        return JSONObject().put("schemaVersion", 2).put("targets", items)
    }

    private fun readEngine(json: JSONObject, engine: TrialEngine, wallMs: Long, elapsedMs: Long) {
        require(json.getString("targetAppId") == engine.targetId.value)
        engine.enabled = json.getBoolean("enabled")
        engine.paused = json.getBoolean("paused")
        engine.cooldownUntilWallMs = json.getLong("cooldownUntilWallMs")
        val remaining = (engine.cooldownUntilWallMs - wallMs).coerceIn(0, TrialEngine.COOLDOWN_MS)
        engine.cooldownUntilElapsedMs = elapsedMs + remaining
        val days = json.getJSONObject("usageByDay")
        days.keys().forEach { key -> engine.usageByDay[key] = days.getLong(key).also { require(it >= 0) } }
        val history = json.getJSONArray("history")
        for (i in maxOf(0, history.length() - 100) until history.length()) {
            val item = history.getJSONObject(i)
            engine.history += TrialRecord(readSession(item.getJSONObject("session"), engine.targetId), item.getLong("endedWallMs"), item.getString("endReason"))
        }
        json.optJSONObject("session")?.let { engine.recoveredSession(readSession(it, engine.targetId), wallMs) }
        engine.incomplete = true
    }

    private fun writeEngine(engine: TrialEngine): JSONObject {
        val history = JSONArray()
        engine.history.forEach { history.put(JSONObject().put("session", sessionJson(it.session)).put("endedWallMs", it.endedWallMs).put("endReason", it.endReason)) }
        val days = JSONObject()
        engine.usageByDay.forEach { (date, used) -> days.put(date, used) }
        return JSONObject().put("targetAppId", engine.targetId.value)
            .put("enabled", engine.enabled).put("paused", engine.paused)
            .put("cooldownUntilWallMs", engine.cooldownUntilWallMs)
            .put("usageByDay", days).put("history", history)
            .put("session", engine.session?.let(::sessionJson) ?: JSONObject.NULL)
    }

    private fun sessionJson(s: TrialSession) = JSONObject().put("id", s.id).put("targetAppId", s.targetId)
        .put("reason", s.reason).put("expectedMs", s.expectedMs).put("approvedMs", s.approvedMs)
        .put("startedWallMs", s.startedWallMs).put("usedMs", s.usedMs)

    private fun readSession(json: JSONObject, targetId: TargetAppId): TrialSession {
        require(json.getString("targetAppId") == targetId.value)
        return TrialSession(json.getString("id"), targetId.value, json.getString("reason"),
            json.getLong("expectedMs"), json.getLong("approvedMs"), json.getLong("startedWallMs"), json.getLong("usedMs"))
    }
}
