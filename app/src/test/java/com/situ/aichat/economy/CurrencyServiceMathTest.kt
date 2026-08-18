package com.situ.aichat.economy

import com.situ.aichat.data.model.CurrencyTransactionCategory
import com.situ.aichat.data.model.CurrencyTransactionKind
import com.situ.aichat.data.model.WalletOwnerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * CurrencyService 纯函数守卫 + 货币枚举单测（断言反推 iOS）。余额增减本体走 Room（行为验证留真机/独立复核），
 * 这里只锁住「扣款 guard / 钳位 / category rawValue」这些移植易错点（boundary / 符号 / 字面）。
 */
class CurrencyServiceMathTest {

    @Test
    fun spend_sufficient_deducts() {
        assertEquals(80, spendableBalance(balance = 100, amount = 20))
    }

    @Test
    fun spend_exact_balance_to_zero() {
        // 边界：余额恰等于金额 → 扣到 0（iOS `balance >= amount` 含等号）
        assertEquals(0, spendableBalance(balance = 20, amount = 20))
    }

    @Test
    fun spend_insufficient_returns_null() {
        assertNull(spendableBalance(balance = 10, amount = 20))
    }

    @Test
    fun spend_zero_amount_returns_null() {
        // iOS `guard amount > 0`：0 元也算非法，不扣
        assertNull(spendableBalance(balance = 100, amount = 0))
    }

    @Test
    fun spend_negative_amount_returns_null() {
        assertNull(spendableBalance(balance = 100, amount = -5))
    }

    @Test
    fun clamp_negative_to_zero() {
        assertEquals(0, clampBalance(-10))
    }

    @Test
    fun clamp_nonnegative_unchanged() {
        assertEquals(0, clampBalance(0))
        assertEquals(50, clampBalance(50))
    }

    @Test
    fun category_rawValues_match_ios() {
        assertEquals("petWalk", CurrencyTransactionCategory.PET_WALK.raw)
        assertEquals("petSouvenirSale", CurrencyTransactionCategory.PET_SOUVENIR_SALE.raw)
        assertEquals("petShop", CurrencyTransactionCategory.PET_SHOP.raw)
        assertEquals("redPacket", CurrencyTransactionCategory.RED_PACKET.raw)
        assertEquals("unexpectedExpense", CurrencyTransactionCategory.UNEXPECTED_EXPENSE.raw)
        assertEquals("redeemCode", CurrencyTransactionCategory.REDEEM_CODE.raw)
        // 安卓独立后新增（无 iOS 对应·世界系统 W7 旅行购票）——总数 = iOS 12 类 + 1。
        assertEquals("worldTravel", CurrencyTransactionCategory.WORLD_TRAVEL.raw)
        assertEquals(13, CurrencyTransactionCategory.entries.size)
    }

    @Test
    fun category_displayName_match_ios() {
        // SPEC 写「宠物用品」，iOS 代码实为「宠物商店」——信代码
        assertEquals("宠物商店", CurrencyTransactionCategory.PET_SHOP.displayName)
        assertEquals("红包", CurrencyTransactionCategory.RED_PACKET.displayName)
        assertEquals("工资", CurrencyTransactionCategory.SALARY.displayName)
    }

    @Test
    fun enum_fromRaw_fallbacks() {
        assertEquals(CurrencyTransactionCategory.OTHER, CurrencyTransactionCategory.fromRaw("nope"))
        assertEquals(WalletOwnerType.USER, WalletOwnerType.fromRaw("nope"))
        assertEquals(CurrencyTransactionKind.EARN, CurrencyTransactionKind.fromRaw("nope"))
        assertEquals(WalletOwnerType.CHARACTER, WalletOwnerType.fromRaw("character"))
        assertEquals(CurrencyTransactionKind.SPEND, CurrencyTransactionKind.fromRaw("spend"))
    }
}
