package com.situ.aichat.gift

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.DiaryDao
import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.local.dao.MomentDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.GiftRecordEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 礼物 → 朋友圈/日记联动的「查询式决策」服务（P9.2e）。1:1 移植 iOS `GiftMomentQueueService`
 * （同一文件含朋友圈版 + 日记版两套 API）+ `MomentGenerationActor+GiftBranch.resolveGiftBranch`。
 *
 * **查询式（不加表字段）**：不在 GiftRecord 上加「待晒」布尔，改为每次按时间戳比较：
 * - 朋友圈版：查该角色最近一条 triggerType=gift_received 的帖时间 T（含软删，防删后立刻再发）；候选 =
 *   48h 窗口内、晚于 T、且「珍贵(>200)或手作」的收礼（用户送+其他角色送都算）；两条礼物朋友圈间 24h 冷却。
 * - 日记版：用户视角（只收 sender=user→character），全局一条 T=「上一篇礼物日记时间」（含草稿），
 *   **无硬冷却**（每天 1 篇自然约束）。
 *
 * **两版关键差异（易混）**：朋友圈版按 receiverCharacterUUID 分组、有 24h 冷却、hint **绝不引用 DIY 正文**；
 * 日记版全局、无冷却、**允许引用 DIY 正文**（私人日记只有用户自己看）。两版 hint **均为英文**——1:1 iOS：
 * iOS 两套 hint 都硬编码英文（与周围 prompt 框架是否本地化无关；用户 2026-06-02 拍板严格照 iOS）。
 *
 * 代表礼物（写入 MomentPost / DiaryEntry.relatedGiftId）：价高 > 手作 > 最新。
 *
 * 纯决策函数（冷却 / 候选过滤 / 代表礼物 / hint 构造）放 [Companion] 且 `internal`，可不依赖 DB/设备单测。
 */
@Singleton
class GiftMomentQueueService @Inject constructor(
    private val giftDao: GiftDao,
    private val momentDao: MomentDao,
    private val diaryDao: DiaryDao,
    private val characterDao: CharacterDao,
) {

    /**
     * 一次礼物分支决策结果（朋友圈/日记共用；iOS 是两个同构 struct，安卓合一）。
     * `null` 表示不走礼物分支（冷却中 / 无候选 / 构造失败），调用方走原 auto_draft 路径。
     */
    data class GiftBranchResult(val promptHint: String, val representativeGiftId: String?)

    // ---- 朋友圈版（角色视角：我收到礼物）----

    /**
     * 判断该角色此刻是否走「礼物朋友圈分支」。顺序：冷却(24h) → 候选(48h 窗、晚于上次礼物帖、珍贵/手作)
     * → hint → 代表礼物。任一环节不过返回 null。1:1 iOS `resolveGiftBranch` + `pendingGiftCandidates`。
     */
    suspend fun resolveGiftBranch(character: CharacterEntity, now: Long): GiftBranchResult? {
        val lastMoment = momentDao.lastGiftPostTimestampForCharacter(character.uuid)
        if (!canPostGiftMoment(lastMoment, now)) return null

        val windowStart = now - CANDIDATE_WINDOW_MS
        val recent = giftDao.giftsReceivedByCharacterSince(character.uuid, windowStart)
        val candidates = filterFreshEligible(recent, lastMoment ?: Long.MIN_VALUE)
        if (candidates.isEmpty()) return null

        val hint = buildPromptHint(candidates) ?: return null
        return GiftBranchResult(hint, representativeGift(candidates)?.uuid)
    }

    // ---- 日记版（用户视角：我送出礼物）----

    /**
     * 判断此刻日记是否走「礼物分支」。**无硬冷却**；候选 = 48h 窗口内、晚于上一篇礼物日记 T、珍贵/手作的
     * 「用户送角色」礼物。1:1 iOS `resolveGiftBranchForDiary` + `pendingGiftCandidatesForDiary`。
     *
     * @param now 基准时间。补昨日日记时传「昨天 22:00」（让 48h 窗口连贯：前天晚 → 昨天晚，避免把今日礼物
     *   带进昨日日记），1:1 iOS backfillNow。
     */
    suspend fun resolveGiftBranchForDiary(now: Long): GiftBranchResult? {
        val windowStart = now - CANDIDATE_WINDOW_MS
        val recent = giftDao.userGiftsToCharactersSince(windowStart)
        val since = diaryDao.lastGiftDiaryTimestamp() ?: Long.MIN_VALUE
        val candidates = filterFreshEligible(recent, since)
        if (candidates.isEmpty()) return null

        // 反查每个 receiver 角色显示名，找不到兜底 "your friend"（保序去重）。
        val nameByUuid = HashMap<String, String>()
        for (uuid in candidates.mapTo(LinkedHashSet()) { it.receiverCharacterUUID }) {
            if (uuid.isEmpty()) continue
            characterDao.getByUuid(uuid)?.let { nameByUuid[uuid] = it.name }
        }

        val hint = buildDiaryPromptHint(candidates, nameByUuid) ?: return null
        return GiftBranchResult(hint, representativeGift(candidates)?.uuid)
    }

    companion object {
        /** 同一角色两条礼物朋友圈最小间隔 24h（朋友圈版冷却；日记版无冷却）。 */
        const val COOLDOWN_HOURS = 24L

        /** 候选窗口：只考虑最近 48h 内收到的礼物（比冷却略大，给「冷却刚解除就发」留缓冲）。 */
        const val CANDIDATE_WINDOW_HOURS = 48L

        /** 「珍贵」档门槛（金币），**严格大于**；与 GiftSendService growthLog 阈值一致。 */
        const val PRECIOUS_COST_THRESHOLD = 200

        private const val HOUR_MS = 3_600_000L
        internal const val COOLDOWN_MS = COOLDOWN_HOURS * HOUR_MS
        internal const val CANDIDATE_WINDOW_MS = CANDIDATE_WINDOW_HOURS * HOUR_MS

        /** 朋友圈冷却：从没发过(null) → 可发；否则距上次 ≥24h 才可发。1:1 iOS `canPostGiftMoment`。 */
        internal fun canPostGiftMoment(lastGiftMomentTs: Long?, now: Long): Boolean {
            if (lastGiftMomentTs == null) return true
            return now - lastGiftMomentTs >= COOLDOWN_MS
        }

        /** 候选过滤（纯）：窗口内记录里，**晚于**上次礼物帖/日记 T、且「珍贵或手作」的。1:1 iOS freshOnly + isEligible。 */
        internal fun filterFreshEligible(windowRecords: List<GiftRecordEntity>, since: Long): List<GiftRecordEntity> =
            windowRecords.filter { it.timestamp > since && isEligibleForMoment(it) }

        /** 值得晒（方案 B：珍贵>200 或 手作 都晒）。珍贵为严格大于。 */
        internal fun isEligibleForMoment(record: GiftRecordEntity): Boolean =
            record.pricePaid > PRECIOUS_COST_THRESHOLD || isHandmadeLike(record)

        /** 手作判定：DIY 或 预置手作（catalog.isHandmade）。DIY 在 catalog 查不到（find 返回 null）。 */
        internal fun isHandmadeLike(record: GiftRecordEntity): Boolean =
            record.isDIY || GiftCatalog.find(record.giftItemId)?.isHandmade == true

        /** 代表礼物：价高 > 手作 > 最新。1:1 iOS `representativeGift`（max(by:) 同序）。 */
        internal fun representativeGift(records: List<GiftRecordEntity>): GiftRecordEntity? =
            records.maxWithOrNull(
                compareBy<GiftRecordEntity> { it.pricePaid }
                    .thenBy { isHandmadeLike(it) } // false < true：手作更「大」
                    .thenBy { it.timestamp }, // 时间戳更大 = 最新更「大」
            )

        /** 展示名：DIY → diyTitle（空 → "a handmade gift"）；预置 → catalog.name（下架 → "a thoughtful gift"）。 */
        internal fun displayName(record: GiftRecordEntity): String {
            if (record.isDIY) {
                val title = record.diyTitle.trim()
                return title.ifEmpty { "a handmade gift" }
            }
            return GiftCatalog.find(record.giftItemId)?.name ?: "a thoughtful gift"
        }

        /**
         * 朋友圈灵感段（英文，1:1 iOS `buildPromptHint`）。多件合并、具体提名字；DIY 只提标题、**绝不引用
         * 正文**（保护用户对单个角色的私密话）。空候选 → null。
         */
        internal fun buildPromptHint(records: List<GiftRecordEntity>): String? {
            if (records.isEmpty()) return null
            val giftLines = records.map { r ->
                val tag = when {
                    r.isDIY -> " (a handmade card from the user)"
                    GiftCatalog.find(r.giftItemId)?.isHandmade == true -> " (a handmade card)"
                    r.pricePaid > PRECIOUS_COST_THRESHOLD -> " (a precious gift)"
                    else -> ""
                }
                "- ${displayName(r)}$tag"
            }
            val parts = mutableListOf<String>()
            parts.add(
                if (records.size == 1) "Today your friend gave you a gift:"
                else "Recently your friend gave you these gifts:",
            )
            parts.addAll(giftLines)
            parts.add("")
            parts.add(
                "You genuinely want to share a social post about it. Write about how it made you feel — the specific detail that touched you, not a formulaic thank-you. Mention the gift(s) by name if it feels natural. For handmade items, you may mention the title but NEVER quote or paraphrase the private message/content the user wrote inside — that's between you two. Keep the post authentic and personal, not performative.",
            )
            return parts.joinToString("\n")
        }

        /**
         * 日记灵感段（英文，1:1 iOS `buildDiaryPromptHint`）。**允许引用 DIY 正文**（私人日记只有用户自己看）；
         * 散布全文、不独立成段；省略价格。空候选 → null。
         *
         * @param nameByUuid receiverCharacterUUID → 角色显示名；缺失兜底 "your friend"。
         */
        internal fun buildDiaryPromptHint(
            records: List<GiftRecordEntity>,
            nameByUuid: Map<String, String>,
        ): String? {
            if (records.isEmpty()) return null
            val giftLines = records.map { r ->
                val receiverName = nameByUuid[r.receiverCharacterUUID] ?: "your friend"
                val tag = when {
                    r.isDIY -> {
                        val content = r.diyContent.trim()
                        if (content.isEmpty()) {
                            " (handmade, given to $receiverName)"
                        } else {
                            " (handmade, given to $receiverName; you wrote inside: “$content”)"
                        }
                    }
                    GiftCatalog.find(r.giftItemId)?.isHandmade == true -> " (handmade, given to $receiverName)"
                    r.pricePaid > PRECIOUS_COST_THRESHOLD -> " (a precious gift, given to $receiverName)"
                    else -> " (given to $receiverName)"
                }
                "- ${displayName(r)}$tag"
            }
            val parts = mutableListOf<String>()
            parts.add(
                if (records.size == 1) "Recently you gave a meaningful gift to your AI companion:"
                else "Recently you gave these meaningful gifts to your AI companions:",
            )
            parts.addAll(giftLines)
            parts.add("")
            parts.add(
                "These gift moments touched you. Weave the feelings NATURALLY into the diary's flow — do NOT write a dedicated paragraph just about gifts, and do NOT list them mechanically. Instead: mention choosing it, how their reaction lingered in your mind, a small detail you noticed, a memory it sparked, or a fragment of the handmade words you wrote that keeps coming back. You MAY quote the handmade content above directly if it fits — this is your private diary, only you read it. Omit prices entirely. Keep first-person, intimate, real — gifts are one thread among today's events, not the headline.",
            )
            return parts.joinToString("\n")
        }
    }
}
