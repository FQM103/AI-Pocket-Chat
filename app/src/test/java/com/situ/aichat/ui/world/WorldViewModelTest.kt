package com.situ.aichat.ui.world

import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.ui.world.continent.ContinentCamSnapshot
import com.situ.aichat.ui.world.planet.PlanetMath
import com.situ.aichat.ui.world.town.TownCamSnapshot
import com.situ.aichat.world.WorldBootstrap
import com.situ.aichat.world.atlas.WorldAtlas
import com.situ.aichat.world.atlas.WorldRegions
import com.situ.aichat.world.parseDebugScene
import com.situ.aichat.world.notify.WorldNotifyStateStore
import com.situ.aichat.world.stage.UserTravelChip
import com.situ.aichat.world.stage.WorldStageService
import com.situ.aichat.world.stage.WorldTownCast
import com.situ.aichat.world.travel.DepartResult
import com.situ.aichat.world.travel.TravelOption
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [WorldViewModel] T2（W9a 图纸 §5 E10·§7 T2-2·MockK bootstrap + 真 prefs + Robolectric）：
 * markWorldEntered 接线（W8 封顶曲线闭环）+ 静默建世 → UiState（seed/家乡/seedOff 派生）。
 * 深链 navigator request/consume 流（E11）另见 [com.situ.aichat.notification.NotificationNavigatorWorldTest]
 * （VM 不触 navigator·图纸 §11 记录该拆分）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldViewModelTest {

    private companion object {
        const val SEED = 424242L
    }

    private val context = RuntimeEnvironment.getApplication()

    // W9d 新依赖（多数测试不触·relaxed；E13/E18/E19 各自 stub）。
    private val stage = mockk<WorldStageService>(relaxed = true)
    private val worldDao = mockk<WorldDao>(relaxed = true)
    private val travelVm = mockk<WorldTravelSheetViewModel>(relaxed = true)
    private val conv = mockk<ConversationRepository>(relaxed = true)
    private val eavesdrop = mockk<com.situ.aichat.world.live.WorldEavesdropService>(relaxed = true) // W12 C3（多数测试不触）
    private val lore = mockk<com.situ.aichat.world.live.WorldLoreService>(relaxed = true) // W12 C4（多数测试不触）
    private val resident = mockk<com.situ.aichat.world.cast.WorldResidentService>(relaxed = true) // 战役 B（多数测试不触）

    private fun newVm(store: WorldNotifyStateStore): WorldViewModel {
        val bootstrap = mockk<WorldBootstrap>()
        coEvery { bootstrap.ensureCreated(any()) } returns
            WorldStateEntity(seed = SEED, createdAt = 100L) // 默认 userHomeCityId = city_yunye
        return WorldViewModel(bootstrap, store, WorldSceneStrings(context), stage, worldDao, travelVm, conv, eavesdrop, lore, resident, context)
    }

    private fun await(message: String, condition: () -> Boolean) {
        repeat(400) {
            if (condition()) return
            Thread.sleep(5)
        }
        error("等待超时：$message")
    }

    @Test
    fun init_marksWorldEntered() {
        val store = WorldNotifyStateStore(context)
        val before = System.currentTimeMillis()
        newVm(store)
        // E10：进屏即写「上次进世界」时刻（回世界恢复通知封顶曲线）。
        assertTrue("markWorldEntered 已写", store.lastWorldEnteredAt >= before)
    }

    @Test
    fun bootstrap_populatesUiState_withHomeCityAndDerivedSeedOff() {
        val vm = newVm(WorldNotifyStateStore(context))
        await("ui.ready") { vm.ui.value.ready }
        val ui = vm.ui.value
        assertEquals(SEED, ui.seed)
        assertEquals("云野镇", ui.homeCityName)
        assertEquals(600, ui.homeX)
        assertEquals(1300, ui.homeY)
        val expectedSeedOff = PlanetMath.deriveSeedOff(SEED, PlanetMath.homeUnitVector(600, 1300))
        assertEquals(expectedSeedOff, ui.seedOff, 0f)
    }

    // ─────────────────────────── W9b 场景状态机（E12/E19）───────────────────────────

    @Test
    fun sceneStateMachine_enterSelectBack() {
        val vm = newVm(WorldNotifyStateStore(context))
        await("ui.ready") { vm.ui.value.ready }
        assertEquals(WorldScene.Planet, vm.ui.value.scene)
        // enterContinent → 家乡恒为入口。
        vm.enterContinent()
        assertEquals(WorldScene.Continent("yunze"), vm.ui.value.scene)
        // selectRegion 换区。
        vm.selectRegion("mushan")
        assertEquals(WorldScene.Continent("mushan"), vm.ui.value.scene)
        // 同 id selectRegion 忽略（状态实例不变·无新发射）。
        val before = vm.ui.value
        vm.selectRegion("mushan")
        assertSame("同 id 忽略", before, vm.ui.value)
        // backToPlanet。
        vm.backToPlanet()
        assertEquals(WorldScene.Planet, vm.ui.value.scene)
    }

    @Test
    fun regionChips_populatedFromAtlas_homeIsYunze() {
        val vm = newVm(WorldNotifyStateStore(context))
        await("ui.ready") { vm.ui.value.ready }
        val chips = vm.ui.value.regionChips
        assertEquals(10, chips.size)
        assertEquals(WorldRegions.ALL.map { it.id }, chips.map { it.id })
        assertEquals(1, chips.count { it.isHome })
        assertTrue("家乡=云泽", chips.first { it.isHome }.id == "yunze")
    }

    // ─────────────────────────── W10 星图场景机（进/出·恒回 Planet）───────────────────────────

    @Test
    fun starmapScene_enterAndBackToPlanet() {
        val vm = newVm(WorldNotifyStateStore(context))
        await("ui.ready") { vm.ui.value.ready }
        assertEquals(WorldScene.Planet, vm.ui.value.scene)
        vm.enterStarmap()
        assertEquals(WorldScene.StarMap, vm.ui.value.scene)
        vm.backFromStarmap()
        assertEquals(WorldScene.Planet, vm.ui.value.scene)
    }

    // T2-8：debug 直达解析（四合法串→对应 scene·非法/查无 → null 留 Planet·E15）。
    @Test
    fun parseDebugScene_fourLegal_andInvalidToNull() {
        val atlas = WorldAtlas.of(SEED)
        assertEquals(WorldScene.StarMap, parseDebugScene("starmap", atlas))
        assertEquals(WorldScene.Continent("yunze"), parseDebugScene("continent:yunze", atlas))
        assertEquals(WorldScene.Town("city_yunye"), parseDebugScene("town:city_yunye", atlas))
        assertEquals(WorldScene.Interior("city_yunye", "yunye_home"), parseDebugScene("interior:city_yunye:yunye_home", atlas))
        // 非法串 / 查无 city / 查无 region / 多字段 → null。
        assertNull(parseDebugScene("xx", atlas))
        assertNull(parseDebugScene("town:city_nonexistent", atlas))
        assertNull(parseDebugScene("continent:not_a_region", atlas))
        assertNull(parseDebugScene("starmap:extra", atlas))
        assertNull(parseDebugScene("interior:city_yunye", atlas))
    }

    @Test
    fun continentOf_allTenRegions_nonEmpty_andCachedIdempotent() = runBlocking {
        val vm = newVm(WorldNotifyStateStore(context))
        await("ui.ready") { vm.ui.value.ready }
        for (region in WorldRegions.ALL) {
            val data = vm.continentOf(region.id)
            assertTrue("${region.id} 站位非空", data.sites.isNotEmpty())
            assertTrue("${region.id} 至少一奇观", data.sites.any { it.isWonder })
            // 缓存幂等：再取同一实例。
            assertSame("${region.id} 缓存幂等", data, vm.continentOf(region.id))
        }
    }

    @Test
    fun savedPlanetCamera_roundTrips() {
        val vm = newVm(WorldNotifyStateStore(context))
        await("ui.ready") { vm.ui.value.ready }
        assertEquals(null, vm.savedPlanetCamera)
        vm.savePlanetCamera(0.6f, -0.25f, 3.1f)
        assertNotNull(vm.savedPlanetCamera)
        val cam = vm.savedPlanetCamera!!
        assertEquals(0.6f, cam.first, 0f)
        assertEquals(-0.25f, cam.second, 0f)
        assertEquals(3.1f, cam.third, 0f)
    }

    // ─────────────────────────── W9c 小镇场景机（E10/E7）+ townOf 缓存 ───────────────────────────

    @Test
    fun sceneStateMachine_enterTownAndBackToContinent() {
        val vm = newVm(WorldNotifyStateStore(context))
        await("ui.ready") { vm.ui.value.ready }
        vm.enterContinent()
        // 进小镇（云野镇）→ Town 场景（E7 VM 层：enterTown 落 Town·选中门在 ContinentSceneView UI 层）。
        vm.enterTown("city_yunye")
        assertEquals(WorldScene.Town("city_yunye"), vm.ui.value.scene)
        // 回大陆：cityId → regionId 查回（云野镇 = 云泽大区）。
        vm.backToContinent()
        assertEquals(WorldScene.Continent("yunze"), vm.ui.value.scene)
        // 程序城同理（回其所属大区）。
        val gen = WorldAtlas.of(SEED).cities.first { !it.curated }
        vm.enterTown(gen.id)
        assertEquals(WorldScene.Town(gen.id), vm.ui.value.scene)
        vm.backToContinent()
        assertEquals(WorldScene.Continent(gen.regionId), vm.ui.value.scene)
    }

    @Test
    fun savedContinentCamera_savesRestoresAcrossTown_andClearsOnFreshEntry() {
        val vm = newVm(WorldNotifyStateStore(context))
        await("ui.ready") { vm.ui.value.ready }
        assertEquals(null, vm.savedContinentCamera)
        vm.saveContinentCamera(ContinentCamSnapshot(0.7f, 0.6f, 20f, 1f, 0.8f, -1f), tDist = 18f)
        assertNotNull(vm.savedContinentCamera)
        assertEquals(18f, vm.savedContinentCamera!!.second, 0f)
        assertEquals(20f, vm.savedContinentCamera!!.first.dist, 0f)
        // 进小镇 + 回大陆保留快照（供 ContinentSceneView 恢复出发前姿态）。
        vm.enterTown("city_yunye"); vm.backToContinent()
        assertNotNull("回大陆保留快照", vm.savedContinentCamera)
        // 从星球再进大陆（新鲜 intro）清快照。
        vm.enterContinent()
        assertEquals("进大陆清快照", null, vm.savedContinentCamera)
        // 回星球也清。
        vm.saveContinentCamera(ContinentCamSnapshot(0f, 0f, 10f, 0f, 0f, 0f), 10f)
        vm.backToPlanet()
        assertEquals("回星球清快照", null, vm.savedContinentCamera)
    }

    @Test
    fun townOf_cachedIdempotent_curatedHasPlaces_proceduralEmpty() = runBlocking {
        val vm = newVm(WorldNotifyStateStore(context))
        await("ui.ready") { vm.ui.value.ready }
        val yunye = vm.townOf("city_yunye")
        assertEquals("精修城 7 地点", 7, yunye.places.size)
        assertTrue("精修城标志", yunye.curated)
        assertSame("缓存幂等", yunye, vm.townOf("city_yunye"))
        val gen = WorldAtlas.of(SEED).cities.first { !it.curated }
        val genTown = vm.townOf(gen.id)
        assertTrue("程序城无地点", genTown.places.isEmpty())
        assertTrue("程序城非精修", !genTown.curated)
        assertSame(genTown, vm.townOf(gen.id))
    }

    @Test
    fun townChrome_returnsCityNameAndSubtitle() {
        val vm = newVm(WorldNotifyStateStore(context))
        await("ui.ready") { vm.ui.value.ready }
        val (name, subtitle) = vm.townChrome("city_yunye")
        assertEquals("云野镇", name)
        assertEquals("云泽大区 · 渡口水乡", subtitle)
    }

    // ─────────────────────────── W9d 场景机四级 + cast 刷新 + 去聊天（T2-6·E13/E18/E19）───────────────────────────

    @Test
    fun `E13 场景机四级_enterInterior_backToTown_savedTownCamera往返`() {
        val vm = newVm(WorldNotifyStateStore(context))
        await("ui.ready") { vm.ui.value.ready }
        vm.enterContinent(); vm.enterTown("city_yunye")
        // 存小镇相机 + 进室内。
        assertNull(vm.savedTownCamera)
        vm.saveTownCamera(TownCamSnapshot(0.7f, 0.6f, 20f, -1.5f, 0.8f, -1f), tDist = 18f)
        assertNotNull(vm.savedTownCamera)
        assertEquals(18f, vm.savedTownCamera!!.second, 0f)
        vm.enterInterior("city_yunye", "yunye_cafe")
        assertEquals(WorldScene.Interior("city_yunye", "yunye_cafe"), vm.ui.value.scene)
        // 回小镇（Interior → Town）。
        vm.backToTown()
        assertEquals(WorldScene.Town("city_yunye"), vm.ui.value.scene)
    }

    @Test
    fun `E18 cast刷新_start 装载_stop 清空`() {
        coEvery { stage.castOf(any(), any()) } returns WorldTownCast("city_yunye", emptyList(), emptyList(), emptyList())
        val vm = newVm(WorldNotifyStateStore(context))
        await("ui.ready") { vm.ui.value.ready }
        assertNull(vm.cast.value)
        vm.startCastRefresh("city_yunye")
        await("cast 装载") { vm.cast.value != null }
        assertEquals("city_yunye", vm.cast.value!!.cityId)
        vm.stopCastRefresh()
        assertNull("离场即停清空", vm.cast.value)
    }

    @Test
    fun `E19 去聊天_getOrCreateForCharacter幂等_导航回调收到会话uuid`() = runBlocking {
        coEvery { conv.getOrCreateForCharacter("u1", "小南") } returns "conv-xyz"
        val vm = newVm(WorldNotifyStateStore(context))
        await("ui.ready") { vm.ui.value.ready }
        // Unconfined 收集器在 launch 时即订阅（先于 openChat 发射）。
        val got = kotlinx.coroutines.CompletableDeferred<String>()
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { vm.chatNav.collect { got.complete(it) } }
        vm.openChat("u1", "小南")
        val result = kotlinx.coroutines.withTimeout(3000) { got.await() }
        assertEquals("conv-xyz", result)
        coVerify(exactly = 1) { conv.getOrCreateForCharacter("u1", "小南") }
        job.cancel()
    }

    @Test
    fun `E13 onMeetNative转调stage_返回值透传`() = runBlocking {
        coEvery { stage.onMeetNative("native:x", any()) } returns true
        val vm = newVm(WorldNotifyStateStore(context))
        await("ui.ready") { vm.ui.value.ready }
        assertTrue(vm.onMeetNative("native:x"))
        coEvery { stage.onMeetNative("native:y", any()) } returns false
        assertFalse(vm.onMeetNative("native:y"))
    }

    // ─────────────────────────── 🔴-1 presence/chip 场景无关刷新（R1 返工）───────────────────────────

    @Test
    fun `R1 presence init即非空_depart后traveling刷新`() = runBlocking {
        // 初始：在云野·未在途·无在途 chip。
        coEvery { stage.userPresenceCityId(any()) } returns "city_yunye"
        coEvery { stage.isUserTraveling(any()) } returns false
        coEvery { stage.userTravel(any()) } returns null
        val vm = newVm(WorldNotifyStateStore(context))
        await("ui.ready") { vm.ui.value.ready }
        // 不进任何小镇 → presence 已由 bootstrap 后立即刷新填好（大陆城卡「出发」钮据此判定）。
        await("presence 非空") { vm.presence.value != null }
        assertEquals("city_yunye", vm.presence.value!!.cityId)
        assertFalse("初始未在途", vm.presence.value!!.traveling)
        assertNull("初始无在途 chip", vm.travelChip.value)

        // 出发去石港：depart 成功 → refreshPresence 刷 traveling=true + 在途 chip 出现。
        val arriveAt = 100_000L
        coEvery { travelVm.quote("city_taoqiu", any()) } returns
            TravelQuote("city_taoqiu", "石港", "云野镇", 77, emptyList(), null)
        coEvery { travelVm.depart("city_taoqiu", "car", any()) } returns
            DepartResult.Departed(TravelOption("car", 660_000L, 9), arriveAt)
        coEvery { stage.isUserTraveling(any()) } returns true
        coEvery { stage.userTravel(any()) } returns UserTravelChip("city_taoqiu", "石港", arriveAt)

        vm.openTravel("city_taoqiu")
        await("报价就绪") { vm.travelQuote.value != null }
        vm.departTravel("car")
        await("depart 后 traveling 刷新") { vm.presence.value?.traveling == true }
        assertTrue("出发后在途", vm.presence.value!!.traveling)
        assertNotNull("出发后在途 chip 常驻", vm.travelChip.value)
    }

    // 战役 B（O6·§4.3）：送 TA 离开确认流程。

    @Test
    fun `送TA离开_请求置弹窗态_确认调deleteUnrecruited并清态`() {
        coEvery { resident.deleteUnrecruited("resident_ab") } returns true
        val vm = newVm(mockk(relaxed = true))
        await("ui.ready") { vm.ui.value.ready }

        vm.requestDismissResident("native:resident_ab", "江晚棠")
        assertEquals("弹窗态带 name", "江晚棠", vm.pendingDismiss.value?.name)
        assertEquals("native:resident_ab（去前缀取 slug 送 deleteUnrecruited）", "native:resident_ab", vm.pendingDismiss.value?.nativeId)

        vm.confirmDismissResident()
        assertNull("确认即同步清空弹窗态", vm.pendingDismiss.value)
        await("deleteUnrecruited(去前缀 slug) 被调") {
            runCatching { coVerify(exactly = 1) { resident.deleteUnrecruited("resident_ab") } }.isSuccess
        }
    }

    @Test
    fun `取消送离_清弹窗态_不调deleteUnrecruited`() {
        val vm = newVm(mockk(relaxed = true))
        vm.requestDismissResident("native:resident_ab", "江晚棠")
        vm.cancelDismissResident()
        assertNull(vm.pendingDismiss.value)
        coVerify(exactly = 0) { resident.deleteUnrecruited(any()) }
    }
}
