package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Mirrors the iOS `Message` @Model.
 * `embedding` holds the serialized semantic vector (iOS stored NLEmbedding [Double] as Data);
 * `messageKindRaw` is the MessageKind tag of the algebraic content type (default "plain_text").
 * Audio/image are referenced by relative file path (iOS used external storage), no large BLOBs.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["conversationUuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("conversationUuid"),
        Index("timestamp"),
        Index(value = ["conversationUuid", "timestamp"]),
    ],
)
data class MessageEntity(
    @PrimaryKey val messageUUID: String,
    val conversationUuid: String,
    val roleRaw: String,
    val content: String,
    val timestamp: Long,

    val isVoiceMessage: Boolean = false,
    val isPartOfVoiceCall: Boolean = false,
    val audioRelativePath: String? = null,
    val audioDuration: Double? = null,

    val imageRelativePath: String? = null,
    val imageThumbnailRelativePath: String? = null,
    val mediaMemorySummary: String = "",

    // Serialized semantic embedding (mirrors iOS Message.embeddingData)
    val embedding: ByteArray? = null,

    val isContentRevealed: Boolean = true,
    val isHeldForDelivery: Boolean = false,
    val scheduledDeliveryDate: Long? = null,

    // Quote reply
    val quotedMessageUUID: String? = null,
    val quotedContent: String? = null,
    val quotedSenderRole: String? = null,

    val emotionTag: String? = null,
    val isPetMessage: Boolean = false,

    val isOfflineMode: Boolean = false,
    val offlineSessionId: String? = null,

    val messageKindRaw: String = "plain_text",
) {
    // ByteArray needs explicit equals/hashCode for a correct data-class contract.
    // 审计 P1（2026-07-02）：由「窄比较（uuid/timestamp/content/embedding）」升级为**全字段结构比较**——
    // 窄版会把「插入后更新的其他列」（isContentRevealed / audio* / isHeldForDelivery…）判为相等，
    // StateFlow 去重与 Compose 行级 skip（compose_stability.conf 声明本类 stable 后走 equals）会静默吞掉这类刷新。
    // embedding 仍按内容比较（数组无结构 equals）；聊天窗口流已在仓库出口剥离 embedding（见
    // MessageRepository.observeVisibleWindowed），后台向量 backfill 不再幽灵刷新整屏。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageEntity) return false
        if (messageUUID != other.messageUUID) return false
        if (conversationUuid != other.conversationUuid) return false
        if (roleRaw != other.roleRaw) return false
        if (content != other.content) return false
        if (timestamp != other.timestamp) return false
        if (isVoiceMessage != other.isVoiceMessage) return false
        if (isPartOfVoiceCall != other.isPartOfVoiceCall) return false
        if (audioRelativePath != other.audioRelativePath) return false
        if (audioDuration != other.audioDuration) return false
        if (imageRelativePath != other.imageRelativePath) return false
        if (imageThumbnailRelativePath != other.imageThumbnailRelativePath) return false
        if (mediaMemorySummary != other.mediaMemorySummary) return false
        if (isContentRevealed != other.isContentRevealed) return false
        if (isHeldForDelivery != other.isHeldForDelivery) return false
        if (scheduledDeliveryDate != other.scheduledDeliveryDate) return false
        if (quotedMessageUUID != other.quotedMessageUUID) return false
        if (quotedContent != other.quotedContent) return false
        if (quotedSenderRole != other.quotedSenderRole) return false
        if (emotionTag != other.emotionTag) return false
        if (isPetMessage != other.isPetMessage) return false
        if (isOfflineMode != other.isOfflineMode) return false
        if (offlineSessionId != other.offlineSessionId) return false
        if (messageKindRaw != other.messageKindRaw) return false
        if (embedding != null) {
            if (other.embedding == null) return false
            if (!embedding.contentEquals(other.embedding)) return false
        } else if (other.embedding != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = messageUUID.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        return result
    }
}
