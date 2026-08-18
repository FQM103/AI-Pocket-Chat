package com.situ.aichat.data.local.dao

/**
 * 一批会话中最后一条非系统非空消息的元信息投影（[MessageDao.latestNonSystemAcross] 用）。
 * 对话状态判定（相位/谁最后说话）与到点竞态终查（生成期间是否有新消息）共用。
 * 字段名须与 messages 表列名一致（Room 投影按列名匹配）。
 */
data class LatestMessageMeta(
    val timestamp: Long,
    val roleRaw: String,
    val messageUUID: String,
)
