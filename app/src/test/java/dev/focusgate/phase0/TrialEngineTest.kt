package dev.focusgate.phase0

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TrialEngineTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val startWall = Instant.parse("2026-11-10T06:00:00Z").toEpochMilli()
    private val target = ForegroundState(ForegroundKind.TARGET, "test.target", TargetCatalog.douyin.id, "test")
    private val other = ForegroundState(ForegroundKind.OTHER_APP, "test.other", reason = "test")
    private fun engine() = TrialEngine().apply { enabled = true }
    private fun observe(e: TrialEngine, at: Long, state: ForegroundState = target, blocked: Boolean = false) =
        e.observe(state, blocked, at, startWall + at, zone)

    @Test fun onlyConfirmedUseConsumesSessionAndPausedUsageStillCounts() {
        val e = engine()
        e.start("看看消息", 5, 0, startWall)
        observe(e, 0)
        observe(e, 1000)
        observe(e, 2000, other)
        observe(e, 5000, other)
        observe(e, 6000)
        assertFalse(e.running)
        assertTrue(e.resume(e.session!!.id, 6000))
        observe(e, 6000)
        observe(e, 7000)
        assertEquals(3000, e.session!!.usedMs)
        assertEquals(3000L, e.usageByDay["2026-11-10"])
        e.setPaused(true, startWall + 7000)
        observe(e, 7000)
        observe(e, 8000)
        assertNull(e.session)
        assertEquals(4000L, e.usageByDay["2026-11-10"])
    }

    @Test fun overlayAndScreenOffDoNotAccrueTime() {
        val e = engine()
        e.start("测试锁屏", 5, 0, startWall)
        observe(e, 0, blocked = true)
        observe(e, 3000, blocked = true)
        assertEquals(0, e.session!!.usedMs)
        observe(e, 3000)
        observe(e, 4000, ForegroundState(ForegroundKind.SCREEN_OFF, reason = "test"))
        observe(e, 8000, ForegroundState(ForegroundKind.LOCKED, reason = "test"))
        assertEquals(1000, e.session!!.usedMs)
    }

    @Test fun graceUsesElapsedTimeAndDoesNotCreateCooldown() {
        val e = engine()
        val id = e.start("查看订阅", 5, 0, startWall).id
        observe(e, 0)
        observe(e, 1000, other)
        observe(e, 601_000)
        assertEquals(id, e.session!!.id)
        assertFalse(e.running)
        observe(e, 601_001, other)
        assertNull(e.session)
        assertEquals("grace_expired", e.history.last().endReason)
        assertEquals(0, e.cooldownRemaining(601_001))
    }

    @Test fun exactTenMinuteBoundaryCanContinueButOneMillisecondLaterCannot() {
        val e = engine()
        e.start("看看收藏", 5, 0, startWall)
        observe(e, 0)
        observe(e, 1000, other)
        observe(e, 601_000)
        assertNotNull(e.session)
        assertFalse(e.resume("stale-permit", 601_000))
        assertFalse(e.resume(e.session!!.id, 601_001))
        observe(e, 601_001)
        assertNull(e.session)
    }

    @Test fun screenOffReturnRequiresContinueAndRepeatedDepartureKeepsOriginalDeadline() {
        val e = engine()
        val id = e.start("测试续用", 30, 0, startWall).id
        observe(e, 0)
        observe(e, 1000, ForegroundState(ForegroundKind.SCREEN_OFF, reason = "test"))
        observe(e, 301_000)
        observe(e, 302_000)
        assertFalse(e.running)
        assertEquals(1000, e.session!!.usedMs)
        observe(e, 303_000, other)
        assertEquals(1000L, e.session!!.awaySinceElapsedMs)
        observe(e, 601_000)
        assertTrue(e.resume(id, 601_000))
        observe(e, 601_000)
        observe(e, 602_000, other)
        assertEquals(2000, e.session!!.usedMs)
        assertEquals(602_000L, e.session!!.awaySinceElapsedMs)
        observe(e, 1_202_000)
        assertTrue(e.resume(id, 1_202_000))
    }

    @Test fun timeoutRetainsActualOvershootAndStartsCooldownOnce() {
        val e = engine()
        e.start("休息一下", 1, 0, startWall)
        observe(e, 0)
        for (at in 1000L..59_000L step 1000L) observe(e, at)
        val ended = observe(e, 60_500)!!
        assertEquals("timeout", ended.endReason)
        assertEquals(60_500, ended.session.usedMs)
        assertEquals(600_000, e.cooldownRemaining(60_500))
        observe(e, 61_500)
        assertEquals(599_000, e.cooldownRemaining(61_500))
        assertEquals(1, e.history.size)
    }

    @Test fun resumeAndInterruptInvalidateOldPermit() {
        val e = engine()
        e.start("查看动态", 5, 0, startWall)
        e.setPaused(true, startWall)
        e.setPaused(false, startWall)
        assertNull(e.session)
        val old = e.start("重新使用", 5, 0, startWall).copy()
        e.interrupt(startWall + 1000)
        assertNull(e.session)
        val restored = engine()
        restored.recoveredSession(old, startWall + 2000)
        assertNull(restored.session)
        assertEquals("monitoring_interrupted", restored.history.last().endReason)
    }

    @Test fun longMissingObservationIsNotAssumedToBeUse() {
        val e = engine()
        e.start("使用一下", 5, 0, startWall)
        observe(e, 0)
        observe(e, 100_000)
        assertNull(e.session)
        assertEquals(0, e.history.last().session.usedMs)
        assertEquals("monitoring_interrupted", e.history.last().endReason)
        assertTrue(e.incomplete)
    }

    @Test fun midnightSplitsObservedDurationWithoutDuplicating() {
        val e = engine()
        e.setPaused(true, startWall)
        val wall = Instant.parse("2026-11-10T15:59:59Z").toEpochMilli()
        e.observe(target, false, 0, wall, zone)
        e.observe(target, false, 2000, wall + 2000, zone)
        assertEquals(1000L, e.usageByDay["2026-11-10"])
        assertEquals(1000L, e.usageByDay["2026-11-11"])
    }

    @Test fun clockChangeDoesNotLengthenElapsedUsage() {
        val e = engine()
        e.setPaused(true, startWall)
        e.observe(target, false, 0, startWall, zone)
        e.observe(target, false, 1000, startWall + 3_600_000, zone)
        assertEquals(1000, e.usageByDay.values.sum())
    }

    @Test fun otherTargetCannotConsumeDouyinLedger() {
        val e = engine()
        e.setPaused(true, startWall)
        val synthetic = target.copy(targetAppId = TargetAppId("synthetic-other"))
        observe(e, 0, synthetic)
        observe(e, 1000, synthetic)
        assertTrue(e.usageByDay.isEmpty())
    }

    @Test fun reasonIsPreservedAndDurationHasNoSilentDefault() {
        val e = engine()
        val reason = "  看看订阅  "
        val s = e.start(reason, 60, 0, startWall)
        assertEquals(reason, s.reason)
        assertEquals(3_600_000, s.expectedMs)
        assertEquals(1_800_000, s.approvedMs)
        assertFalse(TrialEngine.validReason("   "))
        assertFalse(TrialEngine.validReason("好"))
        assertTrue(TrialEngine.validReason("😀😀"))
    }

    @Test fun invalidOrOverflowingMinutesAreRejected() {
        for (minutes in listOf(0L, -1L, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { engine().start("有效理由", minutes, 0, startWall) }
        }
        assertTrue(formatTrialDuration(Long.MAX_VALUE).endsWith("秒"))
    }

    @Test fun ownOverlayAllowsReadingLowerApplicationButDoesNotUseOldTargetCache() {
        val screen = ScreenSample(true, false, true)
        val own = WindowSample(1, WindowKind.ACCESSIBILITY_OVERLAY, 10, true, true, "self")
        val targetWindow = WindowSample(2, WindowKind.APPLICATION, 2, false, false, "test.target")
        val packages = mapOf("test.target" to TargetCatalog.douyin.id)
        assertEquals(ForegroundKind.TARGET, ForegroundResolver.belowOwnOverlay(screen, listOf(own, targetWindow), "self", packages).kind)
        assertEquals(ForegroundKind.OTHER_APP, ForegroundResolver.belowOwnOverlay(screen, listOf(own, targetWindow.copy(packageName = "test.other")), "self", packages).kind)
        assertEquals(ForegroundKind.UNKNOWN, ForegroundResolver.belowOwnOverlay(screen, listOf(own), "self", packages).kind)
        assertEquals(ForegroundKind.SCREEN_OFF, ForegroundResolver.belowOwnOverlay(screen.copy(interactive = false), listOf(own, targetWindow), "self", packages).kind)
        val ime = WindowSample(3, WindowKind.APPLICATION, 12, true, true, "test.ime")
        assertEquals(ForegroundKind.TARGET, ForegroundResolver.belowOwnOverlay(screen, listOf(own, targetWindow, ime), "self", packages, setOf("test.ime")).kind)
    }

    @Test fun activeSystemOrForeignOverlayIsNotPromotedToTarget() {
        val screen = ScreenSample(true, false, true)
        val own = WindowSample(1, WindowKind.ACCESSIBILITY_OVERLAY, 10, false, false, "self")
        val app = WindowSample(2, WindowKind.APPLICATION, 2, false, false, "test.target")
        val system = WindowSample(3, WindowKind.SYSTEM, 11, true, true, "system")
        val packages = mapOf("test.target" to TargetCatalog.douyin.id)
        assertEquals(ForegroundKind.SYSTEM, ForegroundResolver.belowOwnOverlay(screen, listOf(own, app, system), "self", packages).kind)
        assertEquals(ForegroundKind.UNKNOWN, ForegroundResolver.belowOwnOverlay(screen, listOf(own, app, system.copy(kind = WindowKind.ACCESSIBILITY_OVERLAY)), "self", packages).kind)
    }
}
