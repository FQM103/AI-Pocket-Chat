package com.situ.aichat.ui.backup

import android.os.Looper
import androidx.work.ExistingWorkPolicy
import com.situ.aichat.data.backup.BackupService
import com.situ.aichat.data.backup.ImportResult
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.foreground.LlmGenerationForegroundController
import com.situ.aichat.work.BackgroundScheduler
import com.situ.aichat.work.EmbeddingBackfillWorker
import com.situ.aichat.work.NotificationRescheduleWorker
import com.situ.aichat.work.ReliabilityPromptController
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [BackupViewModel] 导入后重排入队 T2-3（W14 图纸 §3.2③/§5 E2·Robolectric 主循环 + mockk backupService/backgroundScheduler）：
 * 导入 Success → 追加入队 [NotificationRescheduleWorker]（UNIQUE_ONESHOT·REPLACE）恰一次；导入失败 → 零入队。
 * 断言从图纸 §3.2③ 独立反推（此入队点在 VM 层·天然在导入事务之外·图纸 §6 红线）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupViewModelRescheduleTest {

    private lateinit var backupService: BackupService
    private lateinit var scheduler: BackgroundScheduler
    private lateinit var vm: BackupViewModel

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        backupService = mockk()
        scheduler = mockk(relaxed = true)
        vm = BackupViewModel(
            backupService = backupService,
            backgroundScheduler = scheduler,
            settingsRepo = mockk(relaxed = true),
            reliabilityPromptController = mockk(relaxed = true),
            llmForeground = mockk(relaxed = true),
        )
        // 卷 A：startImport 先探一次「源打得开吗」（取代旧的「整包读回来是不是 null」）。
        coEvery { backupService.canOpen(any()) } returns true
        // 非 zip 路径（previewArchive=null）→ startImport 直接走 runImport。
        coEvery { backupService.previewArchive(any()) } returns null
    }

    /** E2：导入 Success → NotificationRescheduleWorker 入队恰一次（REPLACE）+ backfill 入队顺带一条（KEEP）。 */
    @Test
    fun `导入成功_重排任务入队一次REPLACE`() = runBlocking {
        coEvery { backupService.importArchive(any(), any(), any()) } returns ImportResult.Success(1, 0, 0, 0, 5)

        vm.startImport { byteArrayOf(1).inputStream() }
        idle()

        // 全六参位显式（初次延迟/inputData=any），避开 MockK 默认参数与 matcher 混用陷阱。
        verify(exactly = 1) {
            scheduler.scheduleOneShot(
                eq(NotificationRescheduleWorker.UNIQUE_ONESHOT),
                eq(NotificationRescheduleWorker::class.java),
                any(),                          // initialDelay
                eq(false),                      // requireNetwork
                eq(ExistingWorkPolicy.REPLACE), // existingPolicy
                any(),                          // inputData
            )
        }
        verify(exactly = 1) {
            scheduler.scheduleOneShot(
                eq(EmbeddingBackfillWorker.UNIQUE_ENSURE),
                eq(EmbeddingBackfillWorker::class.java),
                any(),
                eq(false),
                eq(ExistingWorkPolicy.KEEP),
                any(),
            )
        }
    }

    /** 导入失败（Error）→ 重排 + backfill 均零入队。 */
    @Test
    fun `导入失败_重排任务零入队`() = runBlocking {
        coEvery { backupService.importArchive(any(), any(), any()) } returns ImportResult.Error("boom")

        vm.startImport { byteArrayOf(1).inputStream() }
        idle()

        verify(exactly = 0) {
            scheduler.scheduleOneShot(
                eq(NotificationRescheduleWorker.UNIQUE_ONESHOT),
                eq(NotificationRescheduleWorker::class.java),
                any(), any(), any(), any(),
            )
        }
        verify(exactly = 0) {
            scheduler.scheduleOneShot(
                eq(EmbeddingBackfillWorker.UNIQUE_ENSURE),
                eq(EmbeddingBackfillWorker::class.java),
                any(), any(), any(), any(),
            )
        }
    }
}
