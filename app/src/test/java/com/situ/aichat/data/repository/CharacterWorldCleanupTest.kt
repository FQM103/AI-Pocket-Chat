package com.situ.aichat.data.repository

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.local.entity.WorldBulletinEntity
import com.situ.aichat.data.local.entity.WorldEventEntity
import com.situ.aichat.data.local.entity.WorldMemoryEntity
import com.situ.aichat.data.local.entity.WorldNativeStateEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEventEntity
import com.situ.aichat.data.local.entity.WorldTravelEntity
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.cast.WorldResidentService
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
 * 删角色的世界痕迹清理 T2（E5·W1 图纸 §7 T2-4）：真 [CharacterRepository] + Room 内存库。断言从图纸 §3/§5
 * 反推——`delete(uuid)` 单事务内清：两向关系边 / 关系事件 / 在途旅行 / 世界事件提及 / 原住民招募指针（缘分归零），
 * FK CASCADE 继续清里程碑；**不相关行原样保留**；全程不崩（混合域无 FK）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CharacterWorldCleanupTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: CharacterRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = CharacterRepository(
            db.characterDao(), db.milestoneDao(), db,
            WorldResidentService(db.worldUserResidentDao(), db.worldNativeDao(), db.worldDao(), db),
            io.mockk.mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun 删角色单事务清世界痕迹_不相关行保留() = runBlocking {
        val victim = "victim-uuid"
        val bystander = "bystander-uuid"
        val other = "other-uuid"
        val social = db.worldSocialDao()
        val world = db.worldDao()
        val native = db.worldNativeDao()

        // 角色行（victim + 无关 bystander·FK CASCADE 测试用里程碑各一条）。
        db.characterDao().upsert(CharacterEntity(uuid = victim, name = "小K", creationDate = 0L))
        db.characterDao().upsert(CharacterEntity(uuid = bystander, name = "旁观者", creationDate = 0L))
        db.milestoneDao().upsert(MilestoneEntity("m-v", victim, "朋友", 1L))
        db.milestoneDao().upsert(MilestoneEntity("m-b", bystander, "朋友", 1L))

        // 关系边：含 victim 的两向边 + 一条完全无关边（bystander→other）。
        social.upsertEdge(WorldRelationshipEntity(fromId = victim, toId = bystander))
        social.upsertEdge(WorldRelationshipEntity(fromId = bystander, toId = victim))
        social.upsertEdge(WorldRelationshipEntity(fromId = victim, toId = "native:x"))
        social.upsertEdge(WorldRelationshipEntity(fromId = bystander, toId = other)) // 无关

        // 关系事件：victim 发起 / 作为对象各一 + 一条无关。
        social.upsertEvent(evt("re-v1", WorldIds.pairKey(victim, bystander), victim, bystander))
        social.upsertEvent(evt("re-v2", WorldIds.pairKey(bystander, victim), bystander, victim))
        social.upsertEvent(evt("re-x", WorldIds.pairKey(bystander, other), bystander, other)) // 无关

        // 在途旅行：victim 一行 + 无关 bystander 一行。
        world.upsertTravel(travel(victim))
        world.upsertTravel(travel(bystander))

        // 世界事件：提及 victim 一条 + 不提及的一条。
        world.upsertEvent(worldEvt("we-v", """["$victim","user"]"""))
        world.upsertEvent(worldEvt("we-x", """["$bystander","user"]"""))

        // 原住民：被 victim 招募的（带眼缘燃料）+ 被别人招募的无关原住民。
        native.upsert(
            WorldNativeStateEntity(
                nativeId = "native:x", discovered = true, discoveredAt = 5L, narrativeFuel = 30,
                giftFuel = 20, encounterCount = 5, recruitedCharacterUuid = victim,
            ),
        )
        native.upsert(
            WorldNativeStateEntity(
                nativeId = "native:y", narrativeFuel = 10, giftFuel = 5, recruitedCharacterUuid = other,
            ),
        )

        // 世界记忆（W5·E16）：victim 视角一条 + 他人记忆提及 victim 一条（均应清）+ 完全无关一条（应留）。
        val memory = db.worldMemoryDao()
        memory.upsert(mem("wm-self", victim, listOf(bystander)))
        memory.upsert(mem("wm-mention", bystander, listOf(victim)))
        memory.upsert(mem("wm-unrelated", bystander, listOf(other)))

        // 开机小报（W5·E16）：两天各一份——删角后应全清（正文可能含其名·下次结算重生成）。
        val bulletin = db.worldBulletinDao()
        bulletin.upsert(bull(epochDay = 100L))
        bulletin.upsert(bull(epochDay = 101L))

        // —— 删角色（单事务）——
        repo.delete(victim)

        // 角色：victim 删、bystander 留。
        assertNull("victim 角色行已删", db.characterDao().getByUuid(victim))
        assertTrue("bystander 角色行保留", db.characterDao().getByUuid(bystander) != null)

        // 里程碑：FK CASCADE 清 victim 的、留 bystander 的。
        assertTrue("victim 里程碑级联删", db.milestoneDao().getForCharacter(victim).isEmpty())
        assertEquals("bystander 里程碑保留", 1, db.milestoneDao().getForCharacter(bystander).size)

        // 关系边：含 victim 的两向边全清；无关边保留。
        val edges = social.getAllEdges()
        assertFalse("无任何含 victim 的边", edges.any { it.fromId == victim || it.toId == victim })
        assertTrue("无关边 bystander→other 保留", edges.any { it.fromId == bystander && it.toId == other })
        assertEquals("仅剩无关边一条", 1, edges.size)

        // 关系事件：涉及 victim（actor/target）全清；无关事件保留。
        val revents = social.getAllEvents()
        assertFalse("无任何涉及 victim 的关系事件", revents.any { it.actorId == victim || it.targetId == victim })
        assertEquals("仅剩无关关系事件", listOf("re-x"), revents.map { it.uuid })

        // 在途旅行：victim 清、bystander 留。
        assertNull("victim 在途清除", world.getTravel(victim))
        assertTrue("bystander 在途保留", world.getTravel(bystander) != null)

        // 世界事件：提及 victim 的清、不提及的留。
        assertEquals("仅剩不提及 victim 的世界事件", listOf("we-x"), world.getAllEvents().map { it.uuid })

        // 原住民：victim 招募的 → 指针置 null + 燃料归零，但 discovered/encounterCount 不动；别人招募的原住民不受扰。
        val nx = native.get("native:x")!!
        assertNull("招募指针置 null", nx.recruitedCharacterUuid)
        assertEquals("叙事燃料归零", 0, nx.narrativeFuel)
        assertEquals("心意燃料归零", 0, nx.giftFuel)
        assertTrue("已发现态不受扰", nx.discovered)
        assertEquals("偶遇计数不受扰", 5, nx.encounterCount)
        val ny = native.get("native:y")!!
        assertEquals("无关原住民招募指针不动", other, ny.recruitedCharacterUuid)
        assertEquals("无关原住民燃料不动", 10, ny.narrativeFuel)

        // 世界记忆（E16）：victim 视角 + 他人提及 victim 全清；完全无关记忆保留。
        assertEquals("仅剩完全无关的世界记忆", listOf("wm-unrelated"), memory.getAll().map { it.uuid })

        // 开机小报（E16）：全清（下次结算重生成）。
        assertNull("小报 day100 已清", bulletin.getByDay(100L))
        assertNull("小报 day101 已清", bulletin.getByDay(101L))
    }

    private fun mem(uuid: String, characterUuid: String, others: List<String>) = WorldMemoryEntity(
        uuid = uuid, characterUuid = characterUuid,
        otherIdsJson = others.joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]"),
        kindRaw = "rel_first_meet", content = "记忆", happenedAt = 1L, sourceUuid = "src", createdAt = 1L,
    )

    private fun bull(epochDay: Long) = WorldBulletinEntity(
        epochDay = epochDay, windowStartMs = 0L, windowEndMs = 1L, eventsHash = 0L,
        templateText = "小报", updatedAt = 1L,
    )

    private fun evt(uuid: String, pairKey: String, actor: String, target: String) =
        WorldRelationshipEventEntity(
            uuid = uuid, pairKey = pairKey, actorId = actor, targetId = target,
            kindRaw = "chat", summary = "s", happenedAt = 1L, settledAt = 1L,
        )

    private fun travel(ownerId: String) = WorldTravelEntity(
        ownerId = ownerId, fromCityId = "city_yunye", toCityId = "city_hai",
        departAt = 1L, arriveAt = 2L, modeRaw = WorldIds.TravelModes.TRAIN,
    )

    private fun worldEvt(uuid: String, involvedIdsJson: String) = WorldEventEntity(
        uuid = uuid, kindRaw = "encounter", involvedIdsJson = involvedIdsJson,
        summary = "s", happenedAt = 1L,
    )
}
