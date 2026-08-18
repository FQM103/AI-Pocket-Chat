package com.situ.aichat.pet

import com.situ.aichat.data.local.dao.CurrencyDao
import com.situ.aichat.data.local.dao.MessageDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 计算「最近 24h 用户给宠物买的东西」物品名列表，供 `PromptBuilderPet.buildPetInventoryLines` 注入系统提示词
 * （1:1 iOS `PromptBuilder+Pet.recentPetShopItems`）。
 *
 * Android 适配：iOS 在同步 prompt 构建里直接 FetchDescriptor 查 SwiftData；安卓 PromptBuilder 是纯同步，DB 查询要
 * 异步预算，故抽成 @Singleton 服务由 ChatViewModel/BusyReplyService 在装配 BuildContext 前查好、传入（同
 * MomentChatContextService 模式）。
 *
 * 全局（非按角色）：iOS 的 .petShop 流水 + isPetMessage 查询均不按角色过滤——用户只有一个钱包，购买是全局的；
 * buildPetInventoryLines 再把它们归到「当前角色的宠物」名下（1:1 iOS 行为，多宠物时的归属近似忠实照搬）。
 *
 * 去重：剔除已经在宠物独白消息（isPetMessage）正文里出现过的物品名（这些已进 LLM 历史上下文，再列一遍会让 LLM
 * 反复提同一件东西）。节流后没产生独白的物品仍保留，确保至少从 [宠物状态] 传给 LLM。
 */
@Singleton
class PetInventoryPromptService @Inject constructor(
    private val currencyDao: CurrencyDao,
    private val messageDao: MessageDao,
) {

    /** 最近 24h 购买物品名（已去重 isPetMessage 提过的），最多 10 条、降序。无 → 空列表。 */
    suspend fun recentPetShopItemNames(now: Long = System.currentTimeMillis()): List<String> {
        val oneDayAgo = now - ONE_DAY_MS
        val txs = currencyDao.recentPetShopTransactions(oneDayAgo, PURCHASE_FETCH_LIMIT)
        val itemNames = txs.mapNotNull { tx -> tx.relatedEntityId?.let { PetItemCatalog.find(it)?.name } }
        if (itemNames.isEmpty()) return emptyList()
        val mentioned = messageDao.recentPetMessageContents(oneDayAgo, PET_MESSAGE_FETCH_LIMIT)
        return dedupeByMention(itemNames, mentioned)
    }

    companion object {
        private const val ONE_DAY_MS: Long = 24L * 3600 * 1000
        private const val PURCHASE_FETCH_LIMIT = 10
        private const val PET_MESSAGE_FETCH_LIMIT = 30

        /**
         * 剔除已在宠物独白正文里出现过的物品名（1:1 iOS：`!mentioned.contains { $0.contains(itemName) }`）。
         * 无独白 → 原样返回（含可能的重复名，iOS 不对名字本身去重）。纯函数便于单测。
         */
        internal fun dedupeByMention(itemNames: List<String>, petMessageContents: List<String>): List<String> {
            if (petMessageContents.isEmpty()) return itemNames
            return itemNames.filter { name -> petMessageContents.none { it.contains(name) } }
        }
    }
}
