package com.situ.aichat.world

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.world.cast.WorldResidentService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [WorldBootstrap] 种子竞态金标 T2-1（W14 图纸 §3.1/§5 E5·真 Room in-memory + Robolectric）：
 * 建世盲写窗内若备份导入恰落了世界行，本机新种子**绝不覆盖**已恢复的备份种子。断言从图纸 §3.1 独立反推。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldBootstrapRaceTest {

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

    /** E5 地基：已存 id=1 行 → insertStateIfAbsent 返回 -1、不覆盖原种子。 */
    @Test
    fun insertStateIfAbsent恒不覆盖已存行_E5() = runBlocking {
        db.worldDao().upsertState(WorldStateEntity(seed = 999L, userTimezoneId = "Asia/Shanghai", createdAt = 555L))
        val rowId = db.worldDao().insertStateIfAbsent(WorldStateEntity(seed = 1L, userTimezoneId = null, createdAt = 777L))
        assertEquals("已存行 → IGNORE 返回 -1", -1L, rowId)
        val s = db.worldDao().getState()!!
        assertEquals("原种子不被覆盖", 999L, s.seed)
        assertEquals("原时区不被覆盖", "Asia/Shanghai", s.userTimezoneId)
        assertEquals("原建世时刻不被覆盖", 555L, s.createdAt)
    }

    /** 空表 → insertStateIfAbsent 真插一行（返回有效 rowId，证明非无脑 no-op）。 */
    @Test
    fun insertStateIfAbsent空表真插_E5() = runBlocking {
        val rowId = db.worldDao().insertStateIfAbsent(WorldStateEntity(seed = 7L, createdAt = 1L))
        assertTrue("空表插入返回有效 rowId", rowId >= 0L)
        assertEquals(7L, db.worldDao().getState()!!.seed)
    }

    /**
     * E5 竞态结局：备份导入已落世界行（seed=222）后 ensureCreated 才跑 → 拿回备份行、本机随机种子未覆盖。
     * （双查改造后 getState 非 null 直接返回；即便走到 insertStateIfAbsent 也被 IGNORE 挡下。）
     */
    @Test
    fun ensureCreated遇已导入备份行不覆盖_E5() = runBlocking {
        db.worldDao().upsertState(WorldStateEntity(seed = 222L, userTimezoneId = "UTC", createdAt = 100L))
        val s = bootstrap.ensureCreated(nowMs = 9_999_999L)
        assertEquals("备份种子胜（本机新种子未覆盖）", 222L, s.seed)
        assertEquals("nowMs 不生效（复用备份行）", 100L, s.createdAt)
        assertEquals("UTC", s.userTimezoneId)
    }

    /** 空表首建 → 两次 ensureCreated 同种子、单行原样（幂等路径行为逐位不变）。 */
    @Test
    fun ensureCreated空表幂等两次同种子_E5() = runBlocking {
        val first = bootstrap.ensureCreated(1000L)
        val second = bootstrap.ensureCreated(9999L)
        assertEquals("复用同一行 → 同种子（不因每次新 Random 而分叉）", first.seed, second.seed)
        assertEquals("nowMs 不生效（已存行）", 1000L, second.createdAt)
        assertEquals("恒单行 id=1", first.seed, db.worldDao().getState()!!.seed)
    }
}
