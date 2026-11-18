package dev.focusgate.phase0

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager

class ForegroundProbeService : AccessibilityService() {
    fun isHomePackage(pkg: String?): Boolean = pkg != null && pkg in homePackages
    fun resolveBelowOwnOverlay(): ForegroundState = ForegroundResolver.belowOwnOverlay(
        readScreen(this), ProbeRuntime.windows, packageName, ProbeRuntime.targets.verifiedPackages(), imePackages)
    private val handler = Handler(Looper.getMainLooper())
    private val presence = PresenceTracker()
    private var generation = 0L
    private var receiverRegistered = false
    private var homePackages = emptySet<String>()
    private var imePackages = emptySet<String>()
    private var pendingSample: Runnable? = null
    private var trial: TrialController? = null
    private var lastPackageRefresh = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (ProbeRuntime.service !== this@ForegroundProbeService) return
            // The sample compares against the previous screen state before updating it.
            requestCheck("screen_signal")
            ProbeRuntime.record("SCREEN_SIGNAL", details = mapOf("action" to intent?.action))
        }
    }

    override fun onServiceConnected() {
        ProbeRuntime.initialize(this)
        ProbeRuntime.service = this
        val info = serviceInfo
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        info.packageNames = null // Leaving the target is just as important as entering it.
        serviceInfo = info
        refreshKnownPackages()
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(screenReceiver, filter)
            receiverRegistered = true
            ProbeRuntime.record("SCREEN_RECEIVER_REGISTERED")
        }
        ProbeRuntime.record("SERVICE_CONNECTED")
        trial?.disconnect()
        trial = TrialController(this).also { it.connect() }
        requestCheck("service_connected") // Immediate snapshot; never wait for the next entry event.
    }

    fun requestCheck(reason: String, resetPresence: Boolean = false) {
        if (ProbeRuntime.service !== this) return
        generation++
        pendingSample?.let(handler::removeCallbacks)
        pendingSample = null
        if (resetPresence) presence.reset().forEach { ProbeRuntime.record(it.type, associatedTarget = it.targetAppId) }
        refreshKnownPackages()
        ProbeRuntime.record("RECHECK_REQUESTED", details = mapOf("trigger" to reason))
        sample(reason, generation, 0)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || ProbeRuntime.service !== this) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        // Do not access event.text, contentDescription, source, or the node tree.
        ProbeRuntime.record("WINDOW_SIGNAL", event.packageName?.toString(), details = mapOf(
            "accessibilityEventType" to event.eventType,
            "eventWindowId" to event.windowId,
            "eventTimeUptimeMs" to event.eventTime,
            "snapshotPhase" to "before_resample",
        ))
        generation++ // Invalidate finite retries belonging to an earlier window configuration.
        // Coalesce without postponing forever during a stream of window animation events.
        if (pendingSample != null) return
        val task = Runnable { pendingSample = null; sample("window_event", generation, 0) }
        pendingSample = task
        handler.postDelayed(task, 80)
    }

    private fun refreshKnownPackages() {
        val now = SystemClock.elapsedRealtime()
        if (imePackages.isNotEmpty() && now - lastPackageRefresh < 60_000) return
        lastPackageRefresh = now
        imePackages = getSystemService(InputMethodManager::class.java).inputMethodList.map { it.packageName }.toSet()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        homePackages = packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.toSet()
    }

    private fun sample(reason: String, token: Long, attempt: Int) {
        if (token != generation || ProbeRuntime.service !== this) return
        val sampleStart = SystemClock.elapsedRealtime()
        val currentScreen = readScreen(this)
        val sampled = runCatching {
            windows.map { window ->
                // API 33+ disables node prefetch. Read only the root package, never descendants or text.
                val root = if (Build.VERSION.SDK_INT >= 33) window.getRoot(0) else window.root
                val pkg = root?.packageName?.toString()
                @Suppress("DEPRECATION")
                root?.recycle()
                val entry = WindowSample(window.id, when (window.type) {
                    AccessibilityWindowInfo.TYPE_APPLICATION -> WindowKind.APPLICATION
                    AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowKind.INPUT_METHOD
                    AccessibilityWindowInfo.TYPE_SYSTEM -> WindowKind.SYSTEM
                    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> WindowKind.ACCESSIBILITY_OVERLAY
                    else -> WindowKind.OTHER
                }, window.layer, window.isActive, window.isFocused, pkg)
                @Suppress("DEPRECATION")
                window.recycle()
                entry
            }
        }
        val currentWindows = sampled.getOrDefault(emptyList())
        val readFinished = SystemClock.elapsedRealtime()
        val previousScreen = ProbeRuntime.screen
        val previousState = ProbeRuntime.state
        val state = ForegroundResolver.resolve(currentScreen, currentWindows, packageName, imePackages, ProbeRuntime.targets.verifiedPackages())
        ProbeRuntime.screen = currentScreen
        ProbeRuntime.windows = currentWindows
        ProbeRuntime.lastSampleElapsedMs = SystemClock.elapsedRealtime()
        ProbeRuntime.state = state
        if (state != previousState || currentScreen != previousScreen) ProbeRuntime.stateVersion++
        if (previousScreen?.interactive != currentScreen.interactive) ProbeRuntime.record(if (currentScreen.interactive) "SCREEN_ON" else "SCREEN_OFF")
        if (previousScreen?.locked != currentScreen.locked) ProbeRuntime.record(if (currentScreen.locked) "KEYGUARD_LOCKED" else "KEYGUARD_UNLOCKED")
        if (state != previousState) ProbeRuntime.record("FOREGROUND_CHANGED", details = mapOf("trigger" to reason))
        presence.update(state).forEach { ProbeRuntime.record(it.type, associatedTarget = it.targetAppId) }
        if (state.kind in setOf(ForegroundKind.OTHER_APP, ForegroundKind.TARGET) &&
            state.packageName !in homePackages && state.packageName !in imePackages) {
            state.packageName?.let { pkg ->
                ProbeRuntime.candidate = ProbeRuntime.targets.inspect(pkg)
            }
        }
        ProbeRuntime.record("WINDOW_SNAPSHOT", details = mapOf(
            "trigger" to reason, "retry" to attempt,
            "windowReadError" to sampled.exceptionOrNull()?.javaClass?.simpleName,
            "windowCapability" to ((serviceInfo.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0),
            "sampleStartedElapsedMs" to sampleStart,
            "windowReadFinishedElapsedMs" to readFinished,
            "windowReadDurationMs" to (readFinished - sampleStart),
        ))
        trial?.observe()
        if (state.kind == ForegroundKind.UNKNOWN && attempt < 3) {
            handler.postDelayed({ sample(reason, token, attempt + 1) }, longArrayOf(150, 500, 1500)[attempt])
        }
    }

    override fun onInterrupt() {
        ProbeRuntime.record("SERVICE_INTERRUPT_CALLBACK")
        // onInterrupt is not proof that the service was disconnected.
        requestCheck("service_interrupt")
    }

    private fun disconnect(reason: String) {
        if (ProbeRuntime.service !== this) return
        generation++
        handler.removeCallbacksAndMessages(null)
        trial?.disconnect()
        trial = null
        presence.reset().forEach { ProbeRuntime.record(it.type, associatedTarget = it.targetAppId) }
        ProbeRuntime.service = null
        ProbeRuntime.state = ForegroundState(ForegroundKind.UNKNOWN, reason = reason)
        ProbeRuntime.windows = emptyList()
        ProbeRuntime.lastSampleElapsedMs = null
        ProbeRuntime.stateVersion++
        ProbeRuntime.record("SERVICE_DISCONNECTED", details = mapOf("trigger" to reason))
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver)
            receiverRegistered = false
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect("service_unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect("service_destroyed")
        super.onDestroy()
    }
}
