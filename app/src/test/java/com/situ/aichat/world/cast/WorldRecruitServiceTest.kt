package com.situ.aichat.world.cast

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.WorldNativeStateEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.local.entity.WorldUserResidentEntity
import com.situ.aichat.util.StringListJson
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.world.SettlementDay
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.WorldSeeds
import com.situ.aichat.world.link.WorldMemoryScribe
import com.situ.aichat.world.link.WorldMirrorDeriver
import com.situ.aichat.world.social.WorldRelationshipBeats
import com.situ.aichat.world.social.WorldRelationshipCompactor
import com.situ.aichat.world.social.WorldRelationshipEngine
import io.mockk.coEvery
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * [WorldRecruitService] T2-3/4/5/6（W6 图纸 §7·E8–E13·Robolectric 真 Room + 真引擎/派生器/抄写员）。
 *
 * E11 = 本块最关键联测：招募二人 → 引擎次日 settleDay **绝不产生 first_meet**（rel_origin 让 hasEdge 认账）+
 * W5 镜像/记忆对 `rel_origin` 零产出。断言从图纸 §3.3/§4.3/§4.5/§4.6 独立反推。全程 UTC。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldRecruitServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var service: WorldRecruitService
    private lateinit var affinity: WorldAffinityService
    private val seed = 1L
    private val day0 = 1_000_000_000_000L // 2001-09-09T01:46:40Z

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val characterRepo = CharacterRepository(db.characterDao(), db.milestoneDao(), db, residentService(), io.mockk.mockk(relaxed = true))
        affinity = WorldAffinityService(db.worldNativeDao(), db.worldDao())
        service = WorldRecruitService(db.worldNativeDao(), db.worldSocialDao(), db.worldDao(), characterRepo, db, affinity, db.worldUserResidentDao())
        db.worldDao().upsertState(WorldStateEntity(seed = seed, userTimezoneId = "UTC", createdAt = 0L))
        affinity.ensureSeeded() // 20 行（discovered=false）
    }

    @After
    fun tearDown() {
        WorldNativeRoster.registerUserDefs(emptyList()) // 战役 B：复位进程级花名册防跨测污染
        db.close()
    }

    private fun id(slug: String) = WorldIds.nativeId(slug)

    /** 战役 B：CharacterRepository 需注入 [WorldResidentService]（O2 连坐删）——测试用真 DAO 组装一枚。 */
    private fun residentService() =
        WorldResidentService(db.worldUserResidentDao(), db.worldNativeDao(), db.worldDao(), db)

    /** 令某原住民「愿意」：discovered + 燃料稳过门槛（nw≥1.0 → affinity ≥ narrativeFuel）。 */
    private fun makeWilling(slug: String) = runBlocking {
        val def = WorldNativeRoster.bySlug(slug)!!
        db.worldNativeDao().upsert(
            WorldNativeStateEntity(nativeId = id(slug), discovered = true, discoveredAt = day0, narrativeFuel = 1000, currentCityId = def.cityId),
        )
    }

    // MARK: - E8 门槛/已招募/未发现 → null·零副作用

    @Test
    fun `E8 未达门槛_未发现_已招募 均返 null 且零副作用`() = runBlocking {
        // 未达门槛（discovered 但燃料低）。
        db.worldNativeDao().upsert(WorldNativeStateEntity(nativeId = id("su_wan"), discovered = true, narrativeFuel = 1))
        assertNull(service.recruit(id("su_wan"), day0))
        // 未发现。
        assertNull(service.recruit(id("lin_moyu"), day0))
        assertEquals("零角色", 0, db.characterDao().count())
        assertTrue(db.worldDao().getAllEvents().isEmpty())
        // 已招募 → 二次 null（只一个角色）。
        makeWilling("mu_xing")
        val uuid = service.recruit(id("mu_xing"), day0)
        assertNotNull(uuid)
        assertNull("已招募二次应 null", service.recruit(id("mu_xing"), day0))
        assertEquals(1, db.characterDao().count())
    }

    // MARK: - E9 招募全事务 + §4.5 字段映射

    @Test
    fun `E9 招募成功_角色入世+指针+初始设定里程碑_字段逐列对 §4_5`() = runBlocking {
        makeWilling("su_wan")
        val uuid = service.recruit(id("su_wan"), day0)!!
        val c = db.characterDao().getByUuid(uuid)!!
        val def = WorldNativeRoster.bySlug("su_wan")!!
        assertEquals(def.name, c.name); assertEquals(def.gender, c.gender); assertEquals(def.occupation, c.occupation)
        assertEquals("fixed", c.ageModeRaw); assertEquals(def.fixedAge, c.fixedAge)
        assertEquals(def.personality, c.personalityDescription); assertEquals(def.appearance, c.appearanceDescription)
        assertEquals(def.backstory, c.backstory); assertEquals(def.speakingStyle, c.speakingStyle)
        assertEquals(def.catchphrases, c.catchphrases); assertEquals(def.interests, c.initialInterests)
        assertEquals("", c.exampleDialogues); assertEquals("", c.systemPrompt); assertNull(c.avatarPath)
        assertTrue(c.joinedWorld); assertEquals(def.cityId, c.worldHomeCityId)
        assertEquals(day0, c.worldJoinedAt); assertEquals(day0, c.creationDate)
        // 指针写回。
        assertEquals(uuid, db.worldNativeDao().get(id("su_wan"))!!.recruitedCharacterUuid)
        // 「初始设定」里程碑 = 「新朋友」。
        val ms = db.milestoneDao().getForCharacter(uuid).last()
        assertEquals("新朋友", ms.relationshipName); assertEquals("初始设定", ms.reason)
        // recruit 世界事件（城名随种子解析）。
        val ev = db.worldDao().getAllEvents().first { it.kindRaw == WorldRecruitService.RECRUIT_KIND }
        val cityName = WorldNativeRoster.cityNameOf(def, seed)
        assertEquals("「${def.name}」成了你的朋友——你们的缘分从${cityName}开始", ev.summary)
        assertEquals(def.cityId, ev.cityId)
    }

    @Test
    fun `E9 事务中途异常_全回滚无半态`() = runBlocking {
        // spy worldDao 让最后一步 upsertEvent 抛异常 → 角色/指针应全回滚。
        val spyWorld = spyk(db.worldDao())
        coEvery { spyWorld.upsertEvent(any()) } throws RuntimeException("boom")
        val svc = WorldRecruitService(db.worldNativeDao(), db.worldSocialDao(), spyWorld, CharacterRepository(db.characterDao(), db.milestoneDao(), db, residentService(), io.mockk.mockk(relaxed = true)), db, WorldAffinityService(db.worldNativeDao(), db.worldDao()), db.worldUserResidentDao())
        makeWilling("su_wan")
        var threw = false
        try { svc.recruit(id("su_wan"), day0) } catch (e: RuntimeException) { threw = true }
        assertTrue("应抛异常", threw)
        assertEquals("角色回滚", 0, db.characterDao().count())
        assertNull("指针回滚", db.worldNativeDao().get(id("su_wan"))!!.recruitedCharacterUuid)
        assertTrue("边回滚", db.worldSocialDao().getAllEdges().isEmpty())
        assertTrue("事件回滚", db.worldDao().getAllEvents().isEmpty())
    }

    // MARK: - E10 出厂边落地

    @Test
    fun `E10 双方招募_双向边逐字段对 §4_3_rel_origin 事件正确`() = runBlocking {
        makeWilling("su_wan"); makeWilling("lin_moyu")
        val s = service.recruit(id("su_wan"), day0)!!  // 此时 lin_moyu 未招 → su_wan 不落边
        assertTrue(db.worldSocialDao().getAllEdges().isEmpty())
        val l = service.recruit(id("lin_moyu"), day0)!! // lin_moyu 招募 → 落 #1 边（su_wan 已招）
        // #1 su_wan↔lin_moyu：A→B 42/50/护着 · B→A 38/45/敬重 · types 相识·邻里·朋友。
        val ab = db.worldSocialDao().getEdge(s, l)!!
        assertEquals(42, ab.closeness); assertEquals(50, ab.trust); assertEquals("护着", ab.colorRaw)
        assertEquals("相识·邻里·朋友", ab.bond); assertEquals(false, ab.dormant); assertEquals(day0, ab.updatedAt)
        val ba = db.worldSocialDao().getEdge(l, s)!!
        assertEquals(38, ba.closeness); assertEquals(45, ba.trust); assertEquals("敬重", ba.colorRaw)
        // rel_origin 种子事件。
        val pairKey = WorldIds.pairKey(s, l)
        val evUuid = UUID.nameUUIDFromBytes("world:rel:origin:$pairKey".toByteArray()).toString()
        val ev = db.worldSocialDao().eventByUuid(evUuid)!!
        assertEquals(WorldRecruitService.REL_ORIGIN_KIND, ev.kindRaw); assertNull(ev.arcId)
        assertEquals("同一条街的店开了五年，谁忙不过来另一个就搭把手", ev.summary)
    }

    @Test
    fun `E10 对端未招募_零边零关系事件`() = runBlocking {
        makeWilling("su_wan")
        service.recruit(id("su_wan"), day0) // 邻居 lin_moyu/ming_qian/yan_zhen/you_xin 均未招
        assertTrue(db.worldSocialDao().getAllEdges().isEmpty())
        assertTrue(db.worldSocialDao().getAllEvents().isEmpty())
    }

    // MARK: - E11 引擎认账（最关键）

    @Test
    fun `E11 招募二人_引擎次日绝不发 first_meet_rel_origin 不被镜像与记忆消费`() = runBlocking {
        makeWilling("su_wan"); makeWilling("lin_moyu")
        val s = service.recruit(id("su_wan"), day0)!!
        val l = service.recruit(id("lin_moyu"), day0)!!
        val engine = WorldRelationshipEngine(db, WorldRelationshipCompactor(db.worldSocialDao()))
        val world = db.worldDao().getState()!!
        val participants = listOf(db.characterDao().getByUuid(s)!!, db.characterDao().getByUuid(l)!!)
        val recruitEpochDay = Instant.ofEpochMilli(day0).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
        // 结算招募日之后连续 12 天（noon > 招募时刻 → hasEdge=true 全程走有边路径）。
        for (e in (recruitEpochDay + 1)..(recruitEpochDay + 12)) {
            engine.settleDay(world, AppSettings(worldRomanceEnabled = false), participants, SettlementDay(LocalDate.ofEpochDay(e), e, WorldSeeds.derive(seed, "day", e)))
        }
        val pairKey = WorldIds.pairKey(s, l)
        val events = db.worldSocialDao().eventsForPair(pairKey)
        assertTrue("绝不产生 first_meet", events.none { it.kindRaw == WorldRelationshipBeats.FIRST_MEET })
        assertTrue("rel_origin 仍在", events.any { it.kindRaw == WorldRecruitService.REL_ORIGIN_KIND })
        // W5 镜像：rel_origin 不被派生（不在 MIRROR_KINDS）。
        val relOriginUuid = UUID.nameUUIDFromBytes("world:rel:origin:$pairKey".toByteArray()).toString()
        val relOriginMirrorUuid = UUID.nameUUIDFromBytes("world:relw:$relOriginUuid".toByteArray()).toString()
        val mirrors = WorldMirrorDeriver(db.worldSocialDao(), db.characterDao()).deriveSince(0)
        assertTrue("rel_origin 不镜像", mirrors.none { it.uuid == relOriginMirrorUuid })
        assertTrue("原句不入镜像", mirrors.none { it.summary == "同一条街的店开了五年，谁忙不过来另一个就搭把手" })
        // W5 记忆：rel_origin 不被抄写（不在 MEMORY_KINDS）。
        WorldMemoryScribe(db.worldSocialDao(), db.characterDao(), db.worldMemoryDao()).scribeSince(0)
        assertTrue("rel_origin 不抄写", db.worldMemoryDao().getAll().none { it.sourceUuid == relOriginUuid })
    }

    // MARK: - E12 删角→缘分归零→重新结识

    @Test
    fun `E12 删已招募角色_指针清燃料归零_重招新 uuid 边全新无残留`() = runBlocking {
        makeWilling("su_wan"); makeWilling("lin_moyu")
        val s1 = service.recruit(id("su_wan"), day0)!!
        val l = service.recruit(id("lin_moyu"), day0)!!
        assertNotNull(db.worldSocialDao().getEdge(s1, l))
        // 删 su_wan 角色 → 现有清理链：指针清 + 燃料归零 + 边/事件清。
        CharacterRepository(db.characterDao(), db.milestoneDao(), db, residentService(), io.mockk.mockk(relaxed = true)).delete(s1)
        db.worldNativeDao().get(id("su_wan"))!!.let {
            assertNull(it.recruitedCharacterUuid); assertEquals(0, it.narrativeFuel); assertEquals(0, it.giftFuel)
        }
        assertNull(db.characterDao().getByUuid(s1))
        assertNull("旧边随删角清", db.worldSocialDao().getEdge(s1, l))
        // 重新攒眼缘（discovered 仍在·仅补燃料）→ 重招。
        makeWilling("su_wan")
        val s2 = service.recruit(id("su_wan"), day0 + 86_400_000L)!!
        assertTrue("新 uuid", s2 != s1)
        // 新 pairKey → 新出厂边全新落地（lin_moyu 仍在世）。
        val ab = db.worldSocialDao().getEdge(s2, l)!!
        assertEquals(42, ab.closeness); assertEquals("护着", ab.colorRaw)
        val newPairKey = WorldIds.pairKey(s2, l)
        val newEvUuid = UUID.nameUUIDFromBytes("world:rel:origin:$newPairKey".toByteArray()).toString()
        assertNotNull("新 rel_origin 无残留碰撞", db.worldSocialDao().eventByUuid(newEvUuid))
    }

    // MARK: - E13 引荐候选

    @Test
    fun `E13 招募苏晚_候选=声明序邻居_排除已发现已招募_非招募 uuid 空`() = runBlocking {
        makeWilling("su_wan")
        val s = service.recruit(id("su_wan"), day0)!!
        // W12 C5：recruit 第五步已消费首位候选 lin_moyu（discovered）→ 候选起点顺移一位（属性=声明序·排除已发现仍成立）。
        assertEquals(
            listOf("ming_qian", "yan_zhen", "you_xin"),
            affinity.referralCandidates(s).map { it.slug },
        )
        // 再发现 ming_qian → 候选排除之。
        affinity.discover(id("ming_qian"), day0)
        assertEquals(
            listOf("yan_zhen", "you_xin"),
            affinity.referralCandidates(s).map { it.slug },
        )
        // 非招募角色 uuid → 空。
        assertTrue(affinity.referralCandidates("no-such-uuid").isEmpty())
    }

    // MARK: - T2-3 用户自建居民招募（战役 B·E6/E8·图纸 §7）

    /** 建一枚用户居民 + 装入花名册 + 播一枚 discovered 且已过门槛的 state 行；返回 slug。 */
    private fun seedWillingResident(
        slug: String = "resident_test0001",
        name: String = "江晚棠",
        avatar: String? = "/data/av/j.png",
        cityId: String = "city_yunye",
    ) = runBlocking {
        db.worldUserResidentDao().upsert(
            WorldUserResidentEntity(
                slug = slug, name = name, gender = "female", age = 26, cityId = cityId, occupation = "旧书店店主",
                personaBrief = "安静", traitsJson = StringListJson.encode(listOf("温吞")), freeformLore = "旧怀表",
                initialRelationText = "", fuelBias = "balanced", avatarPath = avatar, createdAt = 0L,
            ),
        )
        residentService().loadIntoRoster() // 装花名册（recruit 靠 byNativeId 命中 def）
        db.worldNativeDao().upsert(
            WorldNativeStateEntity(
                nativeId = id(slug), discovered = true, discoveredAt = day0, narrativeFuel = 1000, currentCityId = cityId,
            ),
        )
        slug
    }

    @Test
    fun `T2-3 招募用户居民_五步走通_头像带入_零出厂边_引荐空跳`() = runBlocking {
        val slug = seedWillingResident()
        val uuid = service.recruit(id(slug), day0)!!
        // ① 角色入库·字段对居民 def·头像带入（E8）。
        val c = db.characterDao().getByUuid(uuid)!!
        assertEquals("江晚棠", c.name)
        assertEquals("旧书店店主", c.occupation)
        assertEquals("头像带入（E8）", "/data/av/j.png", c.avatarPath)
        assertTrue(c.joinedWorld)
        assertEquals("city_yunye", c.worldHomeCityId)
        // ② 指针写回。
        assertEquals(uuid, db.worldNativeDao().get(id(slug))!!.recruitedCharacterUuid)
        // ③ 「新朋友」里程碑。
        assertEquals("新朋友", db.milestoneDao().getForCharacter(uuid).last().relationshipName)
        // ④ recruit 世界事件。
        assertTrue(db.worldDao().getAllEvents().any { it.kindRaw == WorldRecruitService.RECRUIT_KIND })
        // ⑤ 零出厂边（用户居民无 FACTORY_EDGES·E6·第 4 步零边落地）。
        assertTrue("用户居民零出厂边", db.worldSocialDao().getAllEdges().isEmpty())
        // ⑥ 引荐空跳（factoryNeighborsOf(residentSlug) 空 → 第 5 步 no-op·不崩不误引荐·E6）。
        assertTrue("引荐空跳", db.worldDao().getAllEvents().none { it.kindRaw == WorldRecruitService.REFERRAL_KIND })
    }

    @Test
    fun `T2-3 无头像居民_avatarPath null_不崩`() = runBlocking {
        val slug = seedWillingResident(avatar = null)
        val uuid = service.recruit(id(slug), day0)!!
        assertNull("无头像 → null 走字母彩圈", db.characterDao().getByUuid(uuid)!!.avatarPath)
    }
}
