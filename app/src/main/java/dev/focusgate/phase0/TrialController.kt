package dev.focusgate.phase0

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.time.ZoneId

/** One visible gate; independent permits, ledgers and cooldowns for each application. */
class TrialController(private val service: ForegroundProbeService) {
    private val handler = Handler(Looper.getMainLooper())
    private val gate = TrialGateOverlay(service, ::approve, ::goHome, ::pause, ::continueSession)
    private val engines get() = TrialRuntime.engines
    private var unknownSince: Long? = null
    private var lastCheckpoint = 0L
    private var stopped = false
    private var mappings = ProbeRuntime.targets.verifiedPackages()
    private data class PendingPage(val targetId: TargetAppId, val permitId: String, val boundaryElapsed: Long, val targetPackage: String?)
    private var pendingPage: PendingPage? = null
    private val heartbeat = object : Runnable {
        override fun run() {
            if (stopped || ProbeRuntime.service !== service) return
            if (engines.values.any { it.enabled }) service.requestCheck("trial_heartbeat")
            handler.postDelayed(this, 1000)
        }
    }

    fun connect() {
        engines.values.forEach { it.interrupt(System.currentTimeMillis()) }
        TrialRuntime.save()
        handler.post(heartbeat)
    }

    fun observe() {
        if (stopped) return
        val now = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val currentMappings = ProbeRuntime.targets.verifiedPackages()
        if (mappings != currentMappings) {
            TargetCatalog.enabled.forEach { app ->
                if (mappings.entries.firstOrNull { it.value == app.id }?.key != currentMappings.entries.firstOrNull { it.value == app.id }?.key) {
                    engines.getValue(app.id).finish("binding_changed", wall)
                    if (gate.targetId == app.id) gate.hide()
                }
            }
            mappings = currentMappings
            TrialRuntime.save()
        }
        val state = currentUnderlying()
        handlePendingPage(state, now)
        engines.forEach { (id, engine) ->
            val ended = engine.observe(state, gate.visible || TrialRuntime.storageError != null, now, wall, ZoneId.systemDefault())
            if (ended != null) {
                ProbeRuntime.record("TRIAL_SESSION_ENDED", associatedTarget = id, details = mapOf("permitId" to ended.session.id, "endReason" to ended.endReason, "actualMs" to ended.session.usedMs))
                if (ended.endReason == "timeout" && canActOnTarget(state, id) && !engine.paused && engine.enabled) {
                    gate.show(id, "cooldown", "本次时间已用完，请先休息10分钟。")
                }
                val saved = TrialRuntime.save()
                if (ended.endReason == "timeout" && saved && canActOnTarget(state, id) && !engine.paused && engine.enabled) {
                    service.requestCheck("trial_timeout_exit_recheck")
                    if (canActOnTarget(currentUnderlying(), id) && engine.session == null && !engine.paused && engine.enabled) {
                        val acted = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                        ProbeRuntime.record("TRIAL_TIMEOUT_HOME_REQUEST", associatedTarget = id, details = mapOf("permitId" to ended.session.id, "actionAccepted" to acted, "actualMs" to ended.session.usedMs))
                        if (acted) pendingPage = PendingPage(id, ended.session.id, now, state.packageName)
                    }
                    return
                }
            }
        }
        // A recursive timeout resample may have changed the global window state.
        updateGate(currentUnderlying(), now)
        if (engines.values.any { it.enabled } && now - lastCheckpoint >= 2000 && TrialRuntime.storageError == null) {
            lastCheckpoint = now
            if (!TrialRuntime.save()) {
                engines.values.forEach { it.finish("save_failed", wall) }
                updateGate(currentUnderlying(), now)
            }
        }
    }

    private fun updateGate(state: ForegroundState, now: Long) {
        val id = state.targetAppId
        val engine = id?.let { engines[it] }
        if (id != null && engine != null && engine.enabled && !engine.paused && canActOnTarget(state, id)) {
            unknownSince = null
            when {
                TrialRuntime.storageError != null -> gate.show(id, "error", TrialRuntime.storageError!!)
                engine.needsContinue() -> gate.show(id, "resume", "本次还剩 ${formatTrialDuration(engine.remainingMs())}。\n点击继续后才恢复计时；离开超过10分钟需重新填写。")
                engine.session != null -> gate.hide()
                engine.cooldownRemaining(now) > 0 -> gate.show(id, "cooldown", "还需等待 ${formatTrialDuration(engine.cooldownRemaining(now))}")
                else -> gate.show(id, "confirm", "本次最多批准30分钟；请主动选择使用时长。")
            }
        } else if (state.kind == ForegroundKind.UNKNOWN && readScreen(service).let { it.interactive && !it.locked } &&
            gate.targetId?.let { engines[it]?.let { e -> e.enabled && !e.paused } } == true) {
            if (unknownSince == null) unknownSince = now
            if (now - unknownSince!! >= 1000) gate.hide()
        } else {
            unknownSince = null
            gate.hide()
        }
    }

    private fun handlePendingPage(state: ForegroundState, now: Long) {
        val pending = pendingPage ?: return
        val engine = engines.getValue(pending.targetId)
        if (!engine.enabled || engine.paused || engine.session != null ||
            ProbeRuntime.targets.binding(pending.targetId)?.app?.packageName != pending.targetPackage ||
            !readScreen(service).let { it.interactive && !it.locked } || now - pending.boundaryElapsed > 2000) {
            pendingPage = null
            ProbeRuntime.record("TRIAL_TIMEOUT_PAGE_CANCELLED", associatedTarget = pending.targetId)
            return
        }
        if (state.kind == ForegroundKind.OTHER_APP && service.isHomePackage(state.packageName)) {
            pendingPage = null
            gate.hide()
            ProbeRuntime.record("TRIAL_TIMEOUT_EXIT_OBSERVED", associatedTarget = pending.targetId, details = mapOf("permitId" to pending.permitId, "observedAfterBoundaryMs" to (now - pending.boundaryElapsed)))
            runCatching {
                service.startActivity(Intent(service, TrialActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("timeout", true).putExtra("timeoutObservedElapsedMs", now)
                    .putExtra("targetAppId", pending.targetId.value).putExtra("timeoutPermitId", pending.permitId))
                ProbeRuntime.record("TRIAL_TIMEOUT_PAGE_REQUESTED", associatedTarget = pending.targetId, details = mapOf("permitId" to pending.permitId))
            }.onFailure {
                ProbeRuntime.record("TRIAL_TIMEOUT_PAGE_FAILED", associatedTarget = pending.targetId, details = mapOf("error" to it.javaClass.simpleName))
            }
        } else if (state.kind in setOf(ForegroundKind.OTHER_APP, ForegroundKind.SELF, ForegroundKind.LOCKED, ForegroundKind.SCREEN_OFF) ||
            (state.kind == ForegroundKind.TARGET && state.targetAppId != pending.targetId)) {
            pendingPage = null
        }
    }

    private fun currentUnderlying(): ForegroundState = if (gate.visible) service.resolveBelowOwnOverlay() else ProbeRuntime.state

    private fun canActOnTarget(state: ForegroundState, id: TargetAppId): Boolean = state.kind == ForegroundKind.TARGET &&
        state.targetAppId == id && state.packageName == ProbeRuntime.targets.binding(id)?.app?.packageName &&
        ProbeRuntime.service === service && readScreen(service).let { it.interactive && !it.locked }

    private fun approve(id: TargetAppId, reason: String, minutes: Long): String? {
        service.requestCheck("trial_approve_recheck")
        if (!canActOnTarget(currentUnderlying(), id) || gate.targetId != id) return "当前应用已变化，请重新进入并确认。"
        val engine = engines.getValue(id)
        if (TrialRuntime.storageError != null) return TrialRuntime.storageError
        if (!engine.enabled || engine.paused || engine.session != null) return "状态已变化，请返回首页核对。"
        if (engine.cooldownRemaining(SystemClock.elapsedRealtime()) > 0) return "仍处于冷却，请稍后再开始。"
        val active = runCatching { engine.start(reason, minutes, SystemClock.elapsedRealtime(), System.currentTimeMillis()) }
            .getOrElse { return it.message ?: "输入有误，请重新填写。" }
        if (!TrialRuntime.save()) {
            engine.finish("save_failed", System.currentTimeMillis())
            return TrialRuntime.storageError
        }
        ProbeRuntime.record("TRIAL_SESSION_APPROVED", associatedTarget = id, details = mapOf("permitId" to active.id, "expectedMs" to active.expectedMs, "approvedMs" to active.approvedMs))
        gate.hide()
        service.requestCheck("trial_permit_released")
        return null
    }

    private fun continueSession(id: TargetAppId, permitId: String): String? {
        service.requestCheck("trial_continue_recheck")
        if (!canActOnTarget(currentUnderlying(), id) || gate.targetId != id) return "当前应用已变化，请重新进入。"
        if (TrialRuntime.storageError != null) return TrialRuntime.storageError
        val engine = engines.getValue(id)
        if (!engine.resume(permitId, SystemClock.elapsedRealtime())) return "本次时段已结束，请重新进入并填写。"
        if (!TrialRuntime.save()) {
            engine.finish("save_failed", System.currentTimeMillis())
            updateGate(currentUnderlying(), SystemClock.elapsedRealtime())
            return TrialRuntime.storageError
        }
        ProbeRuntime.record("TRIAL_SESSION_CONTINUED", associatedTarget = id, details = mapOf("permitId" to permitId, "remainingMs" to engine.remainingMs()))
        gate.hide()
        service.requestCheck("trial_continued")
        return null
    }

    private fun goHome(id: TargetAppId) {
        service.requestCheck("trial_cancel_recheck")
        if (canActOnTarget(currentUnderlying(), id) && gate.targetId == id) {
            val result = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            ProbeRuntime.record("TRIAL_CANCEL_HOME_REQUEST", associatedTarget = id, details = mapOf("actionAccepted" to result))
        }
    }

    private fun pause(id: TargetAppId) {
        service.requestCheck("trial_pause_recheck")
        val engine = engines.getValue(id)
        val oldPaused = engine.paused
        engine.setPaused(true, System.currentTimeMillis())
        if (TrialRuntime.save()) {
            if (gate.targetId == id) gate.hide()
            ProbeRuntime.record("TRIAL_USER_PAUSED", associatedTarget = id)
            service.requestCheck("trial_paused")
        } else engine.paused = oldPaused
    }

    fun disconnect() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        gate.hide()
        engines.values.forEach { it.interrupt(System.currentTimeMillis()) }
        TrialRuntime.save()
    }
}
