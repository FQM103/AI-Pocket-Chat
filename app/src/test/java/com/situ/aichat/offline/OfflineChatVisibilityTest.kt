package com.situ.aichat.offline

import com.situ.aichat.data.model.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OfflineChatVisibility] 可见性谓词规格（与 DAO 三条「可见消息」查询的同源 SQL 互为镜像）：
 *  - 系统耳语 SYSTEM_HINT（给 AI 的旁白）按类型无条件隐藏——含非线下场景（如「取消见面」提示）；
 *  - 线下见面期间一切消息隐藏、仅离场标记保留（方案 A·2026-06-18 用户拍板）；
 *  - 语音通话逐轮转写（isPartOfVoiceCall=true）按旗无条件隐藏、仅通话记录卡保留（2026-07-12 用户拍板）；
 *  - 邀约卡与普通消息（isOfflineMode=false）不受影响。
 */
class OfflineChatVisibilityTest {

    private fun hidden(isOffline: Boolean, kind: MessageKind, isPartOfVoiceCall: Boolean = false) =
        OfflineChatVisibility.isHiddenFromDailyChat(isOffline, kind, isPartOfVoiceCall)

    @Test
    fun `离场标记保留在日常聊天`() {
        assertFalse(hidden(true, MessageKind.OFFLINE_MARKER_END))
    }

    @Test
    fun `见面期间AI叙事隐藏`() {
        assertTrue(hidden(true, MessageKind.PLAIN_TEXT))
    }

    @Test
    fun `入场标记隐藏`() {
        assertTrue(hidden(true, MessageKind.OFFLINE_MARKER_START))
    }

    @Test
    fun `准备出发确认隐藏`() {
        // CONFIRM_ACCEPT / CONFIRM_MANUAL / 续场 hint 均为 isOfflineMode=true 的 SYSTEM_HINT
        assertTrue(hidden(true, MessageKind.SYSTEM_HINT))
    }

    @Test
    fun `系统耳语在非线下聊天也隐藏`() {
        // 「取消见面」提示 = roleRaw=user + SYSTEM_HINT + isOfflineMode=false（点开发起见面表单又取消·未进线下模式）。
        // 只喂模型让角色感知、用户永不可见——必须按类型隐藏，不能只靠线下旗，否则以聊天气泡漏给用户·破坏沉浸。
        assertTrue(hidden(false, MessageKind.SYSTEM_HINT))
    }

    @Test
    fun `结束确认卡隐藏`() {
        assertTrue(hidden(true, MessageKind.OFFLINE_END_CARD))
    }

    @Test
    fun `邀约卡是进入见面前的普通聊天消息_保留`() {
        // handleSuggestMeeting 插入时不带 isOfflineMode（默认 false）→ 正常留在日常聊天
        assertFalse(hidden(false, MessageKind.OFFLINE_INVITE_CARD))
    }

    @Test
    fun `普通日常消息及其它卡片保留`() {
        assertFalse(hidden(false, MessageKind.PLAIN_TEXT))
        assertFalse(hidden(false, MessageKind.GIFT_CARD))
        assertFalse(hidden(false, MessageKind.RED_PACKET))
        assertFalse(hidden(false, MessageKind.SCHEDULE_CARD))
        assertFalse(hidden(false, MessageKind.CALL_RECORD_CARD))
    }

    @Test
    fun `非线下消息除系统耳语外一律不被隐藏`() {
        // isOfflineMode=false 时，除「系统耳语」SYSTEM_HINT（见上一测·按类型隐藏）外任何 kind 都不隐藏（含理论上的 END marker）。
        MessageKind.entries
            .filter { it != MessageKind.SYSTEM_HINT }
            .forEach { kind ->
                assertFalse("kind=$kind 不应在非线下时被隐藏", hidden(false, kind))
            }
    }

    @Test
    fun `语音通话逐轮转写隐藏_用户与AI两侧同滤`() {
        // VoiceCallPersistence.saveUserMessage / saveAiMessage 落的都是 isPartOfVoiceCall=true 的 plainText：
        // 通话里说的话不该以气泡出现在聊天流（用户 2026-07-12 实机上报「你在干什么」气泡泄漏），只留通话记录卡。
        assertTrue(hidden(false, MessageKind.PLAIN_TEXT, isPartOfVoiceCall = true))
    }

    @Test
    fun `通话记录卡本身不带通话旗_保留`() {
        // saveCallRecord 落卡时不设 isPartOfVoiceCall → 卡片是用户回看整段通话的唯一入口，绝不能被③误伤。
        assertFalse(hidden(false, MessageKind.CALL_RECORD_CARD, isPartOfVoiceCall = false))
    }

    // MARK: - outgoingOfflineSessionId（写入侧对偶：见面期用户消息打标，缺它则漏进普通聊天 + 缺席沉浸剧场）

    @Test
    fun `见面期间用户消息随当前 sessionId 打标`() {
        assertEquals("sess-1", outgoingOfflineSessionId(isInOfflineMode = true, currentOfflineSessionId = "sess-1"))
    }

    @Test
    fun `非线下时不打标_返回null`() {
        // 哪怕残留了 sessionId（进入/退出本应同事务清空），非线下也绝不打标——否则普通消息被错藏进见面、还从普通聊天消失。
        assertNull(outgoingOfflineSessionId(isInOfflineMode = false, currentOfflineSessionId = "sess-stale"))
    }

    @Test
    fun `线下但缺 sessionId 的退化态_返回null`() {
        // isInOfflineMode 与 currentOfflineSessionId 耦合写（同事务进入/退出），理论不单边；真出现则不归任何 session（落库 isOfflineMode=false，=助手口径）。
        assertNull(outgoingOfflineSessionId(isInOfflineMode = true, currentOfflineSessionId = null))
    }
}
