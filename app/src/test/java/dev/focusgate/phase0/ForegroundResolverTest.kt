package dev.focusgate.phase0

import org.junit.Assert.*
import org.junit.Test

class ForegroundResolverTest {
    private val targetId = TargetAppId("douyin")
    // Synthetic packages: these fixtures make no claim about any installed app's real package.
    private val mapping = mapOf("test.target" to targetId)
    private val awake = ScreenSample(interactive = true, locked = false, userUnlocked = true)
    private fun app(pkg: String? = "test.target", active: Boolean = true, focused: Boolean = true, layer: Int = 1) =
        WindowSample(1, WindowKind.APPLICATION, layer, active, focused, pkg)
    private fun resolve(vararg windows: WindowSample, screen: ScreenSample = awake) =
        ForegroundResolver.resolve(screen, windows.toList(), "test.self", setOf("test.ime"), mapping)

    @Test fun `only verified packages become a target`() {
        assertEquals(targetId, resolve(app()).targetAppId)
        assertEquals(ForegroundKind.OTHER_APP, resolve(app("test.unmanaged")).kind)
        assertEquals(ForegroundKind.OTHER_APP, ForegroundResolver.resolve(awake, listOf(app()), "test.self", emptySet(), emptyMap()).kind)
    }

    @Test fun `own activity is distinct from managed app`() {
        assertEquals(ForegroundKind.SELF, resolve(app("test.self")).kind)
        assertNull(resolve(app("test.self")).targetAppId)
    }

    @Test fun `focused ime uses current underlying app instead of its event package`() {
        val ime = WindowSample(2, WindowKind.INPUT_METHOD, 4, true, true, "test.ime")
        val result = resolve(app(active = false, focused = false), ime)
        assertEquals(ForegroundKind.TARGET, result.kind)
        assertEquals("application_below_ime", result.reason)
        assertEquals(ForegroundKind.OTHER_APP, resolve(app("test.unmanaged", false, false), ime).kind)
    }

    @Test fun `active system sheet is not confirmed target use`() {
        val system = WindowSample(2, WindowKind.SYSTEM, 8, true, true, "test.system")
        assertEquals(ForegroundKind.SYSTEM, resolve(app(), system).kind)
    }

    @Test fun `passive system bar does not hide the active app`() {
        val system = WindowSample(2, WindowKind.SYSTEM, 8, false, false, "test.system")
        assertEquals(ForegroundKind.TARGET, resolve(app(), system).kind)
    }

    @Test fun `own overlay is distinct and cannot claim normal use`() {
        val overlay = WindowSample(2, WindowKind.ACCESSIBILITY_OVERLAY, 8, true, true, "test.self")
        assertEquals(ForegroundKind.SELF_OVERLAY, resolve(app(), overlay).kind)
        assertNull(resolve(app(), overlay).targetAppId)
    }

    @Test fun `empty windows and missing focused root remain unknown`() {
        assertEquals(ForegroundKind.UNKNOWN, resolve().kind)
        assertEquals(ForegroundKind.UNKNOWN, resolve(app(), app(null, layer = 3)).kind)
        assertEquals(ForegroundKind.UNKNOWN, resolve(app(active = false, focused = false)).kind)
    }

    @Test fun `screen off or keyguard overrides leftover target windows`() {
        assertEquals(ForegroundKind.SCREEN_OFF, resolve(app(), screen = awake.copy(interactive = false)).kind)
        val locked = resolve(app(), screen = awake.copy(locked = true))
        assertEquals(ForegroundKind.LOCKED, locked.kind)
        assertNull(locked.targetAppId)
    }

    @Test fun `credential unlocked does not mean keyguard is unlocked`() {
        assertEquals(ForegroundKind.LOCKED, resolve(app(), screen = ScreenSample(true, true, true)).kind)
    }

    @Test fun `foreground can be resolved on connection without an entering event`() {
        assertEquals(ForegroundKind.TARGET, resolve(app()).kind)
    }

    @Test fun `internal changes ime and transient unknown do not create extra entries`() {
        val tracker = PresenceTracker()
        assertEquals(listOf("TARGET_ENTER"), tracker.update(resolve(app())).map { it.type })
        assertTrue(tracker.update(resolve(app())).isEmpty())
        assertEquals(listOf("TARGET_UNCERTAIN"), tracker.update(resolve()).map { it.type })
        assertTrue(tracker.update(resolve()).isEmpty())
        assertEquals(listOf("TARGET_RECONFIRMED"), tracker.update(resolve(app())).map { it.type })
        assertTrue(tracker.update(resolve(app(), WindowSample(2, WindowKind.INPUT_METHOD, 4, true, true, "test.ime"))).isEmpty())
    }

    @Test fun `confirmed leave and screen off each end presence only once`() {
        val tracker = PresenceTracker()
        tracker.update(resolve(app()))
        assertEquals(listOf("TARGET_LEAVE"), tracker.update(resolve(app("test.other"))).map { it.type })
        assertTrue(tracker.update(resolve(app("test.other"))).isEmpty())
        tracker.update(resolve(app()))
        assertEquals(listOf("TARGET_LEAVE"), tracker.update(resolve(app(), screen = awake.copy(interactive = false))).map { it.type })
        assertTrue(tracker.update(resolve(app(), screen = awake.copy(locked = true))).isEmpty())
    }

    @Test fun `service disconnect ends observation rather than inventing a user leave`() {
        val tracker = PresenceTracker()
        tracker.update(resolve(app()))
        assertEquals(listOf("TARGET_OBSERVATION_ENDED"), tracker.reset().map { it.type })
        assertEquals(listOf("TARGET_ENTER"), tracker.update(resolve(app())).map { it.type })
    }

    @Test fun `target attribution survives mapping changes without mixing identities`() {
        val tracker = PresenceTracker()
        tracker.update(resolve(app()))
        val second = TargetAppId("synthetic-second-target")
        val next = ForegroundResolver.resolve(awake, listOf(app("test.second")), "test.self", emptySet(), mapOf("test.second" to second))
        assertEquals(listOf(PresenceEvent("TARGET_LEAVE", targetId), PresenceEvent("TARGET_ENTER", second)), tracker.update(next))
        assertEquals(setOf("douyin", "bilibili", "xiaohongshu"), TargetCatalog.enabled.map { it.id.value }.toSet())
        assertEquals(targetId, TargetCatalog.douyin.id)
    }
}
