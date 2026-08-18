package com.situ.aichat.gift

import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.local.entity.GiftRecordEntity

/**
 * 「角色收到的礼物」列表数据源（1:1 iOS `GiftMemoryService`，2026-04-20 改版）。服务于档案页"关系账户"卡片：
 * 所有收到的用户礼物按 timestamp **降序**全列表（UI 固定高度滚动看历史，总件数从 list.size 派生）。
 */
object GiftMemoryService {

    /** 角色收到的所有用户礼物（receiver==char && sender=='user'），timestamp DESC，空时返回 []。 */
    suspend fun allReceivedGifts(characterUuid: String, dao: GiftDao): List<GiftRecordEntity> =
        runCatching { dao.userGiftsToCharacterDesc(characterUuid) }.getOrDefault(emptyList())
}
