package com.situ.aichat.world.link

import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.work.BackgroundScheduler
import com.situ.aichat.work.EmbeddingBackfillWorker
import com.situ.aichat.world.SettlementDay
import com.situ.aichat.world.SettlementWindow
import com.situ.aichat.world.WorldSettlementCoordinator
import com.situ.aichat.world.bulletin.WorldBulletinService
import com.situ.aichat.world.cast.WorldAffinityService
import com.situ.aichat.world.notify.WorldNotifyService
import com.situ.aichat.world.travel.WorldTravelService
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * [WorldLinkRunner] T2-10（MockK·图纸 §7·E19）：编排顺序（coVerifyOrder）+ 重入（Mutex 串行）+ 未初始化/冻结窗
 * 零副作用。断言从图纸 §3.2 回前台链步序独立反推。
 */
class WorldLinkRunnerTest {

    private lateinit var worldDao: WorldDao
    private lateinit var coordinator: WorldSettlementCoordinator
    private lateinit var scribe: WorldMemoryScribe
    private lateinit var moodSettler: WorldMoodSettler
    private lateinit var bulletinService: WorldBulletinService
    private lateinit var scheduler: BackgroundScheduler
    private lateinit var affinityService: WorldAffinityService
    private lateinit var travelService: WorldTravelService
    private lateinit var notifyService: WorldNotifyService
    private lateinit var runner: WorldLinkRunner

    @Before
    fun setUp() {
        worldDao = mockk()
        coordinator = mockk()
        scribe = mockk()
        moodSettler = mockk()
        bulletinService = mockk()
        scheduler = mockk(relaxed = true)
        affinityService = mockk(relaxed = true)
        travelService = mockk(relaxed = true)
        notifyService = mockk(relaxed = true)
        runner = WorldLinkRunner(worldDao, coordinator, scribe, moodSettler, bulletinService, scheduler, affinityService, travelService, notifyService)
    }

    private fun state() = WorldStateEntity(seed = 1L, userTimezoneId = "UTC", createdAt = 0L)
    private fun window(days: List<Long>) =
        SettlementWindow(days = days.map { SettlementDay(LocalDate.ofEpochDay(it), it, 0L) }, truncatedDays = 0, absenceMs = 0L, firstRun = false)

    private fun stubAll(win: SettlementWindow, newMemories: Int) {
        coEvery { worldDao.getState() } returns state()
        coEvery { coordinator.ensureSettled(any()) } returns win
        coEvery { scribe.scribeSince(any()) } returns newMemories
        coEvery { moodSettler.settle(any(), any(), any()) } just Runs
        coEvery { bulletinService.refresh(any(), any(), any(), any()) } returns true
    }

    // MARK: - E19 未初始化零副作用

    @Test
    fun `E19 世界未初始化_零副作用`() = runBlocking {
        coEvery { worldDao.getState() } returns null
        runner.runForegroundPass(1_000L)
        coVerify(exactly = 0) { coordinator.ensureSettled(any()) }
        coVerify(exactly = 0) { scribe.scribeSince(any()) }
        coVerify(exactly = 0) { moodSettler.settle(any(), any(), any()) }
        coVerify(exactly = 0) { bulletinService.refresh(any(), any(), any(), any()) }
    }

    @Test
    fun `E19 冻结窗_days空_结算后不接四出口`() = runBlocking {
        stubAll(window(emptyList()), newMemories = 0)
        runner.runForegroundPass(1_000L)
        coVerify(exactly = 1) { coordinator.ensureSettled(1_000L) } // 结算照跑
        coVerify(exactly = 0) { scribe.scribeSince(any()) } // 但窗空 → 不接后续
        coVerify(exactly = 0) { moodSettler.settle(any(), any(), any()) }
        coVerify(exactly = 0) { bulletinService.refresh(any(), any(), any(), any()) }
    }

    // MARK: - E19 编排顺序 + 排嵌入门槛

    @Test
    fun `E19 编排顺序_结算_记忆_情绪_小报`() = runBlocking {
        stubAll(window(listOf(100L)), newMemories = 2)
        runner.runForegroundPass(1_000L)
        coVerifyOrder {
            coordinator.ensureSettled(1_000L)
            scribe.scribeSince(any())
            moodSettler.settle(any(), any(), any())
            bulletinService.refresh(any(), any(), any(), any())
        }
        // 新记忆 >0 → 排嵌入回填 worker（KEEP·免网）。
        verify(exactly = 1) {
            scheduler.scheduleOneShot(EmbeddingBackfillWorker.UNIQUE_ENSURE, EmbeddingBackfillWorker::class.java, any(), false, any(), any())
        }
    }

    @Test
    fun `E19 无新记忆_不排嵌入worker`() = runBlocking {
        stubAll(window(listOf(100L)), newMemories = 0)
        runner.runForegroundPass(1_000L)
        coVerify(exactly = 1) { bulletinService.refresh(any(), any(), any(), any()) } // 其余照跑
        verify(exactly = 0) { scheduler.scheduleOneShot(any(), EmbeddingBackfillWorker::class.java, any(), any(), any(), any()) } // n==0 → 不排
    }

    // MARK: - E19 单步失败不拦后续（韧性）

    @Test
    fun `E19 记忆抄写抛异常_情绪与小报照跑不崩`() = runBlocking {
        coEvery { worldDao.getState() } returns state()
        coEvery { coordinator.ensureSettled(any()) } returns window(listOf(100L))
        coEvery { scribe.scribeSince(any()) } throws RuntimeException("boom")
        coEvery { moodSettler.settle(any(), any(), any()) } just Runs
        coEvery { bulletinService.refresh(any(), any(), any(), any()) } returns true

        runner.runForegroundPass(1_000L) // 不崩
        coVerify(exactly = 1) { moodSettler.settle(any(), any(), any()) }
        coVerify(exactly = 1) { bulletinService.refresh(any(), any(), any(), any()) }
    }

    // MARK: - E19 重入（Mutex 串行·两并发各跑完不崩）

    @Test
    fun `E19 两并发_Mutex串行各跑完`() = runBlocking {
        stubAll(window(listOf(100L)), newMemories = 1)
        val a = async(Dispatchers.Default) { runner.runForegroundPass(1_000L) }
        val b = async(Dispatchers.Default) { runner.runForegroundPass(2_000L) }
        awaitAll(a, b)
        coVerify(exactly = 2) { coordinator.ensureSettled(any()) } // 两次都跑完（串行不丢）
    }

    // MARK: - E14 原住民播种接线（W6·step 1.5）

    @Test
    fun `E14 state非空_播种在结算前跑一次`() = runBlocking {
        stubAll(window(listOf(100L)), newMemories = 1)
        runner.runForegroundPass(1_000L)
        coVerify(exactly = 1) { affinityService.ensureSeeded() }
        coVerifyOrder { // step 1.5 播种在结算前
            affinityService.ensureSeeded()
            coordinator.ensureSettled(1_000L)
        }
    }

    @Test
    fun `E14 世界未初始化_不播种零副作用`() = runBlocking {
        coEvery { worldDao.getState() } returns null
        runner.runForegroundPass(1_000L)
        coVerify(exactly = 0) { affinityService.ensureSeeded() } // state 非空后才播种
    }

    @Test
    fun `E14 播种抛异常_不拦结算`() = runBlocking {
        stubAll(window(listOf(100L)), newMemories = 1)
        coEvery { affinityService.ensureSeeded() } throws RuntimeException("boom")
        runner.runForegroundPass(1_000L) // 不崩
        coVerify(exactly = 1) { coordinator.ensureSettled(1_000L) } // 结算照跑
    }

    // MARK: - E16 旅行到达结算接线（W7·step 3.5·结算后·小报前）

    @Test
    fun `E16 到达结算_结算后_小报前`() = runBlocking {
        stubAll(window(listOf(100L)), newMemories = 1)
        runner.runForegroundPass(1_000L)
        coVerify(exactly = 1) { travelService.settleArrivals(1_000L) }
        coVerifyOrder { // 结算 → 到达结算 → 小报（到达事件进当次小报）
            coordinator.ensureSettled(1_000L)
            travelService.settleArrivals(1_000L)
            bulletinService.refresh(any(), any(), any(), any())
        }
    }

    @Test
    fun `E16 冻结窗_days空_不结算到达`() = runBlocking {
        stubAll(window(emptyList()), newMemories = 0)
        runner.runForegroundPass(1_000L)
        coVerify(exactly = 0) { travelService.settleArrivals(any()) } // 窗空早返 → 无小报可消费 → 不结算到达
    }

    @Test
    fun `E16 到达结算抛异常_小报照跑不崩`() = runBlocking {
        stubAll(window(listOf(100L)), newMemories = 0)
        coEvery { travelService.settleArrivals(any()) } throws RuntimeException("boom")
        runner.runForegroundPass(1_000L) // 不崩
        coVerify(exactly = 1) { bulletinService.refresh(any(), any(), any(), any()) } // 独立 try/catch·后续照跑
    }

    // MARK: - E16 回 app 撤世界摘要（W8·step 0.5·在 ensureSeeded 前·含空窗都撤·异常不拦后续）

    @Test
    fun `E16 前台通行证_撤摘要在ensureSeeded前`() = runBlocking {
        stubAll(window(listOf(100L)), newMemories = 1)
        runner.runForegroundPass(1_000L)
        coVerify(exactly = 1) { notifyService.onAppForeground() }
        coVerifyOrder { // step 0.5 撤摘要 → step 1.5 播种
            notifyService.onAppForeground()
            affinityService.ensureSeeded()
        }
    }

    @Test
    fun `E16 空窗也撤摘要`() = runBlocking {
        stubAll(window(emptyList()), newMemories = 0)
        runner.runForegroundPass(1_000L)
        coVerify(exactly = 1) { notifyService.onAppForeground() } // 含空窗都撤（回 app 即撤）
    }

    @Test
    fun `E16 撤摘要抛异常_结算与小报照跑不崩`() = runBlocking {
        stubAll(window(listOf(100L)), newMemories = 0)
        every { notifyService.onAppForeground() } throws RuntimeException("boom")
        runner.runForegroundPass(1_000L) // 不崩
        coVerify(exactly = 1) { coordinator.ensureSettled(1_000L) } // 独立 try/catch·后续照跑
        coVerify(exactly = 1) { bulletinService.refresh(any(), any(), any(), any()) }
    }
}
