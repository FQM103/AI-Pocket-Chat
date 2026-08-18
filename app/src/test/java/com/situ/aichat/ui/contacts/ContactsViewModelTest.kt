package com.situ.aichat.ui.contacts

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.repository.CharacterDeletionService
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.OfflineMeetingMemoryRepository
import com.situ.aichat.share.ShareTargetCoordinator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ContactsViewModel] 行为测试（图纸一 · §7 T2·MockK 假掉四个依赖 + `runTest` 虚拟时间）。
 *
 * 断言从图纸 §3.1 规格独立反推：防抖窗口 300ms（空词 0ms）、排序 = 「有会话按最近消息倒序在前 / 无会话保持
 * observeAll 序殿后」、搜索空结果 rows 空。用 `StandardTestDispatcher` + `Dispatchers.setMain` 让
 * `viewModelScope` 与 `runTest` 共用同一虚拟时钟；`WhileSubscribed(5000)` 需活跃订阅者 → `backgroundScope`
 * 收流开闸。覆盖 T2-1…T2-5 全五例（T2-3/T2-5 纪事例已随 chunk 2 补齐；R1 复核修正过时注释）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }

    @After fun tearDown() { Dispatchers.resetMain() }

    // ── 造数 ──

    private fun character(uuid: String, name: String, personality: String = "") = CharacterEntity(
        uuid = uuid,
        name = name,
        creationDate = 0L,
        personalityDescription = personality,
    )

    private fun conversation(characterUuid: String, lastMessageAt: Long) = ConversationEntity(
        uuid = "conv-$characterUuid",
        title = characterUuid,
        characterUuid = characterUuid,
        creationDate = 0L,
        lastMessageDate = lastMessageAt,
    )

    private fun milestone(characterUuid: String, name: String, establishedAt: Long, reason: String = "关系调整") =
        MilestoneEntity(
            uuid = "ms-$characterUuid",
            characterUuid = characterUuid,
            relationshipName = name,
            establishedDate = establishedAt,
            reason = reason,
        )

    private fun buildVm(
        characters: List<CharacterEntity>,
        milestones: List<MilestoneEntity> = emptyList(),
        conversations: List<ConversationEntity> = emptyList(),
        meetings: List<OfflineMeetingMemoryEntity> = emptyList(),
    ): ContactsViewModel {
        val characterRepo = mockk<CharacterRepository> {
            every { observeAll() } returns MutableStateFlow(characters)
            every { observeAllMilestones() } returns MutableStateFlow(milestones)
        }
        val conversationRepo = mockk<ConversationRepository> {
            every { observeActive() } returns MutableStateFlow(conversations)
            every { observeCharacterUuidsWithOfflineFallback() } returns MutableStateFlow(emptyList())
        }
        val meetingMemoryRepo = mockk<OfflineMeetingMemoryRepository> {
            every { observeMeetingsSince(any()) } returns MutableStateFlow(meetings)
        }
        val shareCoordinator = mockk<ShareTargetCoordinator> {
            every { pendingPickerText } returns MutableStateFlow(null)
        }
        val deletionService = mockk<CharacterDeletionService>(relaxed = true)
        return ContactsViewModel(characterRepo, conversationRepo, meetingMemoryRepo, deletionService, shareCoordinator)
    }

    // ── T2-1 防抖（E1）──

    @Test fun `T2-1 非空词等300ms生效_空词立即生效`() = runTest(dispatcher) {
        val vm = buildVm(listOf(character("a", "Alice"), character("b", "Bob")))
        backgroundScope.launch { vm.rows.collect {} }
        advanceUntilIdle()
        assertEquals("初始全部可见", 2, vm.rows.value.size)

        vm.setQuery("Ali")
        advanceTimeBy(299)
        assertEquals("299ms 内防抖未生效 → 保持上次结果", 2, vm.rows.value.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("300ms 生效 → 只剩 Alice", 1, vm.rows.value.size)
        assertEquals("a", vm.rows.value[0].character.uuid)

        vm.setQuery("")
        advanceUntilIdle()
        assertEquals("空词立即生效 → 全部恢复", 2, vm.rows.value.size)
    }

    // ── T2-2 排序（E2）──

    @Test fun `T2-2 有会话按最近消息倒序在前_无会话保持observeAll序殿后`() = runTest(dispatcher) {
        // observeAll 输入序 = [none, far, near]（若无排序则原样）；排序后期望 = [near, far, none]。
        val vm = buildVm(
            characters = listOf(character("none", "N"), character("far", "F"), character("near", "R")),
            conversations = listOf(conversation("far", 100L), conversation("near", 200L)),
        )
        backgroundScope.launch { vm.rows.collect {} }
        advanceUntilIdle()
        assertEquals(
            "有会话者按最近消息倒序在前、无会话者殿后",
            listOf("near", "far", "none"),
            vm.rows.value.map { it.character.uuid },
        )
    }

    // ── T2-4 搜索空结果（E9）──

    @Test fun `T2-4 无匹配搜索_rows空且query非空`() = runTest(dispatcher) {
        val vm = buildVm(listOf(character("a", "Alice"), character("b", "Bob")))
        backgroundScope.launch { vm.rows.collect {} }
        advanceUntilIdle()

        vm.setQuery("Zzz")
        advanceUntilIdle()
        assertTrue("无匹配 → rows 空（供 UI 判无结果态）", vm.rows.value.isEmpty())
        assertEquals("raw query 非空（空态分支用它判定）", "Zzz", vm.query.value)
    }

    // ── T2-3 纪事端到端（E3）──

    @Test fun `T2-3 喂14天内里程碑_对应Row纪事非空且值正确`() = runTest(dispatcher) {
        // VM 内部用 System.currentTimeMillis() 判窗（无法注入时钟）→ 相对真实 now 造 1 天前里程碑（PITFALLS 1e）。
        val oneDayAgo = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        val vm = buildVm(
            characters = listOf(character("a", "Alice"), character("b", "Bob")),
            milestones = listOf(milestone("a", "朋友", oneDayAgo)),
        )
        backgroundScope.launch { vm.rows.collect {} }
        advanceUntilIdle()

        val rowA = vm.rows.value.first { it.character.uuid == "a" }
        val ev = rowA.recentEvent
        assertTrue("14 天内里程碑 → 纪事非空且为 Milestone", ev is RecentEvent.Milestone)
        assertEquals("朋友", (ev as RecentEvent.Milestone).name)
        assertEquals("无里程碑角色纪事为 null", null, vm.rows.value.first { it.character.uuid == "b" }.recentEvent)
    }

    // ── T2-5 冷启空数据（E15）──

    @Test fun `T2-5 三源全空_rows回落且不抛`() = runTest(dispatcher) {
        // 零会话/零里程碑/零见面：rows 非空（角色在）、纪事全 null、排序走「无会话保持 observeAll 序」。
        val vm = buildVm(characters = listOf(character("a", "Alice"), character("b", "Bob")))
        backgroundScope.launch { vm.rows.collect {} }
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), vm.rows.value.map { it.character.uuid })
        assertTrue("纪事全回落 null", vm.rows.value.all { it.recentEvent == null })
    }
}
