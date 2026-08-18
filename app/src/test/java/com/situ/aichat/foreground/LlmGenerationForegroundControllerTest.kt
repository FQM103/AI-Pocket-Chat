package com.situ.aichat.foreground

import com.situ.aichat.story.StoryGenPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * ④ 前台保活控制器 T2（Robolectric：acquire/release 内的 startForegroundService/stopService 被影子化、不真起服务）。
 *
 * 两组契约：
 * 1. 引用计数（既有）——超时复位恢复路径 + 配平 + over-release 钳制。
 * 2. **双槽仲裁**（灵动岛卷一 §3.4）——故事优先于 typing、两 owner 互不越权清对方槽、残值只在服务真停时清。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LlmGenerationForegroundControllerTest {

    private fun newController() = LlmGenerationForegroundController(RuntimeEnvironment.getApplication())

    private fun storyProgress(overall: Double = 0.45, storyId: String = "s1") = ForegroundActivity.StoryProgress(
        storyId = storyId,
        overall = overall,
        genPhase = StoryGenPhase.WRITING,
        phaseLabel = "正在撰写正文…",
        shortLabel = "撰写",
        title = "书",
        chapterNumber = 1,
    )

    private fun typing(name: String = "小夏") = ForegroundActivity.Typing(name, avatarPath = null, conversationUuid = "c1")

    @Test
    fun 超时复位计数_后续acquire能重新拉起保活() {
        val c = newController()
        c.acquire()
        c.acquire()
        assertEquals(2, c.activeCountForTest())

        // FGS 超时被系统回收：计数必须清零，否则后续 acquire 永远到不了 0→1、再不拉起服务。
        c.onForegroundServiceTimedOut()
        assertEquals(0, c.activeCountForTest())

        // 复位后是干净的 0→1：会重新拉起前台服务恢复保活。
        c.acquire()
        assertEquals(1, c.activeCountForTest())
    }

    @Test
    fun acquire与release配平后归零() {
        val c = newController()
        c.acquire()
        c.acquire()
        c.release()
        assertEquals(1, c.activeCountForTest())
        c.release()
        assertEquals(0, c.activeCountForTest())
    }

    @Test
    fun over_release钳到0不为负() {
        val c = newController()
        c.acquire()
        c.release()
        c.release()
        c.release()
        assertEquals(0, c.activeCountForTest())
    }

    // ── 双槽仲裁（灵动岛卷一 §3.4）──

    @Test
    fun 初始态_两槽皆空_药丸静默() {
        assertNull(newController().activity.value)
    }

    @Test
    fun 只有typing_药丸显示typing() {
        val c = newController()
        c.setTyping(typing())
        assertEquals(typing(), c.activity.value)
    }

    @Test
    fun 只有故事_药丸显示故事进度() {
        val c = newController()
        c.updateStoryProgress(storyProgress())
        assertEquals(storyProgress(), c.activity.value)
    }

    @Test
    fun E6_故事与typing并发_故事优先占药丸() {
        val c = newController()
        c.setTyping(typing())
        c.updateStoryProgress(storyProgress())
        assertEquals("故事进度信息量更大，须压过 typing", storyProgress(), c.activity.value)
    }

    @Test
    fun E6_故事结束后_typing仍在则药丸回落typing() {
        val c = newController()
        c.setTyping(typing())
        c.updateStoryProgress(storyProgress())
        c.clearStoryProgress()
        assertEquals("故事让出槽后 typing 必须重新露出，而不是掉成静默", typing(), c.activity.value)
    }

    @Test
    fun typing结束但故事仍在_药丸不受影响() {
        val c = newController()
        c.updateStoryProgress(storyProgress())
        c.setTyping(typing())
        c.clearTyping()
        assertEquals(storyProgress(), c.activity.value)
    }

    @Test
    fun 两槽先后清空_回落静默() {
        val c = newController()
        c.updateStoryProgress(storyProgress())
        c.setTyping(typing())
        c.clearStoryProgress()
        c.clearTyping()
        assertNull(c.activity.value)
    }

    @Test
    fun 故事进度越界_钳到0到1() {
        val c = newController()
        c.updateStoryProgress(storyProgress(overall = 1.7))
        assertEquals(1.0, (c.activity.value as ForegroundActivity.StoryProgress).overall, 0.0)
        c.updateStoryProgress(storyProgress(overall = -0.5))
        assertEquals(0.0, (c.activity.value as ForegroundActivity.StoryProgress).overall, 0.0)
    }

    @Test
    fun release不再清残值_留给服务真停时清() {
        // 清值挪到 onServiceStopped：在 release 里清会在停服前先闪一帧静默态。
        val c = newController()
        c.acquire()
        c.updateStoryProgress(storyProgress())
        c.release()
        assertEquals("release 只管服务生死，不许顺手把药丸内容抹了", storyProgress(), c.activity.value)
    }

    @Test
    fun onServiceStopped_清两槽残值() {
        val c = newController()
        c.updateStoryProgress(storyProgress())
        c.setTyping(typing())
        c.onServiceStopped()
        assertNull(c.activity.value)
        // 残值真被清掉：后续单独设 typing 时，不该有旧故事槽再压过来。
        c.setTyping(typing("小冬"))
        assertEquals(typing("小冬"), c.activity.value)
    }

    @Test
    fun 超时回收_同时清计数与两槽() {
        val c = newController()
        c.acquire()
        c.updateStoryProgress(storyProgress())
        c.setTyping(typing())
        c.onForegroundServiceTimedOut()
        assertEquals(0, c.activeCountForTest())
        assertNull(c.activity.value)
    }

    // ── 启停竞态闸（2026-07-27 闪退修复·图纸 docs/handoff/2026-07-27-前台服务启停竞态闪退.md §4.1）──
    // 病根：startForegroundService() 只是把 onStartCommand 排进主线程 looper，而任务可能在别的调度器上 19ms 内
    // 就失败完毕 → release 的 stopService 抢在服务起身之前到达 → 系统抛致命
    // ForegroundServiceDidNotStartInTimeException。断言用 Robolectric 影子的「下一条被停的服务」实证。

    /** 影子里取出下一条 stopService 的目标类名；没有则 null。 */
    private fun nextStoppedServiceClass(): String? =
        shadowOf(RuntimeEnvironment.getApplication()).nextStoppedService?.component?.className

    /** 把影子里积压的启停记录抽干，让后续断言只看得到自己那一段。 */
    private fun drainServiceIntents() {
        val app = shadowOf(RuntimeEnvironment.getApplication())
        while (app.nextStartedService != null) { /* drain */ }
        while (app.nextStoppedService != null) { /* drain */ }
    }

    @Test
    fun 服务起身前就release_绝不发stopService_改由服务自停() {
        val c = newController()
        c.acquire()
        drainServiceIntents()

        // 生成秒失败：服务的 onStartCommand 还一次都没跑过，任务就结束了。
        c.release()
        assertNull(
            "服务还没挂上前台就 stopService = 系统必抛致命 ForegroundServiceDidNotStartInTimeException",
            nextStoppedServiceClass(),
        )

        // 服务终于起身并挂上前台：此刻 5s 规则已满足，欠账在这里兑现——它该就地自停。
        assertTrue("起身时发现任务已结束，须让服务自停", c.onServiceForegrounded())
    }

    @Test
    fun 正常时序_服务先挂上前台_release照常停服() {
        val c = newController()
        c.acquire()
        assertFalse("任务还在跑，服务不该自停", c.onServiceForegrounded())
        drainServiceIntents()

        c.release()
        assertEquals(
            "服务已挂上前台，停它是安全的，必须真发 stopService",
            LlmGenerationForegroundService::class.java.name,
            nextStoppedServiceClass(),
        )
    }

    @Test
    fun 欠账期间又来新任务_停服欠账被撤销() {
        val c = newController()
        c.acquire()
        c.release() // 记下欠账（服务尚未起身）
        c.acquire() // 新任务来了：欠账必须作废，否则服务起身即自停 → 新任务失去保活

        assertFalse("新任务在跑，服务不该因旧欠账自停", c.onServiceForegrounded())
        assertEquals(1, c.activeCountForTest())
    }

    @Test
    fun 服务真停后_握手标志复位_下一轮仍走推迟路径() {
        val c = newController()
        c.acquire()
        c.onServiceForegrounded()
        c.release()
        c.onServiceStopped() // 服务 onDestroy

        // 新一轮：又是「服务尚未起身」的初始态，必须重新走「只记账不停服」。
        c.acquire()
        drainServiceIntents()
        c.release()
        assertNull("复位后仍须把停服推迟到服务挂上前台之后", nextStoppedServiceClass())
        assertTrue(c.onServiceForegrounded())
    }
}
