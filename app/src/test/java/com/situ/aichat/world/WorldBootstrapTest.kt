package com.situ.aichat.world

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.world.cast.WorldResidentService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [WorldBootstrap] T2（W9a 图纸 §5 E8/E9·§7 T2-1·真 Room in-memory + Robolectric）：
 * 静默建世幂等 + 并发防双建 + 已有世界不重建。断言从图纸 §3.1 独立反推。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldBootstrapTest {

    private lateinit var db: AppDatabase
    private lateinit var bootstrap: WorldBootstrap

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        bootstrap = WorldBootstrap(
            db.worldDao(),
            WorldResidentService(db.worldUserResidentDao(), db.worldNativeDao(), db.worldDao(), db),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun createsSingleRow_withDecision34Defaults() = runBlocking {
        val s = bootstrap.ensureCreated(1000L)
        assertEquals(1000L, s.createdAt)
        assertNull("时区暂 null（跟设备）", s.userTimezoneId)
        assertEquals("家乡默认云野镇", "city_yunye", s.userHomeCityId)
        assertNotNull(db.worldDao().getState())
    }

    @Test
    fun reCall_returnsExistingRow_seedAndCreatedAtUnchanged() = runBlocking {
        val first = bootstrap.ensureCreated(1000L)
        val second = bootstrap.ensureCreated(9999L) // 已存在 → 复用，nowMs 不生效
        assertEquals(first.seed, second.seed)
        assertEquals(1000L, second.createdAt)
    }

    @Test
    fun concurrentEnsureCreated_producesOneSeed() = runBlocking {
        // 两协程并发建世：Mutex 串行 → 后者见前者建的行 → 同 seed（无 Mutex 会各生成不同 seed·id=1 覆盖）。
        val (a, b) = coroutineScope {
            val d1 = async(Dispatchers.IO) { bootstrap.ensureCreated(1000L) }
            val d2 = async(Dispatchers.IO) { bootstrap.ensureCreated(1000L) }
            d1.await() to d2.await()
        }
        assertEquals(a.seed, b.seed)
        assertEquals(a.seed, db.worldDao().getState()!!.seed)
    }

    @Test
    fun existingWorld_isNotRebuilt() = runBlocking {
        // 模拟 W1–W8 已造的世界。
        db.worldDao().upsertState(
            WorldStateEntity(seed = 999L, userTimezoneId = "Asia/Shanghai", createdAt = 555L),
        )
        val s = bootstrap.ensureCreated(8_888_888L)
        assertEquals(999L, s.seed)
        assertEquals("Asia/Shanghai", s.userTimezoneId)
        assertEquals(555L, s.createdAt)
    }
}
