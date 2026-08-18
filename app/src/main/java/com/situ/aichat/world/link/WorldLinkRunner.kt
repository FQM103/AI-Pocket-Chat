package com.situ.aichat.world.link

import android.util.Log
import androidx.work.ExistingWorkPolicy
import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.work.BackgroundScheduler
import com.situ.aichat.work.EmbeddingBackfillWorker
import com.situ.aichat.world.WorldClock
import com.situ.aichat.world.WorldSettlementCoordinator
import com.situ.aichat.world.bulletin.WorldBulletinService
import com.situ.aichat.world.cast.WorldAffinityService
import com.situ.aichat.world.notify.WorldNotifyService
import com.situ.aichat.world.travel.WorldTravelService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 回前台**总编排**（W5 图纸 §3.2 回前台链·契约 §9）：把「离开期间」的世界一次性接上四个出口——懒结算 →
 * 抄写双视角记忆 → 落情绪轻碰 → 拼开机小报 → 排嵌入回填 worker。**W5 = 结算的第一个真实消费者**，`ensureSettled`
 * 在此正式接线（W2 起一直无生产调用点）。
 *
 * 韧性铁则（世界永不死机）：[Mutex] 串行防 ON_RESUME 连发重入（+ AppViewModel 守卫 job 双保险）；世界未初始化
 * 直接零税返回；窗空（首启/冻结）直接返回；**每一步独立 try/catch**——单步失败记 [Log.w] 不拦后续步骤、绝不 crash。
 * 全程只打计数、绝不打内容（logging 约定）。**绝不碰 UI/Compose**（小报只存库·呈现归 W11）。
 */
@Singleton
class WorldLinkRunner @Inject constructor(
    private val worldDao: WorldDao,
    private val coordinator: WorldSettlementCoordinator,
    private val memoryScribe: WorldMemoryScribe,
    private val moodSettler: WorldMoodSettler,
    private val bulletinService: WorldBulletinService,
    private val backgroundScheduler: BackgroundScheduler,
    private val affinityService: WorldAffinityService,
    private val travelService: WorldTravelService,
    private val notifyService: WorldNotifyService,
) {
    private val mutex = Mutex()

    /** 回前台通行证（[now] = AppViewModel 入口 System.currentTimeMillis()·仅作结算/查询上界与日志）。 */
    suspend fun runForegroundPass(now: Long) = mutex.withLock {
        val state = worldDao.getState() ?: return@withLock // 世界未初始化 → 零税

        // step 0.5（W8·§3.5）：回 app 即撤世界合并摘要——**在空窗早返之前**（含空窗都撤）·独立 try/catch·撤失败不拦后续。
        try {
            notifyService.onAppForeground()
        } catch (e: Exception) {
            Log.w(TAG, "前台通行证：撤世界摘要失败", e)
        }

        // step 1.5（W6·§3.4）：播种原住民状态行（只补缺行·让 20 行入库进备份）——独立 try/catch·播种失败不拦结算。
        try {
            affinityService.ensureSeeded()
        } catch (e: Exception) {
            Log.w(TAG, "前台通行证：原住民播种失败", e)
        }

        val window = try {
            coordinator.ensureSettled(now)
        } catch (e: Exception) {
            Log.w(TAG, "前台通行证：结算失败", e)
            return@withLock
        }
        if (window.days.isEmpty()) return@withLock // 首启/冻结 → 无「离开期间」可接

        // step 3.5（W7·§3.2）：结算旅行到达（用户就地/角色来访+返程）——**结算后·小报之前**（到达事件要进当次小报）·独立 try/catch。
        try {
            travelService.settleArrivals(now)
        } catch (e: Exception) {
            Log.w(TAG, "前台通行证：旅行到达结算失败", e)
        }

        val zone = WorldClock.resolveZone(state.userTimezoneId)
        val lowerBound = window.days.first().date.atStartOfDay(zone).toInstant().toEpochMilli() - LOOKBACK_MS

        var newMemories = 0
        try {
            newMemories = memoryScribe.scribeSince(lowerBound)
        } catch (e: Exception) {
            Log.w(TAG, "前台通行证：记忆抄写失败", e)
        }
        try {
            moodSettler.settle(state, window, zone)
        } catch (e: Exception) {
            Log.w(TAG, "前台通行证：情绪落笔失败", e)
        }
        var bulletinUpdated = false
        try {
            bulletinUpdated = bulletinService.refresh(state, window, zone, now)
        } catch (e: Exception) {
            Log.w(TAG, "前台通行证：小报刷新失败", e)
        }
        if (newMemories > 0) {
            try {
                backgroundScheduler.scheduleOneShot(
                    uniqueName = EmbeddingBackfillWorker.UNIQUE_ENSURE,
                    workerClass = EmbeddingBackfillWorker::class.java,
                    requireNetwork = false,
                    existingPolicy = ExistingWorkPolicy.KEEP,
                )
            } catch (e: Exception) {
                Log.w(TAG, "前台通行证：嵌入回填排程失败", e)
            }
        }

        // 观测点（只打计数·绝不打内容·图纸 §3.2 step8）。
        Log.d(TAG, "前台通行证：结算 ${window.days.size} 天 · 新记忆 $newMemories 条 · 小报 ${if (bulletinUpdated) "更新" else "跳过"}")
    }

    companion object {
        private const val TAG = "WorldLink"

        /** 回看窗（= MAX_CATCHUP_DAYS 同宽·自愈截断缝隙 + 崩溃残缝·图纸 §3.2/§9）。 */
        private const val LOOKBACK_MS = 7L * 86_400_000L
    }
}
