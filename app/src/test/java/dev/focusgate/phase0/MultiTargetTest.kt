package dev.focusgate.phase0

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class MultiTargetTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val wall = 1_794_290_000_000L
    private fun state(id: TargetAppId) = ForegroundState(ForegroundKind.TARGET, "test.${id.value}", id, "test")
    private fun engines() = TargetCatalog.enabled.associate { it.id to TrialEngine(it.id).apply { enabled = true } }
    private fun observe(all: Map<TargetAppId, TrialEngine>, id: TargetAppId, at: Long) {
        all.values.forEach { it.observe(state(id), false, at, wall + at, zone) }
    }

    @Test fun switchingTargetsKeepsPermitsAndUsageIndependent() {
        val all = engines()
        val dy = all.getValue(TargetCatalog.douyin.id)
        val bili = all.getValue(TargetCatalog.bilibili.id)
        val red = all.getValue(TargetCatalog.xiaohongshu.id)
        val dyPermit = dy.start("看看订阅", 5, 0, wall).id
        val biliPermit = bili.start("学习视频", 20, 0, wall).id
        observe(all, dy.targetId, 0)
        observe(all, bili.targetId, 1000)
        assertTrue(bili.resume(biliPermit, 1000))
        observe(all, bili.targetId, 1000)
        observe(all, bili.targetId, 2000)
        observe(all, dy.targetId, 3000)
        assertTrue(dy.resume(dyPermit, 3000))
        observe(all, dy.targetId, 3000)
        observe(all, dy.targetId, 4000)
        assertEquals(2000, dy.session!!.usedMs)
        assertEquals(2000, bili.session!!.usedMs)
        assertEquals(dyPermit, dy.session!!.id)
        assertEquals(biliPermit, bili.session!!.id)
        assertTrue(red.usageByDay.isEmpty())
        assertEquals(2000L, dy.usageByDay.values.sum())
        assertEquals(2000L, bili.usageByDay.values.sum())
    }

    @Test fun allThreeApplicationsRequireContinueWithoutChargingScreenOffTime() {
        for (app in TargetCatalog.enabled) {
            val engine = TrialEngine(app.id).apply { enabled = true }
            val permit = engine.start("查看内容", 30, 0, wall)
            engine.observe(state(app.id), false, 0, wall, zone)
            engine.observe(ForegroundState(ForegroundKind.SCREEN_OFF, reason = "test"), false, 1000, wall + 1000, zone)
            engine.observe(state(app.id), false, 601_000, wall + 601_000, zone)
            assertTrue(engine.needsContinue())
            assertFalse(engine.running)
            assertEquals(1_799_000, engine.remainingMs())
            assertTrue(engine.resume(permit.id, 601_000))
            engine.observe(state(app.id), false, 601_000, wall + 601_000, zone)
            engine.observe(state(app.id), false, 602_000, wall + 602_000, zone)
            assertEquals(2000, engine.session!!.usedMs)
        }
    }

    @Test fun anotherManagedAppStartsGraceAndTimeoutDoesNotTransferCooldown() {
        val all = engines()
        val dy = all.getValue(TargetCatalog.douyin.id)
        val bili = all.getValue(TargetCatalog.bilibili.id)
        dy.start("短暂休息", 1, 0, wall)
        observe(all, dy.targetId, 0)
        for (at in 1000L..60_000L step 1000L) observe(all, dy.targetId, at)
        assertNull(dy.session)
        assertEquals(600_000, dy.cooldownRemaining(60_000))
        assertEquals(0, bili.cooldownRemaining(60_000))
        bili.start("观看教程", 5, 60_000, wall + 60_000)
        observe(all, bili.targetId, 60_000)
        observe(all, TargetCatalog.xiaohongshu.id, 61_000)
        observe(all, TargetCatalog.xiaohongshu.id, 661_001)
        assertNull(bili.session)
        assertEquals("grace_expired", bili.history.last().endReason)
        assertEquals(0, bili.cooldownRemaining(121_000))
    }

    @Test fun disablingOneApplicationEndsOnlyItsPermitAndKeepsItsCooldown() {
        val all = engines()
        val dy = all.getValue(TargetCatalog.douyin.id)
        val bili = all.getValue(TargetCatalog.bilibili.id)
        dy.start("看抖音", 5, 0, wall)
        val biliPermit = bili.start("看课程", 5, 0, wall).id
        dy.cooldownUntilElapsedMs = 500_000
        dy.cooldownUntilWallMs = wall + 500_000
        dy.changeEnabled(false, wall)
        observe(all, dy.targetId, 0)
        observe(all, dy.targetId, 1000)
        assertNull(dy.session)
        assertTrue(dy.usageByDay.isEmpty())
        assertEquals("target_disabled", dy.history.last().endReason)
        assertEquals(biliPermit, bili.session!!.id)
        assertTrue(bili.enabled)
        dy.changeEnabled(true, wall + 1000)
        assertNull(dy.session)
        assertEquals(499_000, dy.cooldownRemaining(1000))
    }

    @Test fun pauseAndResumeArePerApplication() {
        val all = engines()
        val dy = all.getValue(TargetCatalog.douyin.id)
        val bili = all.getValue(TargetCatalog.bilibili.id)
        val biliPermit = bili.start("看视频", 5, 0, wall).id
        dy.setPaused(true, wall)
        observe(all, dy.targetId, 0)
        observe(all, dy.targetId, 1000)
        assertEquals(1000, dy.usageByDay.values.sum())
        assertFalse(bili.paused)
        assertEquals(biliPermit, bili.session!!.id)
        dy.setPaused(false, wall + 1000)
        assertNull(dy.session)
        assertEquals(biliPermit, bili.session!!.id)
    }

    @Test fun schemaOneUpgradePreservesDouyinAndDoesNotEnableNewTargets() {
        // Literal old-format fixture, not a schema-2 round trip disguised as migration.
        val old = JSONObject("""{
          "schemaVersion":1,"targetAppId":"douyin","enabled":true,"paused":true,
          "cooldownUntilWallMs":${wall + 25_000},"usageByDay":{"2026-11-11":123456},
          "history":[{"session":{"id":"old-finished","targetAppId":"douyin","reason":"原始理由",
          "expectedMs":60000,"approvedMs":60000,"startedWallMs":$wall,"usedMs":60010},
          "endedWallMs":$wall,"endReason":"timeout"}],
          "session":{"id":"old-active","targetAppId":"douyin","reason":"旧活动会话",
          "expectedMs":300000,"approvedMs":300000,"startedWallMs":$wall,"usedMs":4567}
        }""")
        val migrated = TrialStateCodec.decode(old, wall, 1000)
        val dy = migrated.getValue(TargetCatalog.douyin.id)
        assertTrue(dy.enabled)
        assertTrue(dy.paused)
        assertNull(dy.session)
        assertEquals(25_000, dy.cooldownRemaining(1000))
        assertEquals(123456L, dy.usageByDay["2026-11-11"])
        assertEquals("原始理由", dy.history.first().session.reason)
        assertEquals("monitoring_interrupted", dy.history.last().endReason)
        assertEquals(4567, dy.history.last().session.usedMs)
        listOf(TargetCatalog.bilibili, TargetCatalog.xiaohongshu).forEach { app ->
            val e = migrated.getValue(app.id)
            assertFalse(e.enabled)
            assertFalse(e.paused)
            assertTrue(e.history.isEmpty())
            assertTrue(e.usageByDay.isEmpty())
            assertEquals(0, e.cooldownRemaining(1000))
        }
    }

    @Test fun multiAppStateRestoresSeparateSwitchesUsageAndCooldownsWithoutPermits() {
        val all = engines()
        all.values.forEachIndexed { i, e ->
            e.usageByDay["2026-11-11"] = i * 1000L
            e.cooldownUntilWallMs = wall + (i + 1) * 10_000
            e.start("记录${e.targetId.value}", 5, 0, wall)
        }
        all.getValue(TargetCatalog.xiaohongshu.id).changeEnabled(false, wall)
        all.getValue(TargetCatalog.bilibili.id).setPaused(true, wall)
        val restored = TrialStateCodec.decode(JSONObject(TrialStateCodec.encode(all).toString()), wall, 2000)
        restored.values.forEachIndexed { i, e ->
            assertNull(e.session)
            assertEquals(i * 1000L, e.usageByDay["2026-11-11"])
            assertEquals((i + 1) * 10_000L, e.cooldownRemaining(2000))
            assertTrue(e.history.all { it.session.targetId == e.targetId.value })
        }
        assertFalse(restored.getValue(TargetCatalog.xiaohongshu.id).enabled)
        assertTrue(restored.getValue(TargetCatalog.bilibili.id).paused)
        assertTrue(restored.getValue(TargetCatalog.douyin.id).enabled)
    }

    @Test fun corruptedTargetOwnershipIsRejectedInsteadOfMergingLedgers() {
        val json = TrialStateCodec.encode(engines())
        json.getJSONObject("targets").getJSONObject("bilibili").put("targetAppId", "douyin")
        assertThrows(IllegalArgumentException::class.java) { TrialStateCodec.decode(json, wall, 0) }
        json.put("schemaVersion", 99)
        assertThrows(IllegalArgumentException::class.java) { TrialStateCodec.decode(json, wall, 0) }
    }
}
