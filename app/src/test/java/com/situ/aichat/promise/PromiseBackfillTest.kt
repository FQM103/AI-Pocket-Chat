package com.situ.aichat.promise

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.local.entity.PromiseSource
import com.situ.aichat.data.repository.OpenLoopRepository
import com.situ.aichat.data.repository.PromiseRepository
import com.situ.aichat.util.StringListJson
import com.situ.aichat.work.BackgroundScheduler
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 承诺账本历史回填（记忆改造一期·图纸 §3.2 / T2-12）。用真 in-memory Room（PromiseRepository + OfflineMeetingMemoryDao
 * 走真 SQL·OpenLoopRepository/BackgroundScheduler 回填不触及故 mock）。断言从图纸 §3.2 独立反推：从 promisesJson 注册
 * （createdAt=endedAtMillis·source=meeting_backfill·session 透传）、空数组/legacy 行跳过、重跑幂等（注册端去重）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromiseBackfillTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: PromiseLedgerService
    private val now = 9_999_999L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        ledger = PromiseLedgerService(
            PromiseRepository(db.promiseDao()),
            mockk<OpenLoopRepository>(relaxed = true),
            mockk<BackgroundScheduler>(relaxed = true),
            db.offlineMeetingMemoryDao(),
        )
    }

    @After fun tearDown() { db.close() }

    private fun meeting(
        uuid: String, cid: String, session: String, started: Long, ended: Long,
        promises: List<String>, kind: String = "meeting",
    ) = OfflineMeetingMemoryEntity(
        uuid = uuid, characterUuid = cid, sessionId = session, kindRaw = kind,
        startedAtMillis = started, endedAtMillis = ended, summary = "见面$uuid",
        promisesJson = StringListJson.encode(promises).ifEmpty { "[]" },
        createdAtMillis = started, updatedAtMillis = started,
    )

    @Test fun backfill_registersFromPromisesJson_createdAtEnded_sourceBackfill() = runBlocking {
        db.offlineMeetingMemoryDao().upsertAll(
            listOf(
                meeting("mm1", "c1", "s1", started = 1000, ended = 2000, promises = listOf("下次一起去看海")),
                meeting("mm2", "c1", "s2", started = 3000, ended = 0, promises = listOf("教我做饭")), // ended=0 → 用 started
                meeting("mmEmpty", "c1", "s3", started = 5000, ended = 6000, promises = emptyList()),   // 空数组 → 跳过
                meeting("mmLegacy", "c2", "", started = 0, ended = 0, promises = emptyList(), kind = "legacy"), // legacy → 跳过
            ),
        )

        val count = ledger.backfillFromMeetingRows(now)
        assertEquals("两条 meeting 各一约定", 2, count)

        val all = db.promiseDao().getAll().associateBy { it.content }
        assertEquals(2, all.size)
        with(all.getValue("下次一起去看海")) {
            assertEquals("c1", characterUuid)
            assertEquals(PromiseSource.MEETING_BACKFILL, sourceRaw)
            assertEquals("createdAt=endedAtMillis", 2000L, createdAtMillis)
            assertEquals("s1", sourceSessionId)
            assertNull(dueAtMillis)
        }
        with(all.getValue("教我做饭")) {
            assertEquals("endedAt=0 → 用 startedAtMillis", 3000L, createdAtMillis)
        }
    }

    @Test fun backfill_rerun_isIdempotent_dedup() = runBlocking {
        db.offlineMeetingMemoryDao().upsert(
            meeting("mm1", "c1", "s1", started = 1000, ended = 2000, promises = listOf("下次一起去看海")),
        )
        assertEquals(1, ledger.backfillFromMeetingRows(now))
        assertEquals("重跑被注册端去重挡住", 0, ledger.backfillFromMeetingRows(now))
        assertEquals("账本只一条", 1, db.promiseDao().getAll().size)
    }

    @Test fun backfill_noRows_returnsZero() = runBlocking {
        assertEquals(0, ledger.backfillFromMeetingRows(now))
        assertEquals(0, db.promiseDao().getAll().size)
    }
}
