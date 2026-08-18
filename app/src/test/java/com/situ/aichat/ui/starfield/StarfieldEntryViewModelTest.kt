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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * [StarfieldEntryViewModel] T2-4（图纸 §7 · §3.4 · 边界 E1）：星数 / 星座段数 / 有无新星三项聚合。
 * 副行文案本身在 UI 层（本处只钉状态口径：0 星 = 空态分支的输入）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StarfieldEntryViewModelTest {

    private val uuid = "c1"
    private val milestoneFlow = MutableStateFlow<List<MilestoneEntity>>(emptyList())
    private val resolvedPromiseFlow = MutableStateFlow<List<PromiseEntity>>(emptyList())
    private var meetings: List<OfflineMeetingMemoryEntity> = emptyList()
    private var lastVisit = 0L

    private fun newVm(): StarfieldEntryViewModel {
        val milestoneDao = mockk<MilestoneDao> {
            every { observeForCharacter(uuid) } returns milestoneFlow
        }
        val promiseRepo = mockk<PromiseRepository> {
            every { observeResolvedByCharacter(uuid) } returns resolvedPromiseFlow
        }
        val meetingRepo = mockk<OfflineMeetingMemoryRepository> {
            coEvery { byCharacter(uuid) } returns meetings
        }
        val store = mockk<StarfieldLastVisitStore>(relaxed = true) {
            coEvery { lastVisited(uuid) } returns lastVisit
        }
        val handle = SavedStateHandle(mapOf(StarfieldViewModel.ARG_CHARACTER_UUID to uuid))
        return StarfieldEntryViewModel(handle, milestoneDao, promiseRepo, meetingRepo, store)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun withState(vm: StarfieldEntryViewModel, block: () -> Unit) {
        val scope = CoroutineScope(Dispatchers.Main + Job())
        scope.launch { vm.state.collect {} }
        idle()
        try {
            block()
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

    /** 本机时区某年某月 1 日中午——分簇按本地时区，构造数据同源避开月界歧义。 */
    private fun monthMillis(year: Int, month: Int, day: Int = 1): Long =
        ZonedDateTime.of(year, month, day, 12, 0, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun milestone(id: String, at: Long) =
        MilestoneEntity(uuid = id, characterUuid = uuid, relationshipName = "朋友", establishedDate = at)

    private fun meeting(id: String, startedAt: Long) = OfflineMeetingMemoryEntity(
        uuid = id, characterUuid = uuid, startedAtMillis = startedAt,
        createdAtMillis = startedAt, updatedAtMillis = startedAt,
    )

    private fun promise(id: String, status: String, resolvedAt: Long?) = PromiseEntity(
        uuid = id, characterUuid = uuid, content = "一起去看海", statusRaw = status,
        resolvedAtMillis = resolvedAt, createdAtMillis = 0, updatedAtMillis = 0,
    )

    @Test
    fun emptyAllSources_zeroCounts_noNova() {
        val vm = newVm()
        withState(vm) {
            await("空态未上屏") { vm.state.value == EntryCardState(starCount = 0, clusterCount = 0, hasNova = false) }
        }
    }

    @Test
    fun countsStars_andClustersByMonth() {
        // 三源共 5 颗，落 3 个月 → 3 段星座。
        milestoneFlow.value = listOf(milestone("m1", monthMillis(2026, 5)))
        meetings = listOf(meeting("f1", monthMillis(2026, 6, 2)), meeting("f2", monthMillis(2026, 6, 20)))
        resolvedPromiseFlow.value = listOf(
            promise("p1", PromiseStatus.FULFILLED, monthMillis(2026, 7, 3)),
            promise("p2", PromiseStatus.FULFILLED, monthMillis(2026, 7, 9)),
            promise("pc", PromiseStatus.CANCELLED, monthMillis(2026, 7, 10)), // 不成星
        )
        val vm = newVm()
        withState(vm) {
            await("计数未上屏") { vm.state.value.starCount == 5 }
            assertEquals(3, vm.state.value.clusterCount)
            assertFalse(vm.state.value.hasNova)
        }
    }

    @Test
    fun legacyMeeting_countsAsOwnCluster() {
        // legacy 行（startedAt=0）自成「往昔」一簇（J7）。
        meetings = listOf(meeting("legacy", 0L), meeting("f1", monthMillis(2026, 7, 3)))
        val vm = newVm()
        withState(vm) {
            await("计数未上屏") { vm.state.value.starCount == 2 }
            assertEquals(2, vm.state.value.clusterCount)
        }
    }

    @Test
    fun novaFlag_setWhenStarNewerThanLastVisit() {
        lastVisit = monthMillis(2026, 6)
        milestoneFlow.value = listOf(milestone("new", monthMillis(2026, 7)))
        val vm = newVm()
        withState(vm) {
            await("hasNova 未上屏") { vm.state.value.hasNova }
            assertEquals(1, vm.state.value.starCount)
        }
    }

    @Test
    fun firstVisit_neverHasNova() {
        lastVisit = 0L
        milestoneFlow.value = listOf(milestone("m1", monthMillis(2026, 7)))
        meetings = listOf(meeting("f1", monthMillis(2026, 7, 20)))
        val vm = newVm()
        withState(vm) {
            await("计数未上屏") { vm.state.value.starCount == 2 }
            assertFalse("首访无 nova（缺 key = 0）", vm.state.value.hasNova)
        }
    }

    @Test
    fun fulfilledWithNullResolvedAt_notCounted() {
        resolvedPromiseFlow.value = listOf(
            promise("dirty", PromiseStatus.FULFILLED, null),
            promise("clean", PromiseStatus.FULFILLED, monthMillis(2026, 7)),
        )
        val vm = newVm()
        withState(vm) {
            await("计数未上屏") { vm.state.value.clusterCount == 1 }
            assertEquals(1, vm.state.value.starCount)
            assertTrue(vm.state.value.starCount == 1)
        }
    }
}
