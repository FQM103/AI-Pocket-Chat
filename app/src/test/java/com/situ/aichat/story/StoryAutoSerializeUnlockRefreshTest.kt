package com.situ.aichat.story

import android.content.Context
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.foreground.LlmGenerationForegroundController
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 查询瘦身卷二 T2-5（图纸 §7·E7/E8）：解锁闹钟重排的「进程首跑 ∨ 防抖到期」双门。
 *
 * 重排每次都对章节表做无索引全表扫，同进程反复切前后台不该重复付这个钱；但兜底语义（精确闹钟不跨重启）
 * 必须靠「进程首次回前台必跑」保住 —— 故**新建实例 = 新进程**这一路无论时间戳多新都要重排。
 * 时间一律相对真实 now 构造（生产码内部直取 System.currentTimeMillis·PITFALLS §1e）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryAutoSerializeUnlockRefreshTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val storyRepository = mockk<StoryRepository>(relaxed = true)
    private val generationService = mockk<StoryGenerationService>(relaxed = true)
    private val taskManager = mockk<StoryGenerationTaskManager>(relaxed = true)
    private val unlockScheduler = mockk<StoryUnlockNotificationScheduler>(relaxed = true)
    private val foregroundController = mockk<LlmGenerationForegroundController>(relaxed = true)

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 每跑完一趟前台 pass，第三步都会拉一次 serializing 列表——用它当「这一趟真跑完了」的证据。 */
    private var passesFinished = 0

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        coEvery { storyRepository.getStoriesByStatus(StoryStatus.SERIALIZING) } answers {
            passesFinished++
            emptyList()
        }
    }

    private fun newService() = StoryAutoSerializeService(
        context, storyRepository, generationService, taskManager, unlockScheduler, foregroundController,
    )

    /** 跑一趟前台 pass 并等它真跑完（第三步的生成检查防抖先清零，保证末步一定到达）。 */
    private fun runForegroundPass(service: StoryAutoSerializeService) {
        prefs.edit().putLong(KEY_LAST_CHECK_AT, 0L).commit()
        val before = passesFinished
        service.onAppForeground()
        repeat(400) {
            if (passesFinished > before) return
            Thread.sleep(5)
        }
        error("等待超时：前台 pass 没跑完")
    }

    /** E7：进程首次回前台必重排（兜底语义靠这一跑）。 */
    @Test
    fun `进程首跑必重排`() {
        runForegroundPass(newService())

        coVerify(exactly = 1) { unlockScheduler.refreshAllUnlockNotifications(any()) }
    }

    /** E8：同进程 10 分钟内反复切前后台 → 重排只跑第一次。 */
    @Test
    fun `同进程防抖内二跑不重排`() {
        val service = newService()

        runForegroundPass(service)
        runForegroundPass(service)
        runForegroundPass(service)

        coVerify(exactly = 1) { unlockScheduler.refreshAllUnlockNotifications(any()) }
    }

    /** 防抖到期（时间戳倒拨过一个间隔）→ 同进程也照样重排。 */
    @Test
    fun `防抖到期后再回前台_重新重排`() {
        val service = newService()
        runForegroundPass(service)

        prefs.edit().putLong(
            KEY_LAST_UNLOCK_REFRESH_AT,
            System.currentTimeMillis() - StoryAutoSerializePolicy.MINIMUM_CHECK_INTERVAL_MS - 1_000L,
        ).commit()
        runForegroundPass(service)

        coVerify(exactly = 2) { unlockScheduler.refreshAllUnlockNotifications(any()) }
    }

    /** E7 兜底：进程重启（= 新实例）后哪怕时间戳还很新，首次回前台仍必须重排——闹钟不跨重启。 */
    @Test
    fun `进程重启后防抖内回前台_仍然重排`() {
        runForegroundPass(newService())      // 旧进程刚排过，时间戳是刚刚
        runForegroundPass(newService())      // 新进程：内存标志复位 → 必跑

        coVerify(exactly = 2) { unlockScheduler.refreshAllUnlockNotifications(any()) }
    }

    /** 两条防抖各记各的：解锁重排跳过时，生成检查的时间戳一个字节都不动（独立 key）。 */
    @Test
    fun `解锁重排与生成检查的防抖时间戳互不干扰`() {
        val service = newService()
        runForegroundPass(service)
        val unlockAt = prefs.getLong(KEY_LAST_UNLOCK_REFRESH_AT, 0L)

        runForegroundPass(service) // 第二趟：解锁重排被防抖跳过、生成检查照跑（runForegroundPass 已清零其 key）

        org.junit.Assert.assertEquals("解锁时间戳停在第一趟", unlockAt, prefs.getLong(KEY_LAST_UNLOCK_REFRESH_AT, 0L))
        org.junit.Assert.assertTrue("生成检查时间戳被第二趟重写", prefs.getLong(KEY_LAST_CHECK_AT, 0L) > 0L)
    }

    private companion object {
        const val PREFS = "story_schedule_prefs"
        const val KEY_LAST_CHECK_AT = "storySchedule.lastCheckAt"
        const val KEY_LAST_UNLOCK_REFRESH_AT = "storySchedule.lastUnlockRefreshAt"
    }
}
