package com.situ.aichat.world.link

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.WorldSocialDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEventEntity
import com.situ.aichat.util.StringListJson
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.social.WorldRelationshipBeats
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * [WorldMirrorDeriver] T2 行为测试（W5 图纸 §7 T2-1/T2-2·E1/E2·Robolectric 真 Room + 真 DAO）。
 *
 * D13 闭窗核心：镜像 = 已落库关系事件的**纯派生**，重跑重派生同 uuid 同字段（幂等）。断言从图纸 §3.3 /
 * §4.1（W4 buildMirror 规格）独立反推——uuid 串 / involved 排序 / cityId 取自主动方家乡逐字段核对。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldMirrorDeriverTest {

    private lateinit var db: AppDatabase
    private lateinit var social: WorldSocialDao
    private lateinit var characters: CharacterDao
    private lateinit var deriver: WorldMirrorDeriver

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        social = db.worldSocialDao()
        characters = db.characterDao()
        deriver = WorldMirrorDeriver(social, characters)
    }

    @After
    fun tearDown() = db.close()

    private fun char(uuid: String, home: String) =
        CharacterEntity(uuid = uuid, name = uuid, creationDate = 0L, joinedWorld = true, worldHomeCityId = home)

    private fun relEvent(uuid: String, actor: String, target: String, kind: String, summary: String, at: Long) =
        WorldRelationshipEventEntity(
            uuid = uuid, pairKey = WorldIds.pairKey(actor, target), actorId = actor, targetId = target,
            kindRaw = kind, arcId = null, summary = summary, happenedAt = at, settledAt = at,
        )

    // MARK: - E1 D13 闭窗 + 字段与 W4 原构造逐字段相等

    @Test
    fun `E1 删镜像后重派生同uuid_字段与W4构造逐字段相等`() = runBlocking {
        characters.upsert(char("c1", home = "city_yunye"))
        characters.upsert(char("c2", home = "city_other"))
        val relUuid = "rel-first-meet-1"
        social.upsertEvent(relEvent(relUuid, actor = "c1", target = "c2", kind = WorldRelationshipBeats.FIRST_MEET, summary = "c1和c2在城里相识", at = 5000L))

        val mirrors = deriver.deriveSince(0L)
        assertEquals("首识事件恰派生一条镜像", 1, mirrors.size)
        val m = mirrors.first()
        // 逐字段独立按 W4 buildMirror 规格反推：
        assertEquals(UUID.nameUUIDFromBytes("world:relw:$relUuid".toByteArray()).toString(), m.uuid)
        assertEquals("relationship", m.kindRaw)
        assertEquals("involved 升序去向", StringListJson.encode(listOf("c1", "c2")), m.involvedIdsJson)
        assertEquals("cityId = 主动方(c1) 家乡城", "city_yunye", m.cityId)
        assertEquals("c1和c2在城里相识", m.summary)
        assertEquals(5000L, m.happenedAt)

        // D13 闭窗：镜像只是派生、未落库；重跑必然重派生同 uuid 同字段（幂等·任意时点崩溃自愈）。
        val mirrors2 = deriver.deriveSince(0L)
        assertEquals("重派生同 uuid", listOf(m.uuid), mirrors2.map { it.uuid })
        assertEquals("重派生逐字段相等", m, mirrors2.first())
    }

    // MARK: - involved 排序与 cityId 随主动方（actor=字典序较大者也照样升序 involved）

    @Test
    fun `involved恒升序_cityId取主动方家乡_与actor朝向无关`() = runBlocking {
        characters.upsert(char("c1", home = "city_yunye"))
        characters.upsert(char("c2", home = "city_other"))
        // 主动方 = c2（字典序较大），对象方 = c1：involved 仍应 [c1,c2]，cityId = c2 的家乡。
        social.upsertEvent(relEvent("rel-mend-1", actor = "c2", target = "c1", kind = WorldRelationshipBeats.QUARREL_MEND, summary = "和好了", at = 7000L))
        val m = deriver.deriveSince(0L).single()
        assertEquals(StringListJson.encode(listOf("c1", "c2")), m.involvedIdsJson)
        assertEquals("city_other", m.cityId)
    }

    // MARK: - E2 actor 角色缺失（删角/未入世）跳过该条·其余照派

    @Test
    fun `E2 actor角色缺失_跳过该条其余照派不抛`() = runBlocking {
        characters.upsert(char("c1", home = "city_yunye"))
        // c9 从未入库（模拟已删/未入世）——它作为 actor 的事件应被跳过。
        social.upsertEvent(relEvent("rel-orphan", actor = "c9", target = "c1", kind = WorldRelationshipBeats.FIRST_MEET, summary = "孤儿", at = 100L))
        social.upsertEvent(relEvent("rel-ok", actor = "c1", target = "c9", kind = WorldRelationshipBeats.MILESTONE, summary = "里程碑", at = 200L))

        val mirrors = deriver.deriveSince(0L)
        assertEquals("仅 actor 存在的那条派生", 1, mirrors.size)
        assertEquals("里程碑", mirrors.first().summary)
    }

    // MARK: - 只镜像 MIRROR_KINDS：小事（outing/help/gossip/cold/drift/compact）零镜像

    @Test
    fun `只镜像四种戏剧拍_小事零镜像`() = runBlocking {
        characters.upsert(char("c1", home = "city_yunye"))
        characters.upsert(char("c2", home = "city_other"))
        val small = listOf(
            WorldRelationshipBeats.OUTING, WorldRelationshipBeats.HELP, WorldRelationshipBeats.GOSSIP,
            WorldRelationshipBeats.QUARREL_COLD, WorldRelationshipBeats.DRIFT, WorldRelationshipBeats.COMPACT,
        )
        small.forEachIndexed { i, k -> social.upsertEvent(relEvent("small-$i", "c1", "c2", k, "s$i", 100L + i)) }
        val dramatic = listOf(
            WorldRelationshipBeats.FIRST_MEET, WorldRelationshipBeats.QUARREL_START,
            WorldRelationshipBeats.QUARREL_MEND, WorldRelationshipBeats.MILESTONE,
        )
        dramatic.forEachIndexed { i, k -> social.upsertEvent(relEvent("big-$i", "c1", "c2", k, "b$i", 500L + i)) }

        val mirrors = deriver.deriveSince(0L)
        assertEquals("恰四种戏剧拍派生", 4, mirrors.size)
        assertEquals("全为 b 前缀 summary（无小事）", setOf("b0", "b1", "b2", "b3"), mirrors.map { it.summary }.toSet())
    }

    // MARK: - fromMs 下界：早于窗口的事件不派生

    @Test
    fun `早于fromMs的事件不派生`() = runBlocking {
        characters.upsert(char("c1", home = "city_yunye"))
        characters.upsert(char("c2", home = "city_other"))
        social.upsertEvent(relEvent("old", "c1", "c2", WorldRelationshipBeats.FIRST_MEET, "旧", 100L))
        social.upsertEvent(relEvent("new", "c1", "c2", WorldRelationshipBeats.MILESTONE, "新", 2000L))
        val mirrors = deriver.deriveSince(1000L)
        assertEquals(listOf("新"), mirrors.map { it.summary })
    }
}
