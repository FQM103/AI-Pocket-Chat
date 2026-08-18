package com.situ.aichat.moments

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MomentCommentEntity
import com.situ.aichat.data.local.entity.MomentLikeEntity
import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.data.model.MomentAuthorType
import com.situ.aichat.data.model.MomentChatContext
import com.situ.aichat.data.model.imagePaths
import com.situ.aichat.data.repository.MomentRepository
import com.situ.aichat.prompt.schedule.CharacterSleepChecker
import com.situ.aichat.util.DateFormatters
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 构建某角色与用户最近 7 天的朋友圈互动摘要，供聊天时注入系统提示词（M06 7.2.6）。
 * 1:1 移植 iOS `MomentGenerationService.buildMomentContext`（+Context.swift:42-183）。
 *
 * 两段：① 角色自己发的帖（max 3）+ 用户的点赞/评论反应；② 用户发的、该角色有互动（赞/评）的帖（max 3）。
 * **精准矛盾过滤**：当前角色"不便用手机"（睡眠/开会）且某帖也发于同类时段 → 跳过该帖，避免聊天里
 * 【此刻】"你在睡觉"与【朋友圈】"21 分钟前的凌晨动态"打架（白天读自己的深夜动态属正常回忆，不过滤）。
 * 两段都空 → 返回 null（不注入）。纯格式化拆成 companion internal 函数便于单测（断言反推 iOS 格式串）。
 *
 * 由 [com.situ.aichat.ui.chat.ChatViewModel] 每轮聊天构建 → 传入 PromptBuilder 的 BuildContext，
 * 由 MOMENTS_CONTEXT 模块（`PromptBuilderMoments.buildMomentsContextContent`）渲染。
 */
@Singleton
class MomentChatContextService @Inject constructor(
    private val momentRepo: MomentRepository,
    private val sleepChecker: CharacterSleepChecker,
) {
    suspend fun buildMomentContext(
        character: CharacterEntity,
        userNickname: String,
        scheduleSystemEnabled: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): MomentChatContext? {
        val sevenDaysAgo = nowMillis - SEVEN_DAYS_MS
        val charLines = mutableListOf<String>()
        val userLines = mutableListOf<String>()

        // 1. 角色发的帖（最近 7 天，max 3）+ 用户互动。当前不便时多取几条兜底被矛盾过滤掉的。
        val currentlyUnavailable = sleepChecker.isSleeping(character.uuid, scheduleSystemEnabled, nowMillis, zone)
        val charFetchLimit = if (currentlyUnavailable) MAX_LINES * 2 else MAX_LINES
        for (post in momentRepo.recentCharacterOwnPosts(character.uuid, sevenDaysAgo, charFetchLimit)) {
            if (charLines.size >= MAX_LINES) break
            // 矛盾过滤：当前不便 && 发帖时也不便 → 跳过。
            if (currentlyUnavailable &&
                sleepChecker.isSleeping(character.uuid, scheduleSystemEnabled, post.timestamp, zone)
            ) {
                continue
            }
            val likes = momentRepo.likesForPost(post.uuid)
            val comments = momentRepo.commentsForPost(post.uuid)
            val userLike = likes.firstOrNull { it.authorTypeRaw == MomentAuthorType.USER.raw }
            val userComments = comments.filter { it.authorTypeRaw == MomentAuthorType.USER.raw }
            charLines.add(formatCharacterOwnPostLine(post, userLike, userComments, userNickname, nowMillis, zone))
        }

        // 2. 用户发的帖中该角色有互动的（最近 7 天，max 3；多取 20 条从中筛）。
        for (post in momentRepo.recentUserPostsSince(sevenDaysAgo, USER_POST_FETCH_LIMIT)) {
            if (userLines.size >= MAX_LINES) break
            val charLike = momentRepo.likesForPost(post.uuid).firstOrNull {
                it.authorTypeRaw == MomentAuthorType.CHARACTER.raw && it.characterUuid == character.uuid
            }
            val charComments = momentRepo.commentsForPost(post.uuid).filter {
                it.authorTypeRaw == MomentAuthorType.CHARACTER.raw && it.characterUuid == character.uuid
            }
            // 角色没互动过 → 跳过（只展示有互动的）。
            if (charLike == null && charComments.isEmpty()) continue
            userLines.add(formatUserPostLine(post, charLike, charComments, userNickname, nowMillis, zone))
        }

        if (charLines.isEmpty() && userLines.isEmpty()) return null
        return MomentChatContext(
            characterPostsSummary = charLines.take(MAX_LINES).joinToString("\n"),
            userPostsSummary = userLines.take(MAX_LINES).joinToString("\n"),
        )
    }

    companion object {
        private const val SEVEN_DAYS_MS = 7L * 24 * 3600 * 1000
        private const val MAX_LINES = 3
        private const val USER_POST_FETCH_LIMIT = 20

        // 引号 “ ”（“/”）、箭头 ←（←）、全角分号 ；—— 与 iOS 字面一致。
        private const val QUOTE_OPEN = "“"
        private const val QUOTE_CLOSE = "”"
        private const val ARROW = " ← "
        private const val REACTION_SEP = "；"

        /**
         * 角色自己帖一行（1:1 iOS）：`时间 你发了动态：“内容”` + 可选 ` ← 用户在时间点赞了；用户在时间评论说：“内容”`。
         * 入参已由调用方筛好（[userLike]=用户的赞或 null，[userComments]=用户的评论）。
         */
        internal fun formatCharacterOwnPostLine(
            post: MomentPostEntity,
            userLike: MomentLikeEntity?,
            userComments: List<MomentCommentEntity>,
            userNickname: String,
            nowMillis: Long,
            zone: ZoneId,
        ): String {
            val timeDesc = DateFormatters.momentTimeDescription(post.timestamp, nowMillis, zone)
            var detail = "$timeDesc 你发了动态：$QUOTE_OPEN${post.content}$QUOTE_CLOSE"
            val reactions = mutableListOf<String>()
            if (userLike != null) {
                val likeTime = DateFormatters.momentTimeDescription(userLike.timestamp, nowMillis, zone)
                reactions.add("${userNickname}在${likeTime}点赞了")
            }
            for (uc in userComments) {
                val commentTime = DateFormatters.momentTimeDescription(uc.timestamp, nowMillis, zone)
                reactions.add("${userNickname}在${commentTime}评论说：$QUOTE_OPEN${uc.content}$QUOTE_CLOSE")
            }
            if (reactions.isNotEmpty()) detail += ARROW + reactions.joinToString(REACTION_SEP)
            return detail
        }

        /**
         * 用户帖一行（1:1 iOS）：`时间 用户名发了动态[（附带图片）]：“内容”` + 可选 ` ← 你在时间点赞了；你在时间评论说：“内容”`。
         * 入参已由调用方筛好（[charLike]=该角色的赞或 null，[charComments]=该角色的评论）。
         */
        internal fun formatUserPostLine(
            post: MomentPostEntity,
            charLike: MomentLikeEntity?,
            charComments: List<MomentCommentEntity>,
            userNickname: String,
            nowMillis: Long,
            zone: ZoneId,
        ): String {
            val timeDesc = DateFormatters.momentTimeDescription(post.timestamp, nowMillis, zone)
            var detail = "$timeDesc ${userNickname}发了动态"
            if (post.imagePaths.isNotEmpty()) detail += "（附带图片）"
            detail += "：$QUOTE_OPEN${post.content}$QUOTE_CLOSE"
            val reactions = mutableListOf<String>()
            if (charLike != null) {
                val likeTime = DateFormatters.momentTimeDescription(charLike.timestamp, nowMillis, zone)
                reactions.add("你在${likeTime}点赞了")
            }
            for (cc in charComments) {
                val commentTime = DateFormatters.momentTimeDescription(cc.timestamp, nowMillis, zone)
                reactions.add("你在${commentTime}评论说：$QUOTE_OPEN${cc.content}$QUOTE_CLOSE")
            }
            if (reactions.isNotEmpty()) detail += ARROW + reactions.joinToString(REACTION_SEP)
            return detail
        }
    }
}
