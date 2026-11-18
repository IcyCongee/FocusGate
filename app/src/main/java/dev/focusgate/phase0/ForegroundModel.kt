package dev.focusgate.phase0

/** Stable identity, deliberately independent of a package or display name. */
@JvmInline
value class TargetAppId(val value: String)

data class TargetApp(val id: TargetAppId, val displayName: String, val enabled: Boolean)

object TargetCatalog {
    val douyin = TargetApp(TargetAppId("douyin"), "抖音", enabled = true)
    val bilibili = TargetApp(TargetAppId("bilibili"), "Bilibili", enabled = true)
    val xiaohongshu = TargetApp(TargetAppId("xiaohongshu"), "小红书", enabled = true)
    val enabled = listOf(douyin, bilibili, xiaohongshu)
    fun find(id: TargetAppId): TargetApp = enabled.first { it.id == id }
}

enum class WindowKind { APPLICATION, INPUT_METHOD, SYSTEM, ACCESSIBILITY_OVERLAY, OTHER }

data class WindowSample(
    val id: Int,
    val kind: WindowKind,
    val layer: Int,
    val active: Boolean,
    val focused: Boolean,
    val packageName: String?,
)

data class ScreenSample(val interactive: Boolean, val locked: Boolean, val userUnlocked: Boolean)

enum class ForegroundKind { TARGET, OTHER_APP, SELF, SELF_OVERLAY, SYSTEM, UNKNOWN, LOCKED, SCREEN_OFF }

data class ForegroundState(
    val kind: ForegroundKind,
    val packageName: String? = null,
    val targetAppId: TargetAppId? = null,
    val reason: String,
)

/** No event-package input: an accessibility event is only a signal to resample windows. */
object ForegroundResolver {
    /** Only our *currently attached* blocker may expose an otherwise unfocused lower app. */
    fun belowOwnOverlay(
        screen: ScreenSample,
        windows: List<WindowSample>,
        ownPackage: String,
        verifiedTargets: Map<String, TargetAppId>,
        inputMethodPackages: Set<String> = emptySet(),
    ): ForegroundState {
        val own = windows.filter { it.kind == WindowKind.ACCESSIBILITY_OVERLAY && it.packageName == ownPackage }
        val lower = windows.filterNot { it in own }
        val resolved = resolve(screen, lower, ownPackage, inputMethodPackages, verifiedTargets)
        if (own.isEmpty() || resolved.kind != ForegroundKind.UNKNOWN) return resolved
        // Another service's active overlay is not evidence about the underlying target.
        if (lower.any { it.kind == WindowKind.ACCESSIBILITY_OVERLAY && (it.active || it.focused) }) return resolved
        val app = lower.filter { it.kind == WindowKind.APPLICATION && it.packageName !in inputMethodPackages }
            .maxByOrNull { it.layer } ?: return resolved
        if (app.packageName == null) return resolved
        val promoted = lower.map { if (it.id == app.id) it.copy(active = true) else it }
        return resolve(screen, promoted, ownPackage, inputMethodPackages, verifiedTargets)
    }

    fun resolve(
        screen: ScreenSample,
        windows: List<WindowSample>,
        ownPackage: String,
        inputMethodPackages: Set<String>,
        verifiedTargets: Map<String, TargetAppId>,
    ): ForegroundState {
        if (!screen.interactive) return ForegroundState(ForegroundKind.SCREEN_OFF, reason = "screen_not_interactive")
        if (screen.locked) return ForegroundState(ForegroundKind.LOCKED, reason = "keyguard_locked")
        val ordered = windows.sortedByDescending { it.layer }
        if (ordered.isEmpty()) return ForegroundState(ForegroundKind.UNKNOWN, reason = "empty_windows")

        val topOwner = ordered.firstOrNull { it.active || it.focused }
        if (topOwner?.kind == WindowKind.ACCESSIBILITY_OVERLAY) {
            return ForegroundState(
                if (topOwner.packageName == ownPackage) ForegroundKind.SELF_OVERLAY else ForegroundKind.UNKNOWN,
                topOwner.packageName, reason = "accessibility_overlay_not_normal_use",
            )
        }
        // A focused application with an unreadable root is not evidence about a lower window.
        if (topOwner?.kind == WindowKind.APPLICATION && topOwner.packageName == null) {
            return ForegroundState(ForegroundKind.UNKNOWN, reason = "focused_application_root_unavailable")
        }
        val apps = ordered.filter {
            it.kind == WindowKind.APPLICATION && it.packageName !in inputMethodPackages
        }
        val selected = apps.firstOrNull { it.active || it.focused }
            ?: if (topOwner?.kind == WindowKind.INPUT_METHOD) apps.firstOrNull() else null

        val blockingSystem = ordered.firstOrNull {
            (it.kind == WindowKind.SYSTEM || it.kind == WindowKind.OTHER) &&
                (it.active || it.focused) && (selected == null || it.layer >= selected.layer)
        }
        if (blockingSystem != null) {
            return ForegroundState(ForegroundKind.SYSTEM, blockingSystem.packageName, reason = "active_system_window")
        }
        if (selected?.packageName == null) {
            return ForegroundState(ForegroundKind.UNKNOWN, reason = "no_decisive_application_window")
        }
        val pkg = selected.packageName
        if (pkg == ownPackage) return ForegroundState(ForegroundKind.SELF, pkg, reason = "own_activity")
        val target = verifiedTargets[pkg]
        return ForegroundState(
            if (target != null) ForegroundKind.TARGET else ForegroundKind.OTHER_APP,
            pkg, target, if (topOwner?.kind == WindowKind.INPUT_METHOD) "application_below_ime" else "active_application",
        )
    }
}

data class PresenceEvent(val type: String, val targetAppId: TargetAppId)

/** Transient unknown/system/overlay snapshots never become confirmed use or extra entries. */
class PresenceTracker {
    private var lastConfirmedTarget: TargetAppId? = null
    private var uncertain = false

    fun update(state: ForegroundState): List<PresenceEvent> {
        val events = mutableListOf<PresenceEvent>()
        val target = state.targetAppId.takeIf { state.kind == ForegroundKind.TARGET }
        if (target != null) {
            if (lastConfirmedTarget != target) {
                lastConfirmedTarget?.let { events += PresenceEvent("TARGET_LEAVE", it) }
                events += PresenceEvent("TARGET_ENTER", target)
            } else if (uncertain) events += PresenceEvent("TARGET_RECONFIRMED", target)
            lastConfirmedTarget = target
            uncertain = false
        } else if (state.kind in setOf(ForegroundKind.OTHER_APP, ForegroundKind.SELF, ForegroundKind.LOCKED, ForegroundKind.SCREEN_OFF)) {
            lastConfirmedTarget?.let { events += PresenceEvent("TARGET_LEAVE", it) }
            lastConfirmedTarget = null
            uncertain = false
        } else if (!uncertain) {
            lastConfirmedTarget?.let { events += PresenceEvent("TARGET_UNCERTAIN", it) }
            uncertain = true
        }
        return events
    }

    fun reset(): List<PresenceEvent> {
        val events = lastConfirmedTarget?.let { listOf(PresenceEvent("TARGET_OBSERVATION_ENDED", it)) }.orEmpty()
        lastConfirmedTarget = null
        uncertain = false
        return events
    }
}
