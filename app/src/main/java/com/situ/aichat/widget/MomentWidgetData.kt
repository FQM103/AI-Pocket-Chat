package com.situ.aichat.widget

import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.data.model.MomentAuthorType

/**
 * 最新动态（朋友圈）小组件的纯逻辑（13.9b · C1，安卓超越 iOS——iOS 无朋友圈小组件）。
 *
 * 小组件展示最新一条**角色**朋友圈动态（用户自己的帖子不算「新动态」，对齐 P13.7e 新动态通知只推角色帖）：
 * 头像 + 名字 + 内容摘要 + 缩略图 + 相对时间，点进帖子详情。这里只放「选哪条」的纯函数，便于单测；
 * 取数 / 取图 / 渲染在 [MomentFeedGlanceWidget]。
 */
object MomentWidgetData {
    /**
     * 选最新一条「角色」朋友圈动态（authorType=character、未软删，按 timestamp 取最新）。无 → null。
     * 既用于 provideGlance 的快照（已是角色帖列表），也用于 [MomentWidgetSync] 的全量 feed（含用户帖，故此处过滤）。
     */
    fun pickLatestCharacterPost(posts: List<MomentPostEntity>): MomentPostEntity? =
        posts
            .filter { it.authorTypeRaw == MomentAuthorType.CHARACTER.raw && !it.isSoftDeleted }
            .maxByOrNull { it.timestamp }
}

/**
 * 小组件渲染快照（轻量）。头像 / 缩略图位图另由 [com.situ.aichat.util.AvatarStore] /
 * [com.situ.aichat.util.ContentImageStore] 解码，不入此快照。[content] 可能为空（纯图片帖，视图回退说明文案）。
 */
data class MomentWidgetState(
    val postUuid: String,
    val authorName: String,
    val avatarPath: String?,
    val content: String,
    val timeText: String,
    /** 首图绝对路径（无图 → null，中号组件据此决定是否显示缩略图）。 */
    val imagePath: String?,
)
