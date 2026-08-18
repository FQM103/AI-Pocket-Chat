package com.situ.aichat.moments

import android.util.Log
import com.situ.aichat.data.repository.MomentRepository
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 朋友圈前台恢复补偿（M06 7.2.5）。1:1 移植 iOS `MomentRecoveryService` + `MomentGenerationActor+Recovery`。
 * **国产 ROM 杀后台韧性的核心**（spec §4 头号风险）：[MomentInteractionService] 的延迟互动循环靠协程在前台
 * 跑，被 HyperOS 杀后丢失；本服务在回前台 + 前台每 4 分钟扫一遍「丢失的 AI 互动」并补发——这是「延迟产物
 * 必须能被恢复重建」这一关键不变量的兑现。
 *
 * 三场景（合计上限 [MAX_RECOVERY_COUNT]=5，每项前随机延迟 10~20s 让产物陆续出现而非一齐刷出）：
 * - **A** 用户评论缺 AI 回复 → `generateReplyToComment`
 * - **B** 用户帖缺 AI 互动（评论/点赞）→ `autoInteractWithPost`
 * - **C** AI 帖缺「其他角色」互动 → `autoInteractWithPost`
 *
 * 每项都查 [MomentDelayedTaskRegistry] 去重（在途延迟任务不重复补）；B/C 只看 >5 分钟前的帖（`fiveMinAgo`
 * 防与刚发帖的延迟互动撞车）。[running] 重入锁（=iOS isRunning）保证回前台一次性与 4 分钟循环不并发。
 */
@Singleton
class MomentRecoveryService @Inject constructor(
    private val momentRepo: MomentRepository,
    private val interactionService: MomentInteractionService,
) {
    /** 重入锁（=iOS `MomentRecoveryService.isRunning`）：并发触发时只跑一遍。 */
    private val running = AtomicBoolean(false)

    /** 扫描并补发丢失的 AI 互动。已在跑 → 跳过。 */
    suspend fun recoverIfNeeded(nowMillis: Long = System.currentTimeMillis()) {
        if (!running.compareAndSet(false, true)) return
        try {
            var recovered = 0
            recovered += recoverOrphanedUserComments(MAX_RECOVERY_COUNT - recovered, nowMillis)
            if (recovered < MAX_RECOVERY_COUNT) {
                recovered += recoverOrphanedUserPosts(MAX_RECOVERY_COUNT - recovered, nowMillis)
            }
            if (recovered < MAX_RECOVERY_COUNT) {
                recovered += recoverOrphanedAIPosts(MAX_RECOVERY_COUNT - recovered, nowMillis)
            }
            if (recovered > 0) Log.d(TAG, "朋友圈恢复完成：补发 $recovered 项")
        } finally {
            running.set(false)
        }
    }

    // 场景 A：用户评论缺 AI 回复 —— 最近 24h 用户评论中没收到 AI 回复的，逐条补发。
    private suspend fun recoverOrphanedUserComments(remaining: Int, nowMillis: Long): Int {
        if (remaining <= 0) return 0
        val oneDayAgo = nowMillis - DAY_MS
        val comments = momentRepo.recentUserComments(oneDayAgo, remaining * 2)
        var count = 0
        for (comment in comments) {
            if (count >= remaining) break
            val postUuid = comment.postUuid ?: continue
            val post = momentRepo.getPost(postUuid) ?: continue
            if (post.isSoftDeleted) continue
            if (momentRepo.hasCharacterReply(comment.uuid)) continue
            if (MomentDelayedTaskRegistry.containsTask(postUuid, MomentDelayedTaskRegistry.Purpose.Reply(comment.uuid))) continue
            delay(perItemDelayMs())
            interactionService.generateReplyToComment(comment.uuid, postUuid)
            count++
        }
        return count
    }

    // 场景 B：用户帖缺 AI 互动 —— 最近 24h 且 >5 分钟前的用户帖、无任何 AI 评论的，补发互动。
    private suspend fun recoverOrphanedUserPosts(remaining: Int, nowMillis: Long): Int {
        if (remaining <= 0) return 0
        val oneDayAgo = nowMillis - DAY_MS
        val fiveMinAgo = nowMillis - FIVE_MIN_MS
        val posts = momentRepo.recentUserPostsInWindow(oneDayAgo, fiveMinAgo, remaining * 2)
        var count = 0
        for (post in posts) {
            if (count >= remaining) break
            if (momentRepo.aiCommentCount(post.uuid) > 0) continue
            if (MomentDelayedTaskRegistry.containsTask(post.uuid, MomentDelayedTaskRegistry.Purpose.AutoInteraction)) continue
            delay(perItemDelayMs())
            interactionService.autoInteractWithPost(post.uuid)
            count++
        }
        return count
    }

    // 场景 C：AI 帖缺「其他角色」互动 —— 同 B 窗口，扫 AI 帖、无非作者角色的评论/点赞的，补发互动。
    private suspend fun recoverOrphanedAIPosts(remaining: Int, nowMillis: Long): Int {
        if (remaining <= 0) return 0
        val oneDayAgo = nowMillis - DAY_MS
        val fiveMinAgo = nowMillis - FIVE_MIN_MS
        val posts = momentRepo.recentCharacterPostsInWindow(oneDayAgo, fiveMinAgo, remaining * 2)
        var count = 0
        for (post in posts) {
            if (count >= remaining) break
            val author = post.characterUuid ?: continue
            if (momentRepo.hasOtherCharacterComment(post.uuid, author) || momentRepo.hasOtherCharacterLike(post.uuid, author)) continue
            if (MomentDelayedTaskRegistry.containsTask(post.uuid, MomentDelayedTaskRegistry.Purpose.AutoInteraction)) continue
            delay(perItemDelayMs())
            interactionService.autoInteractWithPost(post.uuid)
            count++
        }
        return count
    }

    /** 每项恢复前的随机延迟（毫秒），10~20s（iOS `perItemDelayRange = 10...20`）。 */
    private fun perItemDelayMs(): Long = Random.nextLong(PER_ITEM_DELAY_MIN_MS, PER_ITEM_DELAY_MAX_MS + 1)

    private companion object {
        const val TAG = "MomentRecovery"

        /** 单次恢复上限（场景 A+B+C 合计，iOS maxRecoveryCount=5）。 */
        const val MAX_RECOVERY_COUNT = 5

        /** 每项恢复前随机延迟 10~20s（iOS perItemDelayRange）。 */
        const val PER_ITEM_DELAY_MIN_MS = 10_000L
        const val PER_ITEM_DELAY_MAX_MS = 20_000L

        const val DAY_MS = 24L * 3600 * 1000

        /** B/C 窗口下界距 now 5 分钟，防与刚发帖的在途延迟互动撞车（iOS fiveMinutesAgo）。 */
        const val FIVE_MIN_MS = 5L * 60 * 1000
    }
}
