package com.situ.aichat.ui.pet

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.ui.graphics.vector.ImageVector
import com.situ.aichat.pet.PetItem
import com.situ.aichat.pet.PetItemCategory
import com.situ.aichat.pet.PetStatBoosts

/**
 * 宠物用品 UI 共享助手（商店卡 / 确认 sheet / 背包行复用，避免重复）。
 * iOS 用 SF Symbol（fallbackSymbol）；安卓 Material 3 原生重写——按 itemId 映射 Material 图标（同 GiftSymbolMapping 思路）。
 */

/** 物品 → Material 图标（按 id 精选，目录新增走 category 兜底）。 */
fun petItemIcon(item: PetItem): ImageVector = when (item.id) {
    "pet_food_biscuit" -> Icons.Filled.Cookie
    "pet_food_carrot" -> Icons.Filled.Eco
    "pet_food_seeds" -> Icons.Filled.Eco
    "pet_food_cat_can" -> Icons.Filled.Restaurant
    "pet_food_premium_can" -> Icons.Filled.Stars
    "pet_food_birthday_cake" -> Icons.Filled.Cake
    "pet_costume_bowtie" -> Icons.Filled.Checkroom
    "pet_costume_scarf" -> Icons.Filled.Checkroom
    "pet_costume_crown" -> Icons.Filled.WorkspacePremium
    "pet_costume_wings" -> Icons.Filled.AutoAwesome
    else -> if (item.category == PetItemCategory.FOOD) Icons.Filled.Restaurant else Icons.Filled.Checkroom
}

/**
 * statBoosts → 短文案分段「饥饿 -15」「心情 +3」（1:1 iOS：hunger 负号原样、正值带 +）。null/全 0 → 空列表。
 * 商店确认 sheet 与背包行共用（iOS 两处逻辑等价，合一）。
 */
fun petBoostParts(boosts: PetStatBoosts?): List<String> {
    if (boosts == null) return emptyList()
    val parts = ArrayList<String>()
    boosts.hunger?.takeIf { it != 0 }?.let { parts.add("饥饿 ${signed(it)}") }
    boosts.cleanliness?.takeIf { it != 0 }?.let { parts.add("清洁 ${signed(it)}") }
    boosts.happiness?.takeIf { it != 0 }?.let { parts.add("心情 ${signed(it)}") }
    boosts.health?.takeIf { it != 0 }?.let { parts.add("健康 ${signed(it)}") }
    return parts
}

private fun signed(v: Int): String = if (v > 0) "+$v" else "$v"
