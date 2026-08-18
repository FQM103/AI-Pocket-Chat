package com.situ.aichat.data.local

import androidx.room.Room
import com.situ.aichat.data.local.dao.StoryDao
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.local.entity.StoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 故事角色摘链 T2（R6#3·Robolectric 真 Room 内存库·契约 FABLE5_STORY_REDESIGN_PROPOSAL.md §9）：
 * 删 AI 角色时 `detachCharacterFromRoles` 只把悬空引用置 null——行保留（roleName/roleDescription
 * 继续驱动生成）、其他角色引用不受扰、跨故事全部生效、对不存在角色幂等。断言从契约反推。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryCharacterRoleDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: StoryDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.storyDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun 摘链只置空目标角色引用_行保留且其他角色不受扰() = runBlocking {
        dao.insertStory(StoryEntity(id = "story-1"))
        dao.insertRoles(
            listOf(
                StoryCharacterRoleEntity(
                    id = "r1", storyId = "story-1", roleName = "林晚",
                    roleDescription = "温柔内敛的医生", characterId = "char-1",
                ),
                StoryCharacterRoleEntity(id = "r2", storyId = "story-1", roleName = "沈决", characterId = "char-2"),
                StoryCharacterRoleEntity(id = "r3", storyId = "story-1", roleName = "路人甲", characterId = null),
            ),
        )

        dao.detachCharacterFromRoles("char-1")

        val roles = dao.getRoles("story-1").associateBy { it.id }
        assertEquals(3, roles.size) // 行只摘链不删除
        assertNull(roles.getValue("r1").characterId)
        assertEquals("林晚", roles.getValue("r1").roleName) // 纯故事角色形态仍可驱动生成
        assertEquals("温柔内敛的医生", roles.getValue("r1").roleDescription)
        assertEquals("char-2", roles.getValue("r2").characterId) // 他人引用不受扰
        assertNull(roles.getValue("r3").characterId)
    }

    @Test
    fun 摘链跨故事全部生效_对不存在角色幂等() = runBlocking {
        dao.insertStory(StoryEntity(id = "s1"))
        dao.insertStory(StoryEntity(id = "s2"))
        dao.insertRoles(
            listOf(
                StoryCharacterRoleEntity(id = "a", storyId = "s1", roleName = "甲", characterId = "char-x"),
                StoryCharacterRoleEntity(id = "b", storyId = "s2", roleName = "乙", characterId = "char-x"),
            ),
        )

        dao.detachCharacterFromRoles("char-x")
        assertNull(dao.getRoles("s1").single().characterId)
        assertNull(dao.getRoles("s2").single().characterId)

        dao.detachCharacterFromRoles("ghost-uuid") // 无匹配行 = 空操作不抛
        assertEquals(1, dao.getRoles("s1").size)
        assertEquals(1, dao.getRoles("s2").size)
    }
}
