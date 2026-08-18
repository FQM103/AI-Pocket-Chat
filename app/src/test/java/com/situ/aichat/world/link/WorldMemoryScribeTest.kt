package com.situ.aichat.world.link

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.WorldMemoryDao
import com.situ.aichat.data.local.dao.WorldSocialDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEventEntity
import com.situ.aichat.util.StringListJson
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.WorldSeeds
import com.situ.aichat.world.social.WorldRelationshipBeats as Beats
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
import java.util.UUID

/**
 * [WorldMemoryScribe] T2-3（Robolectric 真 Room·图纸 §7·E4/E5/E6）：双视角 / 分级 / 幂等 / 模板逐字。
 * 断言从图纸 §4.2 16 句表 + §3.1 uuid 派生 + §4.2 variant 公式独立反推（模板在测试里重打，非引用生产 map）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldMemoryScribeTest {

    private lateinit var db: AppDatabase
    private lateinit var social: WorldSocialDao
    private lateinit var characters: CharacterDao
    private lateinit var memory: WorldMemoryDao
    private lateinit var scribe: WorldMemoryScribe

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        social = db.worldSocialDao()
        characters = db.characterDao()
        memory = db.worldMemoryDao()
        scribe = WorldMemoryScribe(social, characters, memory)
    }

    @After
    fun tearDown() = db.close()

    private fun char(uuid: String, name: String) =
        CharacterEntity(uuid = uuid, name = name, creationDate = 0L)

    private fun relEvent(uuid: String, actor: String, target: String, kind: String, at: Long = 1000L, settled: Long = 1001L) =
        WorldRelationshipEventEntity(
            uuid = uuid, pairKey = WorldIds.pairKey(actor, target), actorId = actor, targetId = target,
            kindRaw = kind, arcId = null, summary = "s", happenedAt = at, settledAt = settled,
        )

    /** 独立复刻 §4.2 variant 公式：((fnv % 2) + 2) % 2。 */
    private fun variant(eventUuid: String, subjectUuid: String): Int =
        (((WorldSeeds.fnv1a64("$eventUuid:$subjectUuid") % 2) + 2) % 2).toInt()

    private fun memUuid(eventUuid: String, subjectUuid: String) =
        UUID.nameUUIDFromBytes("world:mem:$eventUuid:$subjectUuid".toByteArray()).toString()

    // §4.2 表（测试独立重打·逐字·主动方=INITIATOR / 对象方=RECIPIENT）
    private val firstMeetInitiator = listOf(
        "你在城里认识了{other}，一见如故，聊得很投机",
        "你和{other}就这么认识了，感觉遇上了合拍的人",
    )
    private val firstMeetRecipient = listOf(
        "{other}主动和你搭了话，你们就此认识，印象还不错",
        "你认识了{other}，虽然刚见面，却觉得有点投缘",
    )
    private val mendInitiator = listOf(
        "你和{other}把话说开了，别扭烟消云散，反而更近了些",
        "你和{other}和好了，心里一块石头落了地",
    )

    // MARK: - E6 双视角 + 模板逐字 + 字段

    @Test
    fun `E6 首识双视角_模板逐字_字段正确`() = runBlocking {
        characters.upsert(char("c1", "阿哲"))
        characters.upsert(char("c2", "小雅"))
        social.upsertEvent(relEvent("ev-fm", "c1", "c2", Beats.FIRST_MEET, at = 1000L, settled = 1001L))

        assertEquals("双视角各一条 = 新写 2", 2, scribe.scribeSince(0L))
        val all = memory.getAll().associateBy { it.uuid }
        assertEquals(2, all.size)

        val actorMem = all.getValue(memUuid("ev-fm", "c1"))
        val expectedActor = firstMeetInitiator[variant("ev-fm", "c1")].replace("{other}", "小雅")
        assertEquals("主动方视角文案逐字", expectedActor, actorMem.content)
        assertEquals("c1", actorMem.characterUuid)
        assertEquals(StringListJson.encode(listOf("c2")), actorMem.otherIdsJson)
        assertEquals(Beats.FIRST_MEET, actorMem.kindRaw)
        assertEquals(1000L, actorMem.happenedAt)
        assertEquals("ev-fm", actorMem.sourceUuid)
        assertEquals("createdAt = 源事件 settledAt", 1001L, actorMem.createdAt)

        val targetMem = all.getValue(memUuid("ev-fm", "c2"))
        val expectedTarget = firstMeetRecipient[variant("ev-fm", "c2")].replace("{other}", "阿哲")
        assertEquals("对象方视角文案逐字", expectedTarget, targetMem.content)
        assertEquals("c2", targetMem.characterUuid)
        assertEquals(StringListJson.encode(listOf("c1")), targetMem.otherIdsJson)
        assertTrue("两视角文案不同（不对称）", actorMem.content != targetMem.content)
    }

    @Test
    fun `E6 和好双视角模板逐字`() = runBlocking {
        characters.upsert(char("cA", "林深"))
        characters.upsert(char("cB", "苏晓"))
        social.upsertEvent(relEvent("ev-md", "cA", "cB", Beats.QUARREL_MEND))
        scribe.scribeSince(0L)
        val actorMem = memory.getByUuid(memUuid("ev-md", "cA"))!!
        assertEquals(mendInitiator[variant("ev-md", "cA")].replace("{other}", "苏晓"), actorMem.content)
    }

    // MARK: - E5 分级（小事不进记忆库）

    @Test
    fun `E5 小事不产生任何记忆`() = runBlocking {
        characters.upsert(char("c1", "阿哲"))
        characters.upsert(char("c2", "小雅"))
        val small = listOf(
            Beats.OUTING, Beats.HELP, Beats.GOSSIP, Beats.QUARREL_COLD, Beats.DRIFT, Beats.COMPACT,
        )
        small.forEachIndexed { i, k -> social.upsertEvent(relEvent("small-$i", "c1", "c2", k, at = 100L + i)) }
        assertEquals("小事零新写", 0, scribe.scribeSince(0L))
        assertTrue("记忆库空", memory.getAll().isEmpty())
    }

    // MARK: - E4 幂等（同窗重跑不虚增·无重复行）

    @Test
    fun `E4 同窗重跑幂等_count不虚增_无重复行`() = runBlocking {
        characters.upsert(char("c1", "阿哲"))
        characters.upsert(char("c2", "小雅"))
        social.upsertEvent(relEvent("ev-fm", "c1", "c2", Beats.FIRST_MEET))
        social.upsertEvent(relEvent("ev-ms", "c1", "c2", Beats.MILESTONE, at = 2000L))

        assertEquals("首跑 4 条（2 事件 × 双视角）", 4, scribe.scribeSince(0L))
        assertEquals("重跑幂等门跳过 = 0 新写", 0, scribe.scribeSince(0L))
        assertEquals("库中恰 4 条无重复", 4, memory.getAll().size)
    }

    // MARK: - 角色查无 → 跳过该事件（写不出含对方名的记忆）

    @Test
    fun `对方角色缺失_整事件跳过`() = runBlocking {
        characters.upsert(char("c1", "阿哲")) // c2 未入库
        social.upsertEvent(relEvent("ev-fm", "c1", "c2", Beats.FIRST_MEET))
        assertEquals(0, scribe.scribeSince(0L))
        assertTrue(memory.getAll().isEmpty())
    }
}
