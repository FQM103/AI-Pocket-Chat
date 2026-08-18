package com.situ.aichat.world

import android.util.Log
import com.situ.aichat.data.local.entity.WorldEventEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.repository.WorldRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * [WorldSettlementCoordinator] T2 行为测试（W2 图纸 §7 T2-1…T2-8·断言从图纸 §3.4/§5 独立反推）。
 *
 * 手法：MockK 假 [WorldRepository]——`ensureState()` 回读当前锚、`advanceSettledAt` 用 [AtomicLong] 复刻
 * SQL `MAX()` 语义、`recordEvent` 累进 [recorded]。假贡献者按 §3.3 契约实现（种子派生 uuid·日粒度·确定性）。
 * `Log.w` 静态假掉（贡献者异常吞掉路径会调）。
 */
class WorldSettlementCoordinatorTest {

    private companion object {
        const val SEED = 123456789L
        val UTC: ZoneId = ZoneOffset.UTC
    }

    private val repo = mockk<WorldRepository>()
    private val anchor = AtomicLong(0L)
    private val recorded = mutableListOf<WorldEventEntity>()
    private var timezoneId: String? = "UTC" // 默认锁 UTC 令日切与机器默认时区无关

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>(), any()) } returns 0
        coEvery { repo.ensureState() } answers {
            WorldStateEntity(seed = SEED, userTimezoneId = timezoneId, lastSettledAt = anchor.get(), createdAt = 0L)
        }
        coEvery { repo.advanceSettledAt(any()) } answers {
            val at = firstArg<Long>()
            anchor.updateAndGet { cur -> maxOf(cur, at) } // MAX() 只进不退
            Unit
        }
        coEvery { repo.recordEvent(any()) } answers {
            recorded.add(firstArg())
            Unit
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    // ---- 测试脚手架 ----

    private fun coordinator(vararg contribs: WorldSettlementContributor) =
        WorldSettlementCoordinator(repo, linkedSetOf(*contribs))

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId = UTC): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    /** 假贡献者：按 §3.3 契约——确定性、uuid 种子派生、日粒度。可配抛异常（E6）。 */
    private class FakeContributor(
        override val id: String,
        private val eventsPerDay: Int = 1,
        private val throwOnSettle: Boolean = false,
    ) : WorldSettlementContributor {
        override suspend fun settle(state: WorldStateEntity, window: SettlementWindow): List<WorldEventEntity> {
            if (throwOnSettle) throw IllegalStateException("boom from $id")
            return window.days.flatMap { day ->
                (0 until eventsPerDay).map { idx ->
                    val uuid = UUID.nameUUIDFromBytes("world:$id:${day.daySeed}:$idx".toByteArray()).toString()
                    WorldEventEntity(
                        uuid = uuid,
                        kindRaw = "test",
                        summary = "day ${day.epochDay} #$idx by $id",
                        happenedAt = day.epochDay,
                    )
                }
            }
        }
    }

    // ---- T2-1：E1 首次结算（锚==0） ----

    @Test
    fun `T2-1 首启_firstRun且不产事件_锚落到now`() = runBlocking {
        anchor.set(0L)
        val now = ms(2026, 5, 10, 9, 0)
        val window = coordinator(FakeContributor("c1")).ensureSettled(now)

        assertTrue(window.firstRun)
        assertTrue(window.days.isEmpty())
        assertEquals(0, window.truncatedDays)
        assertEquals(0L, window.absenceMs)
        coVerify(exactly = 0) { repo.recordEvent(any()) }
        coVerify(exactly = 1) { repo.advanceSettledAt(now) }
        assertEquals(now, anchor.get())
    }

    // ---- T2-2：E2 设备时间回拨（now < 锚） ----

    @Test
    fun `T2-2 回拨_冻结空窗_锚经MAX不变`() = runBlocking {
        anchor.set(100_000L)
        val window = coordinator(FakeContributor("c1")).ensureSettled(50_000L)

        assertTrue(window.days.isEmpty())
        assertEquals(false, window.firstRun)
        assertEquals(0, window.truncatedDays)
        assertEquals(0L, window.absenceMs)
        coVerify(exactly = 0) { repo.recordEvent(any()) }
        coVerify(exactly = 1) { repo.advanceSettledAt(50_000L) }
        assertEquals(100_000L, anchor.get()) // 只进不退
    }

    // ---- T2-3：E3 缺席 ~30 天（封顶留最后 7 天） ----

    @Test
    fun `T2-3 缺席30天_留最后7天_截断23`() = runBlocking {
        anchor.set(ms(2026, 1, 1, 12, 0))     // Jan 1
        val now = ms(2026, 1, 30, 12, 0)      // Jan 30（含两端 = 30 天）
        val window = coordinator().ensureSettled(now)

        assertEquals(7, window.days.size)
        assertEquals(23, window.truncatedDays) // 30 - 7
        assertEquals(LocalDate.of(2026, 1, 24), window.days.first().date)
        assertEquals(LocalDate.of(2026, 1, 30), window.days.last().date)
        assertEquals(window.days.map { it.epochDay }.sorted(), window.days.map { it.epochDay }) // 升序
        assertEquals(false, window.firstRun)
    }

    // ---- T2-4：E4 跨午夜（昨 23:50 → 今 00:10） ----

    @Test
    fun `T2-4 跨午夜_两天升序`() = runBlocking {
        anchor.set(ms(2026, 3, 14, 23, 50))
        val now = ms(2026, 3, 15, 0, 10)
        val window = coordinator().ensureSettled(now)

        assertEquals(2, window.days.size)
        assertEquals(LocalDate.of(2026, 3, 14), window.days[0].date)
        assertEquals(LocalDate.of(2026, 3, 15), window.days[1].date)
        assertEquals(0, window.truncatedDays)
        assertEquals(20L * 60 * 1000, window.absenceMs)
    }

    // ---- T2-5：E5 同日两次开 app（幂等·uuid 完全相同） ----

    @Test
    fun `T2-5 同日两次_窗口同为今天_两轮uuid相同`() = runBlocking {
        anchor.set(ms(2026, 5, 10, 0, 1))
        val coord = coordinator(FakeContributor("c1"))

        val w1 = coord.ensureSettled(ms(2026, 5, 10, 9, 0))
        val round1 = recorded.map { it.uuid }
        val w2 = coord.ensureSettled(ms(2026, 5, 10, 18, 0))
        val round2 = recorded.drop(round1.size).map { it.uuid }

        assertEquals(listOf(LocalDate.of(2026, 5, 10)), w1.days.map { it.date })
        assertEquals(listOf(LocalDate.of(2026, 5, 10)), w2.days.map { it.date })
        assertTrue(round1.isNotEmpty())
        assertEquals(round1, round2) // 同日 daySeed 相同 → uuid 逐条相同 = 幂等实证
    }

    // ---- T2-6：E6 某贡献者抛异常（别家照跑、锚照推、不崩） ----

    @Test
    fun `T2-6 贡献者异常_其余照跑锚照推`() = runBlocking {
        anchor.set(ms(2026, 6, 1, 12, 0))
        val now = ms(2026, 6, 2, 12, 0) // Jun1..Jun2 = 2 天
        val window = coordinator(
            FakeContributor("bad", throwOnSettle = true),
            FakeContributor("good"),
        ).ensureSettled(now)

        assertEquals(2, window.days.size)
        assertEquals(2, recorded.size) // 只 good 的 2 天事件（bad 抛异常吞掉）
        assertTrue(recorded.all { it.summary.contains("by good") })
        coVerify(exactly = 1) { repo.advanceSettledAt(now) } // 锚照推
        verify { Log.w("WorldSettlement", any<String>(), any()) } // 异常记日志
    }

    // ---- T2-7：E7 两协程并发 ensureSettled（Mutex 串行） ----

    @Test
    fun `T2-7 并发_Mutex串行_只结算一次`() = runBlocking {
        anchor.set(ms(2026, 7, 1, 0, 1))
        val now = ms(2026, 7, 1, 12, 0)
        val coord = coordinator(FakeContributor("c1"))

        val (w1, w2) = coroutineScope {
            val a = async(Dispatchers.Default) { coord.ensureSettled(now) }
            val b = async(Dispatchers.Default) { coord.ensureSettled(now) }
            a.await() to b.await()
        }

        // 串行：一者拿到 [今天]、后进者锚已被推进 → 空窗（冻结）。无 Mutex 则两者都产 = recorded 2 条。
        assertEquals(1, listOf(w1, w2).count { it.days.isNotEmpty() })
        assertEquals(1, recorded.size)
        assertEquals(now, anchor.get())
        coVerify(exactly = 2) { repo.advanceSettledAt(now) } // 两次都收尾推锚（MAX 幂等）
    }

    // ---- T2-8：E8 时区（非法回退不崩 / 合法 Asia/Shanghai 日切正确） ----

    @Test
    fun `T2-8a 非法时区_回退不崩`() = runBlocking {
        timezoneId = "Not/AZone"
        anchor.set(ms(2026, 8, 1, 12, 0))
        val now = ms(2026, 8, 2, 12, 0)
        val window = coordinator(FakeContributor("c1")).ensureSettled(now)

        assertEquals(false, window.firstRun) // 未崩、走到正常分支
        coVerify(exactly = 1) { repo.advanceSettledAt(now) }
    }

    @Test
    fun `T2-8b 上海时区_日切按用户时区`() = runBlocking {
        timezoneId = "Asia/Shanghai"
        // UTC 2026-03-14 20:00 == SH 2026-03-15 04:00；UTC 2026-03-15 01:00 == SH 2026-03-15 09:00。
        anchor.set(ms(2026, 3, 14, 20, 0, UTC))
        val now = ms(2026, 3, 15, 1, 0, UTC)
        val window = coordinator().ensureSettled(now)

        // 上海侧两端同为 3/15 → 单日；若误用 UTC 则会是两天。
        assertEquals(1, window.days.size)
        assertEquals(LocalDate.of(2026, 3, 15), window.days[0].date)
        assertNotEquals(2, window.days.size)
    }
}
