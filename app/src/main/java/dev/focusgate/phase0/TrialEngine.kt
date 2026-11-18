package dev.focusgate.phase0

import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** First usable slice. Daily quotas/extensions are deliberately not represented as implemented. */
data class TrialSession(
    val id: String,
    val targetId: String,
    val reason: String,
    val expectedMs: Long,
    val approvedMs: Long,
    val startedWallMs: Long,
    var usedMs: Long = 0,
    var awaySinceElapsedMs: Long? = null,
)

data class TrialRecord(val session: TrialSession, val endedWallMs: Long, val endReason: String)

class TrialEngine(val targetId: TargetAppId = TargetCatalog.douyin.id) {
    var enabled = false
    var paused = false
    var session: TrialSession? = null
        private set
    var cooldownUntilElapsedMs = 0L
    var cooldownUntilWallMs = 0L
    val usageByDay = linkedMapOf<String, Long>()
    val history = mutableListOf<TrialRecord>()
    var incomplete = true // No historical import, and process gaps cannot be verified yet.
    var running = false
        private set
    private var lastElapsedMs: Long? = null
    private var lastWallMs: Long? = null

    fun cooldownRemaining(nowElapsedMs: Long) = (cooldownUntilElapsedMs - nowElapsedMs).coerceAtLeast(0)
    fun remainingMs() = session?.let { (it.approvedMs - it.usedMs).coerceAtLeast(0) } ?: 0
    fun needsContinue() = session?.awaySinceElapsedMs != null

    fun resume(permitId: String, nowElapsedMs: Long): Boolean {
        val active = session ?: return false
        val away = active.awaySinceElapsedMs ?: return false
        if (!enabled || paused || active.id != permitId || nowElapsedMs - away !in 0..GRACE_MS || remainingMs() == 0L) return false
        active.awaySinceElapsedMs = null
        return true // Accounting starts only after a fresh, unblocked target sample.
    }

    fun start(reason: String, minutes: Long, nowElapsedMs: Long, nowWallMs: Long): TrialSession {
        check(enabled && !paused && session == null && cooldownRemaining(nowElapsedMs) == 0L)
        require(validReason(reason)) { "请填写2～100个字符的理由（不能全为空白）" }
        require(minutes in 1..MAX_INPUT_MINUTES) { "请输入有效的正整数分钟" }
        return TrialSession(UUID.randomUUID().toString(), targetId.value, reason,
            minutes * 60_000L, minOf(minutes * 60_000L, SINGLE_LIMIT_MS), nowWallMs).also {
            session = it
            running = false // Only a subsequent confirmed target sample starts accounting.
        }
    }

    /** All callers use elapsed time. A missing long observation is unknown, not fabricated use. */
    fun observe(state: ForegroundState, blocked: Boolean, nowElapsedMs: Long, nowWallMs: Long, zone: ZoneId): TrialRecord? {
        // Entering another managed application is a departure for this engine.
        val ownForeground = state.kind == ForegroundKind.TARGET && state.targetAppId == targetId
        val previousElapsed = lastElapsedMs
        val previousWall = lastWallMs
        var interrupted: TrialRecord? = null
        if (running && previousElapsed != null && previousWall != null) {
            val delta = nowElapsedMs - previousElapsed
            if (delta in 0..MAX_OBSERVATION_GAP_MS) {
                session?.let { it.usedMs += delta }
                account(previousWall, nowWallMs, delta, zone)
            } else {
                incomplete = true
                interrupted = finish("monitoring_interrupted", nowWallMs)
            }
        }
        lastElapsedMs = nowElapsedMs
        lastWallMs = nowWallMs
        val active = session
        var ended: TrialRecord? = interrupted
        if (active != null) {
            if (active.usedMs >= active.approvedMs) {
                ended = finish("timeout", nowWallMs)
                cooldownUntilElapsedMs = nowElapsedMs + COOLDOWN_MS
                cooldownUntilWallMs = nowWallMs + COOLDOWN_MS
            } else if (active.awaySinceElapsedMs?.let { nowElapsedMs - it > GRACE_MS } == true) {
                ended = finish("grace_expired", nowWallMs)
            } else if ((state.kind == ForegroundKind.TARGET && !ownForeground) ||
                state.kind in setOf(ForegroundKind.OTHER_APP, ForegroundKind.SELF, ForegroundKind.LOCKED, ForegroundKind.SCREEN_OFF)) {
                if (active.awaySinceElapsedMs == null) active.awaySinceElapsedMs = nowElapsedMs
            }
        }
        running = enabled && ownForeground &&
            !blocked && (paused || (session != null && !needsContinue()))
        return ended
    }

    fun setPaused(value: Boolean, nowWallMs: Long) {
        finish(if (value) "user_paused" else "restrictions_resumed", nowWallMs)
        paused = value
        running = false
    }

    fun interrupt(nowWallMs: Long) {
        finish("monitoring_interrupted", nowWallMs)
        running = false
        lastElapsedMs = null
        lastWallMs = null
        incomplete = true
    }

    fun recoveredSession(value: TrialSession, nowWallMs: Long) {
        require(value.targetId == targetId.value)
        // A persisted permit is historical evidence only, never authorization after reconnect.
        history += TrialRecord(value, nowWallMs, "monitoring_interrupted")
        trimHistory()
    }

    fun finish(reason: String, nowWallMs: Long): TrialRecord? {
        val active = session ?: return null
        session = null
        running = false
        return TrialRecord(active.copy(), nowWallMs, reason).also { history += it; trimHistory() }
    }

    fun changeEnabled(value: Boolean, nowWallMs: Long) {
        finish(if (value) "restrictions_resumed" else "target_disabled", nowWallMs)
        enabled = value
        paused = false
        running = false
        // Keep this application's ledger and cooldown; toggling is not a data reset.
    }

    private fun trimHistory() { while (history.size > 100) history.removeAt(0) }

    private fun account(fromWall: Long, toWall: Long, delta: Long, zone: ZoneId) {
        if (delta == 0L) return
        val from = Instant.ofEpochMilli(fromWall).atZone(zone)
        if (kotlin.math.abs((toWall - fromWall) - delta) > 1000) {
            // Wall-clock changes must never change the elapsed duration or invent a day split.
            incomplete = true
            addUsage(from.toLocalDate().toString(), delta)
            return
        }
        val midnight = from.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val before = minOf(delta, midnight - fromWall)
        addUsage(from.toLocalDate().toString(), before)
        if (delta > before) addUsage(from.toLocalDate().plusDays(1).toString(), delta - before)
    }

    private fun addUsage(day: String, delta: Long) { usageByDay[day] = (usageByDay[day] ?: 0L) + delta }

    companion object {
        const val SINGLE_LIMIT_MS = 30 * 60_000L
        const val COOLDOWN_MS = 10 * 60_000L
        const val GRACE_MS = 10 * 60_000L
        const val MAX_OBSERVATION_GAP_MS = 5_000L
        // Multiplication safety, not an arbitrary product duration limit.
        const val MAX_INPUT_MINUTES = Long.MAX_VALUE / 60_000L
        fun validReason(reason: String): Boolean = reason.isNotBlank() && reason.codePointCount(0, reason.length) in 2..100
    }
}

fun formatTrialDuration(milliseconds: Long): String {
    val value = milliseconds.coerceAtLeast(0)
    val seconds = value / 1000 + if (value % 1000 == 0L) 0 else 1
    return "${seconds / 60}分${seconds % 60}秒"
}
