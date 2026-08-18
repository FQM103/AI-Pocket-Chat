package com.situ.aichat.data.local.dao

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.OpenLoopEntity
import com.situ.aichat.data.local.entity.OpenLoopStatus
import com.situ.aichat.data.local.entity.OpenLoopType
import com.situ.aichat.data.repository.OpenLoopRepository
import com.situ.aichat.openloop.OpenLoopScanService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 活人感一期 P2 · T2-5（E11）：[OpenLoopDao] 真 Room 行为——open 过滤 + 会话/角色级联删无孤儿。
 * 用真 AppDatabase（in-memory）验 DAO 查询与删除语义确实生效（不止编译过）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenLoopDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: OpenLoopDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.openLoopDao()
    }

    @After
    fun tearDown() = db.close()

    private fun loop(
        uuid: String,
        conv: String,
        char: String,
        status: String = OpenLoopStatus.OPEN,
    ) = OpenLoopEntity(
        uuid = uuid,
        conversationUuid = conv,
        characterUuid = char,
        content = "content-$uuid",
        typeRaw = OpenLoopType.OPEN_TOPIC,
        createdAt = 0L,
        statusRaw = status,
    )

    @Test
    fun `openByCharacter 只返回该角色的 open 状态行`() = runBlocking {
        dao.upsertAll(
            listOf(
                loop("l1", "conv1", "charA"),
                loop("l2", "conv1", "charA", status = OpenLoopStatus.RESOLVED),
                loop("l3", "conv1", "charA", status = OpenLoopStatus.EXPIRED),
                loop("l4", "conv2", "charB"),
            ),
        )
        val open = dao.openByCharacter("charA")
        assertEquals("charA 仅 1 条 open（resolved/expired 被滤）", listOf("l1"), open.map { it.uuid })
    }

    @Test
    fun `deleteByConversation 只清该会话的行`() = runBlocking {
        dao.upsertAll(
            listOf(
                loop("l1", "conv1", "charA"),
                loop("l2", "conv2", "charA"), // 同角色另一会话
                loop("l3", "conv1", "charB"), // 同会话另一角色（理论上不同会话，防御性）
            ),
        )
        dao.deleteByConversation("conv1")
        assertEquals("conv1 的 loops 全清", null, dao.byUuid("l1"))
        assertEquals("conv1 的 loops 全清", null, dao.byUuid("l3"))
        assertEquals("conv2 的 loop 保留", "l2", dao.byUuid("l2")?.uuid)
    }

    @Test
    fun `deleteByCharacter 清该角色所有会话的行不碰他人`() = runBlocking {
        dao.upsertAll(
            listOf(
                loop("l1", "conv1", "charA"),
                loop("l2", "conv2", "charA"), // charA 的另一会话
                loop("l3", "conv3", "charB"),
            ),
        )
        dao.deleteByCharacter("charA")
        assertEquals("charA 跨会话全清", null, dao.byUuid("l1"))
        assertEquals("charA 跨会话全清", null, dao.byUuid("l2"))
        assertEquals("charB 保留", "l3", dao.byUuid("l3")?.uuid)
    }

    // ── 活人感二期 M2 · T2-4：长线回访候选窗口/过滤/排序 + markRevisited 终态（E8/E11/E12） ──

    /** 固定 now 与 7d/30d 窗口边界（真实常量，验证 Repository 换算 + DAO BETWEEN 闭区间协同）。 */
    private val nowMs = 1_000_000_000_000L
    private val d7 = OpenLoopScanService.REVISIT_MIN_MS
    private val d30 = OpenLoopScanService.REVISIT_MAX_MS

    private fun resolvedEvent(
        uuid: String,
        char: String = "charA",
        resolvedAt: Long?,
        type: String = OpenLoopType.USER_EVENT,
        status: String = OpenLoopStatus.RESOLVED,
    ) = OpenLoopEntity(
        uuid = uuid,
        conversationUuid = "conv1",
        characterUuid = char,
        content = "content-$uuid",
        typeRaw = type,
        createdAt = 0L,
        statusRaw = status,
        resolvedAt = resolvedAt,
    )

    @Test
    fun `revisitCandidates 窗口闭区间_两端点入选_两侧外排除`() = runBlocking {
        val repo = OpenLoopRepository(dao)
        dao.upsertAll(
            listOf(
                resolvedEvent("at7", resolvedAt = nowMs - d7),        // 恰 7 天 → 入选（闭区间上界）
                resolvedEvent("at30", resolvedAt = nowMs - d30),      // 恰 30 天 → 入选（闭区间下界）
                resolvedEvent("mid", resolvedAt = nowMs - (d7 + d30) / 2), // 窗口正中 → 入选
                resolvedEvent("tooRecent", resolvedAt = nowMs - d7 + 1),   // 不足 7 天 → 排除
                resolvedEvent("tooOld", resolvedAt = nowMs - d30 - 1),     // 超 30 天 → 排除
            ),
        )
        val got = repo.revisitCandidates("charA", nowMs).map { it.uuid }.toSet()
        assertEquals(setOf("at7", "at30", "mid"), got)
    }

    @Test
    fun `revisitCandidates 只取 resolved 加 user_event`() = runBlocking {
        val repo = OpenLoopRepository(dao)
        val inWindow = nowMs - d7 - d7 // 14 天前，窗口内
        dao.upsertAll(
            listOf(
                resolvedEvent("ok", resolvedAt = inWindow),                                        // resolved+user_event → 入选
                resolvedEvent("wrongType", resolvedAt = inWindow, type = OpenLoopType.OPEN_TOPIC),  // 非 user_event → 排除
                resolvedEvent("wrongStatus", resolvedAt = inWindow, status = OpenLoopStatus.OPEN),   // 非 resolved → 排除
                resolvedEvent("revisited", resolvedAt = inWindow, status = OpenLoopStatus.REVISITED),// 终态 → 排除（E12）
                resolvedEvent("nullResolved", resolvedAt = null),                                    // resolvedAt 空 → 排除
            ),
        )
        assertEquals(listOf("ok"), repo.revisitCandidates("charA", nowMs).map { it.uuid })
    }

    @Test
    fun `revisitCandidates 按 resolvedAt 升序`() = runBlocking {
        val repo = OpenLoopRepository(dao)
        dao.upsertAll(
            listOf(
                resolvedEvent("newer", resolvedAt = nowMs - d7 - 1000),      // 较新
                resolvedEvent("older", resolvedAt = nowMs - d30 + 1000),     // 较旧
                resolvedEvent("middle", resolvedAt = nowMs - (d7 + d30) / 2), // 居中
            ),
        )
        assertEquals(listOf("older", "middle", "newer"), repo.revisitCandidates("charA", nowMs).map { it.uuid })
    }

    @Test
    fun `markRevisited 置终态_resolvedAt 原值保留_不再入候选`() = runBlocking {
        val repo = OpenLoopRepository(dao)
        val resolvedAt = nowMs - d7 - d7 // 14 天前，窗口内
        val loop = resolvedEvent("r1", resolvedAt = resolvedAt)
        dao.upsert(loop)
        assertEquals("标记前在候选内", listOf("r1"), repo.revisitCandidates("charA", nowMs).map { it.uuid })

        repo.markRevisited(loop, nowMs)

        val after = dao.byUuid("r1")!!
        assertEquals("statusRaw 置 revisited", OpenLoopStatus.REVISITED, after.statusRaw)
        assertEquals("resolvedAt 原值保留不覆盖（E8）", resolvedAt, after.resolvedAt)
        assertTrue("终态后不再入候选（E12）", repo.revisitCandidates("charA", nowMs).isEmpty())
    }

    @Test
    fun `revisitCandidates 只取本角色`() = runBlocking {
        val repo = OpenLoopRepository(dao)
        val inWindow = nowMs - d7 - d7
        dao.upsertAll(
            listOf(
                resolvedEvent("mine", char = "charA", resolvedAt = inWindow),
                resolvedEvent("theirs", char = "charB", resolvedAt = inWindow),
            ),
        )
        assertEquals(listOf("mine"), repo.revisitCandidates("charA", nowMs).map { it.uuid })
        assertNull("charA 候选不含他人", repo.revisitCandidates("charA", nowMs).firstOrNull { it.uuid == "theirs" })
    }
}
