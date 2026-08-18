package com.situ.aichat.ui.world.starmap

import android.os.Looper
import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.local.dao.WorldNativeDao
import com.situ.aichat.data.local.dao.WorldSocialDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.local.entity.WorldNativeStateEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.world.WorldIds
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * [StarmapViewModel] flow 接线 T2（W10 图纸 §7·E1/E3/E4/E7/E18·Robolectric + MockK DAO/Repo + MutableStateFlow 喂流）。
 * 派生语义的确定性主证在 [StarmapDeriveTest]；本类证「Room Flow 重发 → uiState 实时刷新 + 选中卡随之更新」的接线。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StarmapViewModelTest {

    private val context = RuntimeEnvironment.getApplication()
    private val socialDao = mockk<WorldSocialDao>(relaxed = true)
    private val charRepo = mockk<CharacterRepository>(relaxed = true)
    private val nativeDao = mockk<WorldNativeDao>(relaxed = true)
    private val worldDao = mockk<WorldDao>(relaxed = true)

    private val edgesFlow = MutableStateFlow<List<WorldRelationshipEntity>>(emptyList())
    private val charsFlow = MutableStateFlow<List<CharacterEntity>>(emptyList())
    private val milestonesFlow = MutableStateFlow<List<MilestoneEntity>>(emptyList())
    private val nativesFlow = MutableStateFlow<List<WorldNativeStateEntity>>(emptyList())

    private lateinit var vm: StarmapViewModel
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private var job: Job? = null

    private fun char(uuid: String, joined: Boolean = true) =
        CharacterEntity(uuid = uuid, name = uuid, creationDate = 0L, joinedWorld = joined, worldHomeCityId = WorldIds.HOME_CITY_ID)

    private fun edge(from: String, to: String, tension: Int = 0, color: String = "投缘", traj: String = "stable") =
        WorldRelationshipEntity(fromId = from, toId = to, typesJson = """["相识"]""", closeness = 30, trust = 30, tension = tension, colorRaw = color, trajectoryRaw = traj, updatedAt = 0L)

    @Before
    fun setUp() {
        every { socialDao.observeEdges() } returns edgesFlow
        every { charRepo.observeAll() } returns charsFlow
        every { charRepo.observeAllMilestones() } returns milestonesFlow
        every { nativeDao.observeAll() } returns nativesFlow
        coEvery { worldDao.getState() } returns WorldStateEntity(seed = 42L, userTimezoneId = "UTC", createdAt = 0L)
        coEvery { socialDao.eventsForPair(any()) } returns emptyList()
        vm = StarmapViewModel(socialDao, charRepo, nativeDao, worldDao, context)
        vm.computeDispatcher = Dispatchers.Unconfined
        job = scope.launch { vm.uiState.collect {} } // 激活 WhileSubscribed
        idle()
    }

    @After
    fun tearDown() {
        job?.cancel(); scope.cancel()
    }

    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()
    private fun await(msg: String, cond: () -> Boolean) {
        repeat(200) { if (cond()) return; idle(); Thread.sleep(2) }
        idle(); if (!cond()) error("等待超时：$msg")
    }

    // MARK: - T2-1 空世界 ready（E1）

    @Test
    fun `T2-1 空世界_ready且图空`() {
        await("ready") { vm.uiState.value.ready }
        assertTrue(vm.uiState.value.graph!!.isEmpty)
    }

    // MARK: - T2-3 脏端点经 flow 丢弃（E3）

    @Test
    fun `T2-3 脏端点边经flow丢弃`() {
        charsFlow.value = listOf(char("a"), char("b"))
        edgesFlow.value = listOf(edge("a", "ghost"), edge("ghost", "a"), edge("a", "b"), edge("b", "a"))
        await("只余 a|b 一条") { vm.uiState.value.graph?.nodes?.size == 2 && vm.uiState.value.graph?.edges?.size == 1 }
        assertEquals(WorldIds.pairKey("a", "b"), vm.uiState.value.graph!!.edges.first().pairKey)
    }

    // MARK: - T2-4 实时（边 Flow 重发 → graph 线型语义变 + 选中卡随之更新·E7）

    @Test
    fun `T2-4 选中边后重发edges_graph与选中卡实时更新`() {
        charsFlow.value = listOf(char("a"), char("b"))
        edgesFlow.value = listOf(edge("a", "b", tension = 0, color = "投缘", traj = "stable"), edge("b", "a"))
        val pk = WorldIds.pairKey("a", "b")
        await("边就绪") { vm.uiState.value.graph?.edges?.size == 1 }
        vm.select(StarmapSelection.Edge(pk))
        await("选中卡=边") { vm.uiState.value.selectionCard is StarmapCard.Edge }
        assertEquals("stable", (vm.uiState.value.selectionCard as StarmapCard.Edge).edge.trajectory)

        // 结算跨入：同对边升温→降温 + tension 46 → 星图与卡实时换语义。
        edgesFlow.value = listOf(edge("a", "b", tension = 46, color = "别扭", traj = "cooling"), edge("b", "a", color = "较劲"))
        await("卡随之变 cooling") { (vm.uiState.value.selectionCard as? StarmapCard.Edge)?.edge?.trajectory == "cooling" }
        val card = vm.uiState.value.selectionCard as StarmapCard.Edge
        assertEquals(46, card.edge.tension)
        assertEquals("cooling", vm.uiState.value.graph!!.edges.first().trajectory) // 图侧同步
    }

    // MARK: - E4 选中消失 → None、卡收起

    @Test
    fun `E4 选中角色随后删除_选中复位None卡收起`() {
        charsFlow.value = listOf(char("a"), char("b"))
        await("节点就绪") { vm.uiState.value.graph?.nodes?.size == 2 }
        vm.select(StarmapSelection.Node("a"))
        await("选中卡=人物") { vm.uiState.value.selectionCard is StarmapCard.Node }
        charsFlow.value = listOf(char("b")) // 删除 a
        await("a 消失") { vm.uiState.value.graph?.nodes?.size == 1 }
        assertEquals(StarmapSelection.None, vm.uiState.value.selection)
        assertNull(vm.uiState.value.selectionCard)
    }

    // MARK: - E18 列表模式切换（保持 + 开列表自动收卡）

    @Test
    fun `E18 列表模式切换_开列表自动收卡`() {
        charsFlow.value = listOf(char("a"), char("b"))
        await("就绪") { vm.uiState.value.ready && vm.uiState.value.graph?.nodes?.size == 2 }
        vm.select(StarmapSelection.You)
        await("你卡在") { vm.uiState.value.selectionCard is StarmapCard.You }
        vm.toggleListMode()
        await("列表开且自动收卡") { vm.uiState.value.listMode && vm.uiState.value.selectionCard == null }
        assertNull("开列表自动收卡", vm.uiState.value.selectionCard)
        vm.toggleListMode()
        await("列表关") { !vm.uiState.value.listMode }
        assertFalse(vm.uiState.value.listMode)
    }
}
