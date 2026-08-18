package com.situ.aichat.moments

import com.situ.aichat.data.local.entity.MomentPostEntity

/**
 * 「X 发了新动态」通知的**纯函数**决策（13.7e）：给定本次周期 worker 这一轮新建的帖子 + 今天已推过的角色集合，
 * 决定推 0 / 1 / 合并条。无副作用（节流台账的读写、文案/大图/深链、实际发通知都在 [MomentNewPostNotifier]）。
 *
 * 规则（用户拍板「保守节流」）：
 * - 每角色每天≤1：排除 [alreadyNotifiedCharIds] 里的角色（已在台账里今天推过的）。
 * - 同一角色一轮多帖只取第一条（`distinctBy characterUuid`）——实际上一轮每角色至多发一帖（各自 4h 冷却/日上限），此为防御。
 * - 0 个待推角色 → [None]；1 个 → [Single]（深链该帖）；≥2 个 → [Merged]（合并「N 位好友发了新动态」，深链朋友圈 feed）。
 */
object MomentNewPostNotificationPlanner {

    sealed interface Plan {
        /** 无可推（本轮没新帖 / 全被当天节流挡下）。 */
        data object None : Plan

        /** 单个角色发了新动态 → 推一条带该帖深链的通知。 */
        data class Single(val post: MomentPostEntity) : Plan

        /** 多个角色同一轮发了新动态 → 合并一条「N 位好友发了新动态」，深链 feed。[posts] 每角色一条，size≥2。 */
        data class Merged(val posts: List<MomentPostEntity>) : Plan
    }

    /**
     * @param createdPosts 本轮周期 worker 新建并落库的帖（顺序=角色遍历顺序）。
     * @param alreadyNotifiedCharIds 今天已就新动态推过通知的角色 uuid 集合（来自 [MomentNewPostNotifiedStore]）。
     */
    fun plan(createdPosts: List<MomentPostEntity>, alreadyNotifiedCharIds: Set<String>): Plan {
        val eligible = createdPosts
            .filter { it.characterUuid != null && it.characterUuid !in alreadyNotifiedCharIds }
            .distinctBy { it.characterUuid }
        return when {
            eligible.isEmpty() -> Plan.None
            eligible.size == 1 -> Plan.Single(eligible.first())
            else -> Plan.Merged(eligible)
        }
    }
}
