package com.situ.aichat.data.repository

import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.util.AudioStore
import com.situ.aichat.util.ContentImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 删会话前清理其磁盘媒体文件（语音 / 图片 / 缩略图），1:1 iOS `CharacterMediaCleanupService.cleanupConversation`。
 *
 * **为什么需要**：Room FK CASCADE 删会话时只清数据库消息行，**不删磁盘文件**（音频/图片落在 filesDir 下、
 * 由 [AudioStore] / [ContentImageStore] 管理）。任何删会话路径必须先调本清理器，否则文件孤儿残留。
 *
 * **调用约束**：必须在 [ConversationRepository.deleteById] **之前**调用（iOS 顺序：先清磁盘媒体，后删库行）。
 *
 * 注：iOS 还清会话壁纸文件（ConversationWallpaperStore），安卓 [com.situ.aichat.data.local.entity.ConversationEntity]
 * 暂无壁纸字段（壁纸功能未移植），故此处不含壁纸清理；壁纸落地时一并补。
 */
@Singleton
class ConversationMediaCleaner @Inject constructor(
    private val messageDao: MessageDao,
) {
    /** 清理会话 [conversationUuid] 全部消息的磁盘媒体文件。须在删会话库行之前调用。 */
    suspend fun cleanup(conversationUuid: String) {
        val media = messageDao.mediaPathsForConversation(conversationUuid)
        if (media.isEmpty()) return
        withContext(Dispatchers.IO) {
            for (m in media) {
                AudioStore.delete(m.audioRelativePath)
                ContentImageStore.delete(m.imageRelativePath)
                ContentImageStore.delete(m.imageThumbnailRelativePath)
            }
        }
    }
}
