package com.situ.aichat.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-7 确定性进度纯函数单测。权重为自定设计（iOS 全程不确定转圈无数值真值，CharacterBackupView.swift:125-133，
 * 安卓超越项·13.6 拍板背书）——锁的是不变量：不除零、钳位、阶段单调、终点收敛 1。
 */
class BackupProgressTest {

    private fun f(stage: BackupProgress.Stage, done: Int, total: Int): Float =
        overallFraction(BackupProgress(stage, done, total))

    @Test fun totalZero_noDivByZero_treatedAsStageComplete() {
        // 纯文本备份无媒体：WRITE_MEDIA total=0 → 该阶段视为已完成（=阶段终点 0.9）。
        assertEquals(0.9f, f(BackupProgress.Stage.WRITE_MEDIA, 0, 0), 1e-6f)
        assertEquals(0.3f, f(BackupProgress.Stage.COLLECT, 0, 0), 1e-6f)
    }

    @Test fun doneOverTotal_clampedToStageEnd() {
        assertEquals(0.3f, f(BackupProgress.Stage.COLLECT, 99, 10), 1e-6f)
        assertEquals(1f, f(BackupProgress.Stage.COPY, 2048, 1024), 1e-6f)
    }

    @Test fun negativeDone_clampedToStageStart() {
        assertEquals(0f, f(BackupProgress.Stage.COLLECT, -3, 10), 1e-6f)
        assertEquals(0.3f, f(BackupProgress.Stage.WRITE_MEDIA, -1, 5), 1e-6f)
    }

    @Test fun exportStages_monotonicAcrossStageBoundaries() {
        // COLLECT 全程 < WRITE_MEDIA 起点之后的值 < COPY 终点；同阶段内随 done 单调不减。
        val collectEnd = f(BackupProgress.Stage.COLLECT, 10, 10)
        val mediaStart = f(BackupProgress.Stage.WRITE_MEDIA, 0, 10)
        val mediaEnd = f(BackupProgress.Stage.WRITE_MEDIA, 10, 10)
        val copyEnd = f(BackupProgress.Stage.COPY, 10, 10)
        assertEquals(collectEnd, mediaStart, 1e-6f) // 阶段边界严丝合缝（0.3）
        assertTrue(mediaEnd > mediaStart)
        assertEquals(1f, copyEnd, 1e-6f)
        assertTrue(f(BackupProgress.Stage.COLLECT, 3, 10) < f(BackupProgress.Stage.COLLECT, 7, 10))
    }

    @Test fun importStages_spanZeroToOne() {
        assertEquals(0f, f(BackupProgress.Stage.RESTORE_MEDIA, 0, 10), 1e-6f)
        assertEquals(0.5f, f(BackupProgress.Stage.RESTORE_MEDIA, 10, 10), 1e-6f)
        assertEquals(0.5f, f(BackupProgress.Stage.WRITE_DB, 0, 4), 1e-6f)
        assertEquals(1f, f(BackupProgress.Stage.WRITE_DB, 4, 4), 1e-6f)
    }

    @Test fun midStage_linearWithinWeights() {
        // COLLECT 半程 = 0.15；WRITE_DB 半程 = 0.75。
        assertEquals(0.15f, f(BackupProgress.Stage.COLLECT, 5, 10), 1e-6f)
        assertEquals(0.75f, f(BackupProgress.Stage.WRITE_DB, 2, 4), 1e-6f)
    }
}
