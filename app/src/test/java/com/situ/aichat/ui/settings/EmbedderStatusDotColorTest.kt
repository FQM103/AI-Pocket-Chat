package com.situ.aichat.ui.settings

import com.situ.aichat.prompt.memory.TextEmbedder.LoadState
import com.situ.aichat.ui.designsystem.LightAppColors
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [dotColorFor] 三态色点映射 T1（记忆健壮性 #3）：补上渲染测试够不着的「装饰色」断言——
 * [EmbedderStatusRowTest] 只验文字，若色点 when 被误配（如 FAILED→柔绿、LOADED→琥珀）渲染测试仍全绿。
 * 此处用 [LightAppColors] 直取期望色，把色点映射也纳入回归网。纯函数，零渲染。
 */
class EmbedderStatusDotColorTest {

    @Test fun loaded_isSuccessGreen() =
        assertEquals(LightAppColors.status.onSuccess, dotColorFor(LoadState.LOADED, LightAppColors))

    @Test fun notAttempted_isNeutralTertiary() =
        assertEquals(LightAppColors.text.tertiary, dotColorFor(LoadState.NOT_ATTEMPTED, LightAppColors))

    @Test fun failed_isWarningAmber() =
        assertEquals(LightAppColors.status.onWarning, dotColorFor(LoadState.FAILED, LightAppColors))
}
