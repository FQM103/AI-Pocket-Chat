package com.situ.aichat.ui.starfield

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.data.local.dao.MilestoneDao
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.data.local.entity.PromiseStatus
import com.situ.aichat.data.repository.OfflineMeetingMemoryRepository
import com.situ.aichat.data.repository.PromiseRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [StarfieldViewModel] T2（图纸 2026-07-16-记忆星空 §7 T2-1…T2-3 · 边界 E1/E5/E6/E7/E8）：
 * 三源合并计数 · CANCELLED/脏数据不成星 · nova 与流星判定 · markVisited 写入 · 选中态按 id 解析。
 *
 * MockK 假三源 + Store；Robolectric 驱动 `WhileSubscribed` 开闸（同 `PromiseLedgerViewModelTest` 惯例）。
 * 断言口径从图纸 §3.1/§3.3 规格独立反推。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StarfieldViewModelTest {

    private val uuid = "c1"
    private val milestoneFlow = MutableStateFlow<List<MilestoneEntity>>(emptyList())
    private val resolvedPromiseFlow = MutableStateFlow<List<PromiseEntity>>(emptyList())
    private var meetings: List<OfflineMeetingMemoryEntity> = emptyList()
    private var lastVisit = 0L
    private val store = mockk<StarfieldLastVisitStore>(relaxed = true)

    private fun newVm(): StarfieldViewModel {
        val milestoneDao = mockk<MilestoneDao> {
            every { observeForCharacter(uuid) } returns milestoneFlow
        }
        val promiseRepo = mockk<PromiseRepository> {
            every { observeResolvedByCharacter(uuid) } returns resolvedPromiseFlow
        }
        val meetingRepo = mockk<OfflineMeetingMemoryRepository> {
            coEvery { byCharacter(uuid) } returns meetings
        }
        coEvery { store.lastVisited(uuid) } returns lastVisit
        val handle = SavedStateHandle(mapOf(StarfieldViewModel.ARG_CHARACTER_UUID to uuid))
        return StarfieldViewModel(handle, milestoneDao, promiseRepo, meetingRepo, store)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** 订阅 state（WhileSubscribed 开闸）+ 回灌视口，等首个非 loading 态后跑 [block]。 */
    private fun withState(vm: StarfieldViewModel, block: (StarfieldUiState) -> Unit) {
        val scope = CoroutineScope(Dispatchers.Main + Job())
        scope.launch { vm.state.collect {} }
        idle()
        vm.onViewportChanged(VIEWPORT_W, VIEWPORT_H)
        try {
            await("state 未离开 loading") { !vm.state.value.loading }
            block(vm.state.value)
        } finally {
            scope.coroutineContext[Job]?.cancel()
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        repeat(400) {
            idle()
            if (condition()) return
            Thread.sleep(5)
        }
        error("等待超时：$message")
    }

    private fun milestone(id: String, at: Long, reason: String = "初始设定") = MilestoneEntity(
        uuid = id, characterUuid = uuid, relationshipName = "朋友", establishedDate = at, reason = reason,
    )

    private fun meeting(id: String, startedAt: Long, messageCount: Int = 0, activity: String = "看电影") =
        OfflineMeetingMemoryEntity(
            uuid = id, characterUuid = uuid, startedAtMillis = startedAt, activity = activity,
            messageCount = messageCount, createdAtMillis = startedAt, updatedAtMillis = startedAt,
        )

    private fun promise(id: String, status: String, resolvedAt: Long?) = PromiseEntity(
        uuid = id, characterUuid = uuid, content = "一起去看海", statusRaw = status,
        resolvedAtMillis = resolvedAt, createdAtMillis = 0, updatedAtMillis = 0,
    )

    private fun StarfieldUiState.allStars() = clusters.flatMap { it.stars }

    // ── T2-1 三源合并 / 全空（E1）──────────────────────────────────────────────

    @Test
    fun threeSourcesMerge_starCountIsSum() {
        milestoneFlow.value = listOf(milestone("m1", T0))
        meetings = listOf(meeting("f1", T0 + 100_000_000L), meeting("f2", T0 + 200_000_000L))
        resolvedPromiseFlow.value = listOf(promise("p1", PromiseStatus.FULFILLED, T0 + 300_000_000L))

        withState(newVm()) { state ->
            assertEquals(4, state.starCount)
            assertEquals(
                setOf(StarType.MILESTONE, StarType.MEETING, StarType.PROMISE),
                state.allStars().map { it.node.type }.toSet(),
            )
        }
    }

    @Test
    fun allSourcesEmpty_starCountZero_noClusters() {
        withState(newVm()) { state ->
            assertEquals(0, state.starCount)
            assertTrue(state.clusters.isEmpty())
            assertFalse(state.showMeteor)
            // 星 0 颗时画布仍等于视口高（夜幕+尘星+月照常·§4.10 空态）。
            assertEquals(VIEWPORT_H, state.canvasHeightDp, 0.01f)
        }
    }

    @Test
    fun loneMilestone_getsFullWeight() {
        // 空态唯一星恒 4.4（§4.3）——默认 reason「初始设定」按长度公式只有 3.09。
        milestoneFlow.value = listOf(milestone("m1", T0))
        withState(newVm()) { state ->
            assertEquals(StarNodes.LONE_MILESTONE_WEIGHT, state.allStars().single().node.weight, 0.0001f)
        }
    }

    // ── T2-2 约定过滤（E5/E6）────────────────────────────────────────────────

    @Test
    fun cancelledPromise_neverBecomesStar() {
        resolvedPromiseFlow.value = listOf(
            promise("fulfilled", PromiseStatus.FULFILLED, T0),
            promise("cancelled", PromiseStatus.CANCELLED, T0 + 100_000_000L),
        )
        withState(newVm()) { state ->
            assertEquals(1, state.starCount)
            assertEquals("fulfilled", state.allStars().single().node.id)
        }
    }

    @Test
    fun fulfilledWithNullResolvedAt_isSkipped_notCrash() {
        resolvedPromiseFlow.value = listOf(
            promise("dirty", PromiseStatus.FULFILLED, null),
            promise("clean", PromiseStatus.FULFILLED, T0),
        )
        withState(newVm()) { state ->
            assertEquals(1, state.starCount)
            assertEquals("clean", state.allStars().single().node.id)
        }
    }

    // ── T2-3 nova / 流星 / markVisited（E7/E8）──────────────────────────────

    @Test
    fun firstVisit_noNova_noMeteor() {
        lastVisit = 0L // 无 key = 首访
        milestoneFlow.value = listOf(milestone("m1", T0))
        meetings = listOf(meeting("f1", T0 + 100_000_000L))
        withState(newVm()) { state ->
            assertFalse("首访全场无流星", state.showMeteor)
            assertTrue("首访全场无 nova", state.allStars().none { it.node.nova })
        }
    }

    @Test
    fun secondVisit_newMilestone_novaAndMeteor() {
        lastVisit = T0
        milestoneFlow.value = listOf(
            milestone("old", lastVisit - 1), // 上次访问前 → 旧星
            milestone("new", lastVisit + 1), // 上次访问后 → 新星
        )
        withState(newVm()) { state ->
            val stars = state.allStars().associateBy { it.node.id }
            assertFalse(stars.getValue("old").node.nova)
            assertTrue(stars.getValue("new").node.nova)
            assertTrue("新里程碑 → 播一次流星", state.showMeteor)
        }
    }

    @Test
    fun secondVisit_newMeetingButNoNewMilestone_novaWithoutMeteor() {
        lastVisit = T0
        milestoneFlow.value = listOf(milestone("old", lastVisit - 1))
        meetings = listOf(meeting("newMeeting", lastVisit + 1))
        withState(newVm()) { state ->
            assertTrue(state.allStars().single { it.node.id == "newMeeting" }.node.nova)
            assertFalse("流星只认里程碑", state.showMeteor)
        }
    }

    @Test
    fun onMeteorPlayed_clearsFlag() {
        lastVisit = T0
        milestoneFlow.value = listOf(milestone("new", lastVisit + 1))
        val vm = newVm()
        withState(vm) { state ->
            assertTrue(state.showMeteor)
            vm.onMeteorPlayed()
            await("流星标志未回落") { !vm.state.value.showMeteor }
        }
    }

    @Test
    fun markVisited_writesNow() {
        val vm = newVm()
        vm.markVisited()
        idle()
        Thread.sleep(20)
        idle()
        coVerify { store.markVisited(uuid, any()) }
    }

    // ── 选中态（只存 id·渲染时按 id 从当前流解析·PITFALLS 1b）───────────────

    @Test
    fun selection_resolvesById_andClears() {
        milestoneFlow.value = listOf(milestone("m1", T0))
        val vm = newVm()
        withState(vm) { state ->
            val node = state.allStars().single().node
            vm.onStarSelected(node)
            await("选中态未上屏") { vm.state.value.selected?.id == "m1" }

            // 源里没了 → 选中态自然收起（不残留对象快照）。
            milestoneFlow.value = emptyList()
            await("源消失后选中态未收起") { vm.state.value.selected == null }

            milestoneFlow.value = listOf(milestone("m1", T0))
            vm.onStarSelected(node)
            await("重新选中失败") { vm.state.value.selected != null }
            vm.onStarSelected(null)
            await("空点未清选中") { vm.state.value.selected == null }
            assertNull(vm.state.value.selected)
        }
    }

    private companion object {
        const val T0 = 1_700_000_000_000L
        const val VIEWPORT_W = 360f
        const val VIEWPORT_H = 800f
    }
}
