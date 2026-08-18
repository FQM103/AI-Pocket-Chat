package com.situ.aichat.diagnostics

import android.app.job.JobScheduler
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ③ 后台诊断的纯映射函数单测：原因码 → 人话。验证关键码（Android16 收紧的 QUOTA、国产 ROM 嫌疑的
 * BACKGROUND_RESTRICTION/APP_STANDBY）落到可读标签，未知码回退原值（不丢信息）。
 *
 * 全为编译期 int 常量映射，纯 JVM 即可，无需 Robolectric/框架。
 */
class BackgroundWorkDiagnosticsTest {

    @Test
    fun stopReason_未停码_映射为未停() {
        assertEquals("未停", BackgroundWorkDiagnostics.stopReasonLabel(WorkInfo.STOP_REASON_NOT_STOPPED))
    }

    @Test
    fun stopReason_关键码_含原始英文标记便于检索() {
        assertTrue(BackgroundWorkDiagnostics.stopReasonLabel(WorkInfo.STOP_REASON_QUOTA).contains("QUOTA"))
        assertTrue(BackgroundWorkDiagnostics.stopReasonLabel(WorkInfo.STOP_REASON_TIMEOUT).contains("TIMEOUT"))
        assertTrue(BackgroundWorkDiagnostics.stopReasonLabel(WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION).isNotBlank())
        assertTrue(BackgroundWorkDiagnostics.stopReasonLabel(WorkInfo.STOP_REASON_APP_STANDBY).contains("APP_STANDBY"))
    }

    @Test
    fun stopReason_未知码_回退原始码不丢信息() {
        assertEquals("原因码 9999", BackgroundWorkDiagnostics.stopReasonLabel(9999))
    }

    @Test
    fun pendingJobReason_关键码_含原始英文标记便于检索() {
        assertTrue(BackgroundWorkDiagnostics.pendingJobReasonLabel(JobScheduler.PENDING_JOB_REASON_QUOTA).contains("QUOTA"))
        assertTrue(
            BackgroundWorkDiagnostics.pendingJobReasonLabel(JobScheduler.PENDING_JOB_REASON_BACKGROUND_RESTRICTION)
                .contains("BACKGROUND_RESTRICTION"),
        )
        assertTrue(
            BackgroundWorkDiagnostics.pendingJobReasonLabel(JobScheduler.PENDING_JOB_REASON_APP_STANDBY)
                .contains("APP_STANDBY"),
        )
    }

    @Test
    fun pendingJobReason_未知码_回退原始码不丢信息() {
        assertEquals("原因码 12345", BackgroundWorkDiagnostics.pendingJobReasonLabel(12345))
    }
}
