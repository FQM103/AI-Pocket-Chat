package com.situ.aichat.ui.story

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.dao.UserStoryTemplateDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.story.StoryGenerationTaskManager
import com.situ.aichat.story.StoryRoleType
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 创建屏「本书专属角色」T2（图纸二 D1·验收 T2-4）：E15 三族角色一次性落库、characterId/isUserRole 三态正确、
 * 描述走既有 normalizedText（空白归 null）、表单增删改（含越界保护）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryCreationViewModelCustomRolesTest {

    private val storyRepo = mockk<StoryRepository>()
    private val characterRepo = mockk<CharacterRepository>()
    private val userProfileDao = mockk<UserProfileDao>()
    private val templateDao = mockk<UserStoryTemplateDao>(relaxed = true)
    private val taskManager = mockk<StoryGenerationTaskManager>(relaxed = true)

    private val storySlot = slot<StoryEntity>()
    private val rolesSlot = slot<List<StoryCharacterRoleEntity>>()

    private val shen = CharacterEntity(uuid = "c-shen", name = "沈之衡", creationDate = 0L)
    private val lin = CharacterEntity(uuid = "c-lin", name = "林晚照", creationDate = 0L)

    private val scope = CoroutineScope(Dispatchers.Main)
    private val jobs = mutableListOf<Job>()

    @Before
    fun setUp() {
        coEvery { characterRepo.getAll() } returns listOf(shen, lin)
        every { userProfileDao.observe() } returns flowOf(UserProfileEntity(nickname = "司徒", bio = "写代码的人"))
        coEvery { storyRepo.insertStory(capture(storySlot)) } just Runs
        coEvery { storyRepo.insertRoles(capture(rolesSlot)) } just Runs
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun vm(): StoryCreationViewModel =
        StoryCreationViewModel(storyRepo, characterRepo, userProfileDao, templateDao, taskManager, SavedStateHandle())

    private fun await(message: String, condition: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        error("等待超时：$message")
    }

    private fun StoryCreationViewModel.loadCharacters() = also {
        jobs += scope.launch { characters.collect {} }
        await("角色加载") { characters.value.size == 2 }
        jobs += scope.launch { userProfile.collect {} }
        await("资料加载") { userProfile.value != null }
    }

    @Test
    fun 三族角色一次落库_两个聊天角色加三个专属加我也参演() {
        // E15：2 聊天角色 + 3 自建 + 我 = 六行；characterId 三态（有值 / null 非用户 / null 用户）
        val vm = vm().loadCharacters()
        vm.update {
            it.copy(
                selectedRoles = mapOf(shen.uuid to StoryRoleType.PROTAGONIST, lin.uuid to StoryRoleType.SUPPORTING),
                roleDescriptions = mapOf(shen.uuid to "冷面上司"),
                customRoles = listOf(
                    CustomRoleDraft("望月", StoryRoleType.SUPPORTING, "过气女团主唱，说话末尾爱带「啦」"),
                    CustomRoleDraft("林俐", StoryRoleType.ANTAGONIST, "  竞争对手  "),
                    CustomRoleDraft("阿舟", StoryRoleType.SUPPORTING, "   "),
                ),
                includeUserRole = true,
                userRoleName = "司徒",
                userRoleType = StoryRoleType.PROTAGONIST,
                userPersonaSource = UserPersonaSource.PROFILE,
            )
        }

        var createdId: String? = null
        vm.createStory { createdId = it }
        await("开书完成") { createdId != null }

        val roles = rolesSlot.captured
        assertEquals("六行一次性落库", 6, roles.size)
        assertEquals(
            setOf("沈之衡", "林晚照", "望月", "林俐", "阿舟", "司徒"),
            roles.map { it.roleName }.toSet(),
        )

        val 望月 = roles.first { it.roleName == "望月" }
        assertNull("自建角色不关联聊天角色", 望月.characterId)
        assertEquals(false, 望月.isUserRole)
        assertEquals("过气女团主唱，说话末尾爱带「啦」", 望月.roleDescription)
        assertEquals(StoryRoleType.SUPPORTING, 望月.roleType)

        val 林俐 = roles.first { it.roleName == "林俐" }
        assertEquals("描述走既有 normalizedText（两侧空白剔掉）", "竞争对手", 林俐.roleDescription)
        assertEquals(StoryRoleType.ANTAGONIST, 林俐.roleType)

        assertNull("纯空白描述归 null（= 无补充人设）", roles.first { it.roleName == "阿舟" }.roleDescription)

        val chat = roles.first { it.roleName == "沈之衡" }
        assertEquals("c-shen", chat.characterId)
        assertEquals(false, chat.isUserRole)

        val me = roles.first { it.roleName == "司徒" }
        assertNull("用户角色不关联聊天角色", me.characterId)
        assertTrue(me.isUserRole)
        assertEquals("走资料页简介", "写代码的人", me.roleDescription)

        assertEquals("恰好一行是用户角色", 1, roles.count { it.isUserRole })
        assertEquals("三行自建（无 characterId 且非用户）", 3, roles.count { it.characterId == null && !it.isUserRole })
    }

    @Test
    fun 只有专属角色也能开书_不选任何聊天角色不参演() {
        val vm = vm().loadCharacters()
        vm.update { it.copy(customRoles = listOf(CustomRoleDraft("望月")), includeUserRole = false) }

        var createdId: String? = null
        vm.createStory { createdId = it }
        await("开书完成") { createdId != null }

        val roles = rolesSlot.captured
        assertEquals(1, roles.size)
        assertEquals("望月", roles.single().roleName)
        assertEquals("默认定位=配角", StoryRoleType.SUPPORTING, roles.single().roleType)
        assertNull(roles.single().roleDescription)
    }

    @Test
    fun 空名字的草稿不落库() {
        val vm = vm().loadCharacters()
        vm.update {
            it.copy(customRoles = listOf(CustomRoleDraft("望月"), CustomRoleDraft("   ", description = "有描述没名字")))
        }

        var createdId: String? = null
        vm.createStory { createdId = it }
        await("开书完成") { createdId != null }

        assertEquals(1, rolesSlot.captured.size)
        assertEquals("望月", rolesSlot.captured.single().roleName)
    }

    @Test
    fun 名字两侧空白_落库前trim() {
        val vm = vm().loadCharacters()
        vm.update { it.copy(customRoles = listOf(CustomRoleDraft("  望月  "))) }

        var createdId: String? = null
        vm.createStory { createdId = it }
        await("开书完成") { createdId != null }

        assertEquals("望月", rolesSlot.captured.single().roleName)
    }

    // ── 表单增删改（UI 与测试共用同一处逻辑）──

    @Test
    fun 表单_增改删各一次() {
        val vm = vm()
        vm.update { it.copy(customRoles = it.customRoles + CustomRoleDraft("望月")) }
        vm.update { it.copy(customRoles = it.customRoles + CustomRoleDraft("林俐")) }
        assertEquals(listOf("望月", "林俐"), vm.form.value.customRoles.map { it.name })

        vm.update { it.withCustomRoleAt(1, CustomRoleDraft("林俐", StoryRoleType.ANTAGONIST, "改过的描述")) }
        assertEquals(StoryRoleType.ANTAGONIST, vm.form.value.customRoles[1].type)
        assertEquals("改过的描述", vm.form.value.customRoles[1].description)
        assertEquals("改一个不影响另一个", "望月", vm.form.value.customRoles[0].name)

        vm.update { it.withoutCustomRoleAt(0) }
        assertEquals(listOf("林俐"), vm.form.value.customRoles.map { it.name })
    }

    @Test
    fun 表单_越界改删原样返回不崩() {
        val vm = vm()
        vm.update { it.copy(customRoles = listOf(CustomRoleDraft("望月"))) }

        vm.update { it.withCustomRoleAt(5, CustomRoleDraft("鬼")) }
        vm.update { it.withoutCustomRoleAt(-1) }
        vm.update { it.withoutCustomRoleAt(3) }

        assertEquals(listOf("望月"), vm.form.value.customRoles.map { it.name })
    }

    @Test
    fun 表单_默认空列表_既有开书流程零影响() {
        val vm = vm().loadCharacters()
        assertTrue(vm.form.value.customRoles.isEmpty())

        vm.update { it.copy(selectedRoles = mapOf(shen.uuid to StoryRoleType.PROTAGONIST)) }
        var createdId: String? = null
        vm.createStory { createdId = it }
        await("开书完成") { createdId != null }

        assertEquals(1, rolesSlot.captured.size)
        assertEquals("沈之衡", rolesSlot.captured.single().roleName)
    }
}
