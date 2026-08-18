package com.situ.aichat.prompt

import com.situ.aichat.ui.promptmodule.SceneBadgeState
import com.situ.aichat.ui.promptmodule.sceneBadgeState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 徽章四态纯函数（两语境模型 v2·图纸 §4-U2）：只看 ONLINE_CHAT/OFFLINE_MEETING 两有效位（null=双含），
 * VOICE_CALL/BUSY_REPLY 死位不影响判定。断言从图纸 §7 徽章矩阵独立反推。
 */
class SceneBadgeStateTest {

    @Test fun nullMeansBoth() =
        assertEquals(SceneBadgeState.CHAT_AND_MEET, sceneBadgeState(null))

    @Test fun onlineOnly() =
        assertEquals(SceneBadgeState.CHAT_ONLY, sceneBadgeState(setOf(PromptScene.ONLINE_CHAT)))

    @Test fun offlineOnly() =
        assertEquals(SceneBadgeState.MEET_ONLY, sceneBadgeState(setOf(PromptScene.OFFLINE_MEETING)))

    @Test fun emptyMeansNone() =
        assertEquals(SceneBadgeState.NONE, sceneBadgeState(emptySet()))

    @Test fun deadVoiceBitDoesNotAffect_chatOnly() =
        assertEquals(SceneBadgeState.CHAT_ONLY, sceneBadgeState(setOf(PromptScene.ONLINE_CHAT, PromptScene.VOICE_CALL)))

    @Test fun allFourBitsMeansBoth() =
        assertEquals(
            SceneBadgeState.CHAT_AND_MEET,
            sceneBadgeState(setOf(PromptScene.ONLINE_CHAT, PromptScene.OFFLINE_MEETING, PromptScene.VOICE_CALL, PromptScene.BUSY_REPLY)),
        )

    @Test fun deadBitsOnlyMeansNone() =
        // 只含死位（无 ONLINE/OFFLINE）→ 两有效位皆不含 → NONE。
        assertEquals(SceneBadgeState.NONE, sceneBadgeState(setOf(PromptScene.VOICE_CALL, PromptScene.BUSY_REPLY)))
}
