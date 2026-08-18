package com.situ.aichat.world.bulletin

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [WorldLlmBudget] T1-3（Robolectric 真库·图纸 §7·E15）：cap 边界 + cap≤0 恒 false + 类目/日独立 + 30 天清旧。
 * 断言从图纸 §3.3 事务化读增语义独立反推。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldLlmBudgetTest {

    private lateinit var db: AppDatabase
    private lateinit var budget: WorldLlmBudget

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        budget = WorldLlmBudget(db)
    }

    @After
    fun tearDown() = db.close()

    private fun count(epochDay: Long, category: String = "bulletin") = runBlocking { db.worldBulletinDao().spendCount(epochDay, category) }

    @Test
    fun `E15 cap边界_到cap返false`() = runBlocking {
        assertTrue(budget.tryConsume("bulletin", 100, 3))
        assertTrue(budget.tryConsume("bulletin", 100, 3))
        assertTrue(budget.tryConsume("bulletin", 100, 3))
        assertFalse("已达 cap=3", budget.tryConsume("bulletin", 100, 3))
        assertEquals(3, count(100))
    }

    @Test
    fun `E15 cap小于等于0恒false`() = runBlocking {
        assertFalse(budget.tryConsume("bulletin", 100, 0))
        assertFalse(budget.tryConsume("bulletin", 100, -5))
        assertNull("恒 false 不写台账", count(100))
    }

    @Test
    fun `E15 类目与日独立计数`() = runBlocking {
        assertTrue(budget.tryConsume("bulletin", 100, 1))
        assertFalse("同类目同日到 cap", budget.tryConsume("bulletin", 100, 1))
        assertTrue("不同类目独立", budget.tryConsume("other", 100, 1))
        assertTrue("不同日独立", budget.tryConsume("bulletin", 101, 1))
    }

    @Test
    fun `E15 清30天前旧台账`() = runBlocking {
        assertTrue(budget.tryConsume("bulletin", 60, 3)) // 旧行 day60
        assertEquals(1, count(60))
        // 在 day100 消费 → 同事务删 epochDay < 70 → day60 被清。
        assertTrue(budget.tryConsume("bulletin", 100, 3))
        assertNull("day60 < 100−30=70 应被清", count(60))
        assertEquals(1, count(100))
    }
}
