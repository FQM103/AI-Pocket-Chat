package com.situ.aichat.pet

import com.situ.aichat.data.local.dao.DiaryDao
import com.situ.aichat.data.local.dao.MomentDao
import com.situ.aichat.data.local.dao.PetDao
import com.situ.aichat.data.local.entity.CharacterEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「用户给宠物买了贵价用品后，该角色该不该发朋友圈 / 该不该写宠物商店日记」的查询式决策服务（P9.3c）。
 * 1:1 移植 iOS `Services/PetShopMomentQueueService.swift`（同文件含朋友圈版 + 日记版两套 API），仿安卓已建
 * [com.situ.aichat.gift.GiftMomentQueueService]（9.2e）同模式。
 *
 * **查询式**（不 push，由 MomentGenerationService / DiaryGenerationCoordinator 主动调用）。数据源 =
 * `pet.metadata.recentExpensivePurchases`（[PetShopService] 在 ≥300 金币购买时写入）。
 * - **朋友圈版（per 角色）**：查该角色最近一条 triggerType=pet_shop_purchase 的帖时间 T（含软删，防删后即发）；
 *   候选 = 48h 窗内、晚于 T 的贵价购买；两条间 24h 冷却。hint **英文**（角色视角「看到用户给宝贝买东西的感受」）。
 * - **日记版（全局跨角色，用户视角）**：全局一条 T=上一篇宠物商店日记；候选 = 所有宠物 48h 窗内、晚于 T 的购买；
 *   **无硬冷却**（每天 1 篇自然约束）。hint **中文**（用户私人视角）。
 *
 * **两版 hint 语言差异（与 gift 不同，易混）**：gift 两版 hint 都英文；**宠物商店朋友圈英文、日记中文**——1:1 iOS
 * （iOS PetShopMomentQueueService `buildMomentPromptHint` 英文 / `buildDiaryPromptHint` 中文，逐字照搬）。
 *
 * 代表物品（iOS 计算但朋友圈/日记落库均不存入 relatedGiftId——只用 promptHint；保留以 1:1 iOS struct + 单测覆盖）：
 * 价格最高，并列取最新。纯决策（冷却/候选/代表/hint）放 [Companion] 且 internal，可不依赖 DB/设备单测。
 */
@Singleton
class PetShopMomentQueueService @Inject constructor(
    private val petDao: PetDao,
    private val momentDao: MomentDao,
    private val diaryDao: DiaryDao,
) {

    /**
     * 一次宠物商店分支决策结果（朋友圈/日记共用；iOS 是两个同构 struct，安卓合一）。
     * `null` 表示不走宠物商店分支（无宠物 / 冷却中 / 无候选 / 构造失败），调用方走原 auto_draft 路径。
     * [representativeItemId] = 价高>最新的代表物品 id（朋友圈/日记落库都不存它，仅 hint 入 prompt，1:1 iOS）。
     */
    data class PetShopBranchResult(val promptHint: String, val representativeItemId: String)

    // ---- 朋友圈版（角色视角：用户给我们的宝贝买了贵价用品）----

    /**
     * 该角色此刻是否走「宠物商店朋友圈分支」。顺序：冷却(24h) → 候选(48h 窗、晚于上次宠物商店帖) → hint → 代表物品。
     * 任一环节不过返回 null。1:1 iOS `resolveMomentBranch`。
     */
    suspend fun resolveMomentBranch(character: CharacterEntity, now: Long): PetShopBranchResult? {
        val pet = petDao.getForCharacter(character.uuid) ?: return null
        val lastMoment = momentDao.lastPetShopPostTimestampForCharacter(character.uuid)
        if (!canPostPetShopMoment(lastMoment, now)) return null

        val candidates = filterFreshInWindow(pet.metadata.recentExpensivePurchases, now, lastMoment ?: Long.MIN_VALUE)
        if (candidates.isEmpty()) return null

        val hint = buildMomentPromptHint(candidates, pet.name) ?: return null
        return PetShopBranchResult(hint, representativeItemId(candidates))
    }

    // ---- 日记版（用户视角：我给宠物买了贵价用品）----

    /**
     * 此刻日记是否走「宠物商店分支」。**无硬冷却**；候选 = 所有宠物 48h 窗内、晚于上一篇宠物商店日记 T 的贵价购买。
     * 1:1 iOS `resolveDiaryBranch` + `pendingPurchasesForDiary`（跨所有角色，因日记是用户视角）。
     *
     * @param now 基准时间。补昨日日记时传「昨天 22:00」（48h 窗连贯，1:1 iOS backfillNow）。
     */
    suspend fun resolveDiaryBranch(now: Long): PetShopBranchResult? {
        val since = diaryDao.lastPetShopDiaryTimestamp() ?: Long.MIN_VALUE
        // 全局聚合所有宠物的贵价购买，并记录每条记录的所属宠物名（iOS owningPet?.name ?? "宠物"；首个匹配为准）。
        val allRecords = ArrayList<PetExpensivePurchaseRecord>()
        val nameByRecord = HashMap<PetExpensivePurchaseRecord, String>()
        for (pet in petDao.getAll()) {
            for (rec in pet.metadata.recentExpensivePurchases) {
                allRecords.add(rec)
                nameByRecord.putIfAbsent(rec, pet.name)
            }
        }
        val candidates = filterFreshInWindow(allRecords, now, since)
        if (candidates.isEmpty()) return null

        val hint = buildDiaryPromptHint(candidates, nameByRecord) ?: return null
        return PetShopBranchResult(hint, representativeItemId(candidates))
    }

    companion object {
        /** 触发朋友圈/日记的金额阈值（单一真相源，= [PetItemCatalog.EXPENSIVE_PURCHASE_THRESHOLD] = 300）。 */
        const val PRICE_THRESHOLD: Int = PetItemCatalog.EXPENSIVE_PURCHASE_THRESHOLD

        /** 同一角色两条宠物商店朋友圈最小间隔 24h（朋友圈版冷却；日记版无冷却）。 */
        const val COOLDOWN_HOURS: Long = 24

        /** 候选窗口：只考虑最近 48h 内购买的物品（超出视为过时）。 */
        const val CANDIDATE_WINDOW_HOURS: Long = 48

        private const val HOUR_MS = 3_600_000L
        internal const val COOLDOWN_MS = COOLDOWN_HOURS * HOUR_MS
        internal const val CANDIDATE_WINDOW_MS = CANDIDATE_WINDOW_HOURS * HOUR_MS

        /** 朋友圈冷却：从没发过(null) → 可发；否则距上次 ≥24h 才可发。1:1 iOS `canPostPetShopMoment`。 */
        internal fun canPostPetShopMoment(lastTs: Long?, now: Long): Boolean {
            if (lastTs == null) return true
            return now - lastTs >= COOLDOWN_MS
        }

        /**
         * 候选过滤（纯）：48h 窗内（`purchasedAt >= now-48h`）且**晚于**上次帖/日记 T（`purchasedAt > since`），按
         * purchasedAt 升序。1:1 iOS `pendingPurchasesForMoment`/`pendingPurchasesForDiary` 的 filter+sorted。
         */
        internal fun filterFreshInWindow(
            records: List<PetExpensivePurchaseRecord>,
            now: Long,
            since: Long,
        ): List<PetExpensivePurchaseRecord> {
            val cutoff = now - CANDIDATE_WINDOW_MS
            return records.filter { it.purchasedAt >= cutoff && it.purchasedAt > since }
                .sortedBy { it.purchasedAt }
        }

        /**
         * 代表物品 id：价格最高，并列取最新（1:1 iOS `representativeItemId` 的 max-by 同序）。目录查不到全部 →
         * 兜底取候选最后一条（最新）的 id；空 → ""。
         */
        internal fun representativeItemId(records: List<PetExpensivePurchaseRecord>): String {
            val withPrice = records.mapNotNull { rec -> PetItemCatalog.find(rec.itemId)?.let { rec to it.price } }
            if (withPrice.isEmpty()) return records.lastOrNull()?.itemId ?: ""
            val top = withPrice.maxWithOrNull(
                compareBy<Pair<PetExpensivePurchaseRecord, Int>> { it.second } // 价格
                    .thenBy { it.first.purchasedAt }, // 并列 → 时间戳更大=最新更「大」
            )
            return top?.first?.itemId ?: ""
        }

        /**
         * 朋友圈灵感段（**英文**，角色视角「看到用户给宝贝买东西的感受」）。1:1 iOS `buildMomentPromptHint`，逐字照搬。
         * 空候选（目录全查不到）→ null。
         */
        internal fun buildMomentPromptHint(
            records: List<PetExpensivePurchaseRecord>,
            petName: String,
        ): String? {
            val name = petName.ifEmpty { "the pet" } // iOS character.pet?.name ?? "the pet"（无宠物兜底；空名近似兜底）
            val items = records.mapNotNull { rec -> PetItemCatalog.find(rec.itemId)?.let { it.name to it.price } }
            if (items.isEmpty()) return null
            val itemList = items.joinToString(", ") { (n, price) -> "$n($price coins)" }
            return listOf(
                "[Pet Shop Moment Inspiration]",
                "User just bought $name some premium items: $itemList.",
                "You feel touched and proud — your shared pet is being well-cared for.",
                "Write a short moment post (~30-80 chars in Chinese) that:",
                "- shows you noticed the user's care for $name",
                "- shares a sweet observation of $name wearing or using the new item",
                "- conveys gratitude subtly — never thank the user explicitly or sound cheesy",
                "Tone: warm, slightly sentimental, like a real person quietly noticing a small kindness.",
            ).joinToString("\n")
        }

        /**
         * 日记灵感段（**中文**，用户私人视角）。1:1 iOS `buildDiaryPromptHint`，逐字照搬（标点照 iOS：ASCII 冒号/逗号/
         * 分号/括号 + 中文句号）。空候选 → null。
         *
         * @param nameByRecord 记录 → 所属宠物名；缺失兜底「宠物」（1:1 iOS owningPet?.name ?? "宠物"）。
         */
        internal fun buildDiaryPromptHint(
            records: List<PetExpensivePurchaseRecord>,
            nameByRecord: Map<PetExpensivePurchaseRecord, String>,
        ): String? {
            data class Resolved(val petName: String, val itemName: String, val price: Int)
            val resolved = records.mapNotNull { rec ->
                PetItemCatalog.find(rec.itemId)?.let { Resolved(nameByRecord[rec] ?: "宠物", it.name, it.price) }
            }
            if (resolved.isEmpty()) return null
            val summary = resolved.joinToString(";") { "给${it.petName}买了${it.itemName}(${it.price}金币)" }
            return listOf(
                "[Pet Shop Diary Inspiration]",
                "今天给宠物买了贵价用品:${summary}。",
                "在日记里自然提一下,体现:",
                "- 给宠物花钱时的小满足感或偶尔纠结",
                "- 看到宠物用上新东西的小确幸",
                "- 1-2 句即可,不要单独成段,要融入今天的整体心情",
            ).joinToString("\n")
        }
    }
}
