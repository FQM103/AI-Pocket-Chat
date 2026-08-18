package com.situ.aichat.tooling

/**
 * 日历操作「真失败」回流（②·陪伴改良版）的暂存数据。
 *
 * 由 [com.situ.aichat.ui.chat.ChatCalendarActionHandler] 在 `executeCalendarAction` 的**真执行失败**路记下
 * （per-会话·内存字段·进程死亡可接受丢失=非钱路）；下一轮该会话装配时由
 * [com.situ.aichat.ui.chat.AssistantTurnEngine] 经 TTL **一次性**消费，[com.situ.aichat.prompt.PromptBuilder]
 * 注入陪伴口吻系统提示，让角色据实自然找补（提一句 / 请用户再说一次时间），绝不谎称已办成。
 *
 * **只记真失败**（没认出时间 / 没找到那条日程 / 写入手机日历出错），绝不记「待确认」（确认卡尚未执行 ≠ 失败）。
 * 范围聚焦日历写入——线下/约见面只是「发卡片」无真执行可失败（D-4 边界核）。
 */
data class PendingCalendarFailure(
    /** 动作动词（创建 / 修改 / 删除）。 */
    val verb: String,
    /** 事件标题。 */
    val title: String,
    /** 给角色看的**人话**原因（绝不漏 e.message 等技术错误）。 */
    val reason: String,
    /** 记录时刻（epoch ms）·供 TTL 新鲜度判定。 */
    val recordedAtMillis: Long,
)
