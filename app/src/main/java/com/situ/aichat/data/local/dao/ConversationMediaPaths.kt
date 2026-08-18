package com.situ.aichat.data.local.dao

/**
 * 一条消息的磁盘媒体路径投影（[MessageDao.mediaPathsForConversation] 用）。删会话前据此清音频/图片/缩略图文件。
 * 字段名须与 messages 表列名一致（Room 投影按列名匹配）。
 */
data class ConversationMediaPaths(
    val audioRelativePath: String?,
    val imageRelativePath: String?,
    val imageThumbnailRelativePath: String?,
)
