package com.situ.aichat.ui.story

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.ApiFunctionRouter
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.story.StoryGenerationService
import com.situ.aichat.story.StoryGenerationTaskManager
import com.situ.aichat.story.StoryReadingProgressStore
import com.situ.aichat.story.StoryRoleType
import com.situ.aichat.story.StoryWorldInfoService
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 参演角色增删改 T2（图纸二 D1·验收 T2-3）：新建/更新走 insertRoles(REPLACE)、移出走 deleteRole，
 * 两者都必须**重拉列表**（原实现是一次性 flow，加人后不刷新 = 本卷要修的病）；排序复用 sortedStoryRoles。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorySettingsViewModelRolesTest {

    private val repo = mockk<StoryRepository>()
    private val generationService = mockk<StoryGenerationService>()
    private val taskManager = mockk<StoryGenerationTaskManager>(relaxed = true)
    private val worldInfoService = mockk<StoryWorldInfoService>(relaxed = true)
    private val readingProgressStore = mockk<StoryReadingProgressStore>(relaxed = true)
    private val functionRouter = mockk<ApiFunctionRouter>()
    private val apiConfigs = mockk<ApiConfigRepository>()
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)

    private val scope = CoroutineScope(Dispatchers.Main)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun role(id: String, name: String, isUser: Boolean = false, characterId: String? = null) =
        StoryCharacterRoleEntity(
            id = id,
            storyId = "s1",
            roleName = name,
            roleType = StoryRoleType.SUPPORTING,
            roleDescription = null,
            isUserRole = isUser,
            characterId = characterId,
        )

    /** 当前库里的角色（用可变列表模拟真库：saveRole/deleteRole 改它，reload 读它）。 */
    private val stored = mutableListOf<StoryCharacterRoleEntity>()

    private fun vm(): StorySettingsViewModel {
        every { repo.observeStory(any()) } returns flowOf(StoryEntity(id = "s1", genre = "都市"))
        every { settingsRepository.appSettings } returns flowOf(AppSettings())
        every { functionRouter.assignments } returns flowOf(emptyMap())
        every { apiConfigs.observeAll() } returns flowOf(emptyList())
        every { apiConfigs.observeActive() } returns flowOf(null)
        coEvery { repo.getRoles("s1") } answers { stored.toList() }
        coEvery { repo.insertRoles(any()) } answers {
            firstArg<List<StoryCharacterRoleEntity>>().forEach { new ->
                stored.removeAll { it.id == new.id }   // REPLACE 语义：同 PK 覆盖
                stored += new
            }
        }
        coEvery { repo.deleteRole(any()) } answers { stored.removeAll { it.id == firstArg<String>() }; Unit }
        return StorySettingsViewModel(
            SavedStateHandle(mapOf("storyId" to "s1")),
            repo,
            generationService,
            taskManager,
            worldInfoService,
            readingProgressStore,
            mockk(relaxed = true), // StoryArchiver（本组用例不碰归档）
            mockk(relaxed = true), // StoryDeleter（本组用例不碰删除）
            mockk(relaxed = true), // StoryUnlockNotificationScheduler（本组用例不碰闹钟）
            mockk(relaxed = true), // StoryPersonaDrafter（本组用例不碰起草）
            mockk(relaxed = true), // StoryOutlineOrchestrator（本组用例不碰重排）
            functionRouter,
            apiConfigs,
            settingsRepository,
            mockk(relaxed = true),
        )
    }

    private fun await(message: String, condition: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        error("等待超时：$message")
    }

    @Test
    fun 初始化_读库并按既有规则排序_用户角色优先再按名字() {
        stored += listOf(role("r1", "望月"), role("r2", "陈默"), role("r3", "司徒", isUser = true))
        val vm = vm()
        await("角色列表载入") { vm.roles.value.size == 3 }

        assertEquals(listOf("司徒", "陈默", "望月"), vm.roles.value.map { it.roleName })
    }

    @Test
    fun 新建角色_落库后列表即时出现新人() {
        val vm = vm()
        await("初始空列表") { vm.roles.value.isEmpty() }

        vm.saveRole(role("r9", "林俐"))
        await("新角色进列表") { vm.roles.value.any { it.roleName == "林俐" } }

        coVerify(exactly = 1) { repo.insertRoles(listOf(role("r9", "林俐"))) }
        assertEquals(1, vm.roles.value.size)
    }

    @Test
    fun 编辑既有角色_同主键覆盖不新增行() {
        stored += role("r1", "望月")
        val vm = vm()
        await("载入") { vm.roles.value.size == 1 }

        val edited = vm.roles.value.first().copy(roleType = StoryRoleType.ANTAGONIST, roleDescription = "过气女团主唱")
        vm.saveRole(edited)
        await("改动生效") { vm.roles.value.firstOrNull()?.roleDescription == "过气女团主唱" }

        assertEquals("同 PK 覆盖，不是新增一行", 1, vm.roles.value.size)
        assertEquals(StoryRoleType.ANTAGONIST, vm.roles.value.first().roleType)
        assertEquals("r1", vm.roles.value.first().id)
    }

    @Test
    fun 移出角色_只删这一行且列表刷新() {
        stored += listOf(role("r1", "望月"), role("r2", "陈默"))
        val vm = vm()
        await("载入两人") { vm.roles.value.size == 2 }

        vm.deleteRole("r1")
        await("移出后只剩一人") { vm.roles.value.size == 1 }

        coVerify(exactly = 1) { repo.deleteRole("r1") }
        assertEquals("陈默", vm.roles.value.single().roleName)
    }

    @Test
    fun 移出最后一个角色_空态不崩() {
        stored += role("r1", "望月")
        val vm = vm()
        await("载入") { vm.roles.value.size == 1 }

        vm.deleteRole("r1")
        await("列表清空") { vm.roles.value.isEmpty() }
    }

    @Test
    fun 保存失败_置错误且列表不变() {
        stored += role("r1", "望月")
        val vm = vm()
        await("载入") { vm.roles.value.size == 1 }
        coEvery { repo.insertRoles(any()) } throws IllegalStateException("写库失败了")

        vm.saveRole(role("r2", "陈默"))
        await("错误落定") { vm.error.value == "写库失败了" }

        assertEquals(1, vm.roles.value.size)
        assertTrue(vm.roles.value.none { it.roleName == "陈默" })
    }

    @Test
    fun 移出失败_置错误且列表不变() {
        stored += role("r1", "望月")
        val vm = vm()
        await("载入") { vm.roles.value.size == 1 }
        coEvery { repo.deleteRole(any()) } throws IllegalStateException("删除失败了")

        vm.deleteRole("r1")
        await("错误落定") { vm.error.value == "删除失败了" }

        assertEquals(1, vm.roles.value.size)
    }

    @Test
    fun 用户角色行_数据层不做名字与类型的特殊处理_约束在UI侧() {
        // E10 的落点声明：isUserRole 的「只开描述」是 sheet 的权限矩阵（nameEditable/typeEditable=false），
        // VM 侧照常存 —— 这里钉的是「VM 不额外拦截」，防日后有人以为 VM 也在拦。
        stored += role("r1", "司徒", isUser = true)
        val vm = vm()
        await("载入") { vm.roles.value.size == 1 }

        vm.saveRole(vm.roles.value.first().copy(roleDescription = "戴银边眼镜"))
        await("描述已存") { vm.roles.value.first().roleDescription == "戴银边眼镜" }

        assertTrue("用户角色标记不变", vm.roles.value.first().isUserRole)
        assertEquals("司徒", vm.roles.value.first().roleName)
    }

    @Test
    fun 关联聊天角色行_移出只删故事角色行_不碰聊天角色本体() {
        stored += role("r1", "夏晴子", characterId = "ch-uuid-1")
        val vm = vm()
        await("载入") { vm.roles.value.size == 1 }

        vm.deleteRole("r1")
        await("移出") { vm.roles.value.isEmpty() }

        // 只走这一条 DELETE：既不整故事清空（deleteRoles），也不改写别的行（insertRoles）；
        // 聊天角色本体（CharacterEntity）压根不在故事仓库这条路上，天然不受影响。
        coVerify(exactly = 1) { repo.deleteRole("r1") }
        coVerify(exactly = 0) { repo.insertRoles(any()) }
    }
}
