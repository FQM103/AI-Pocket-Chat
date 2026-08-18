package com.situ.aichat.story

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.model.StructuredMemory
import com.situ.aichat.data.repository.CharacterRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * StoryCharacterDataCollector 行为测试——验证刀1 角色原料采集协作者「真的能用」（不止编译过）。
 *
 * 手法：MockK 假掉 CharacterDao + CharacterRepository（返回真 CharacterEntity，让扩展属性/结构化记忆解码真跑）；
 * runBlocking 驱动 suspend 采集。覆盖三方法真实路径：
 *  - collectCharacterData：身份字段逐项映射 + characterId 去重 + 跳空 cid + 跳查不到；
 *  - collectVoiceCharacterData：声音字段映射 + 结构化记忆解码（昵称/梗）+ 关系名取 CharacterRepository + 去重跳空；
 *  - collectProtagonistDynamicState：protagonist 选取（只查主角不碰配角）+ 无主角/空 cid/查不到一律回 (null, null)。
 * 纯只读、不写库、不碰生成主流程。
 */
class StoryCharacterDataCollectorTest {

    private lateinit var characterDao: CharacterDao
    private lateinit var characterRepository: CharacterRepository
    private lateinit var collector: StoryCharacterDataCollector

    @Before
    fun setUp() {
        characterDao = mockk(relaxed = true)
        characterRepository = mockk(relaxed = true)
        collector = StoryCharacterDataCollector(characterDao, characterRepository)
    }

    private fun role(cid: String?, type: String = StoryRoleType.SUPPORTING, name: String = "角色") =
        StoryCharacterRoleEntity(roleName = name, roleType = type, characterId = cid)

    // ---- collectCharacterData：身份原料 ----

    @Test
    fun 身份原料_字段逐项映射() = runBlocking {
        coEvery { characterDao.getByUuid("c1") } returns CharacterEntity(
            uuid = "c1", name = "测试", creationDate = 0L,
            gender = "女", occupation = "画家",
            appearanceDescription = "长发披肩", personalityDescription = "温柔", backstory = "美院毕业",
        )
        val result = collector.collectCharacterData(listOf(role("c1")), nowMillis = 0L)
        val data = result["c1"]!!
        assertEquals("女", data.gender)
        assertEquals("画家", data.occupation)
        assertEquals("长发披肩", data.appearanceDescription)
        assertEquals("温柔", data.personalityDescription)
        assertEquals("美院毕业", data.backstory)
    }

    @Test
    fun 身份原料_按cid去重_跳空cid_跳查不到() = runBlocking {
        coEvery { characterDao.getByUuid("c1") } returns CharacterEntity(uuid = "c1", name = "甲", creationDate = 0L)
        coEvery { characterDao.getByUuid("c2") } returns CharacterEntity(uuid = "c2", name = "乙", creationDate = 0L)
        coEvery { characterDao.getByUuid("missing") } returns null
        val roles = listOf(
            role("c1"), role("c1"),     // 同 cid → 去重
            role("c2"),
            role(null), role(""),       // 空 cid → 跳过
            role("missing"),            // 查不到 → 跳过
        )
        val result = collector.collectCharacterData(roles, nowMillis = 0L)
        assertEquals(setOf("c1", "c2"), result.keys)
        coVerify(exactly = 1) { characterDao.getByUuid("c1") }   // 去重：第二个 c1 角色不再查库
    }

    // ---- collectVoiceCharacterData：声音原料 ----

    @Test
    fun 声音原料_映射字段_解码结构化记忆_关系名取repo() = runBlocking {
        val memJson = StructuredMemory(nicknameFromChar = "小笨蛋", insideJoke = "暗号梗").encode()
        coEvery { characterDao.getByUuid("v1") } returns CharacterEntity(
            uuid = "v1", name = "测试", creationDate = 0L,
            personalityDescription = "活泼", speakingStyle = "俏皮",
            catchphrases = "嘿嘿", exampleDialogues = "你好呀", systemPrompt = "你是甜妹",
            structuredMemoryJSON = memJson,
        )
        coEvery { characterRepository.currentRelationship("v1") } returns "恋人"
        val data = collector.collectVoiceCharacterData(listOf(role("v1")))["v1"]!!
        assertEquals("活泼", data.personalityDescription)
        assertEquals("俏皮", data.speakingStyle)
        assertEquals("嘿嘿", data.catchphrases)
        assertEquals("你好呀", data.exampleDialogues)
        assertEquals("你是甜妹", data.systemPrompt)
        assertEquals("小笨蛋", data.nicknameFromChar)   // 结构化记忆解码
        assertEquals("暗号梗", data.insideJoke)
        assertEquals("恋人", data.currentRelationship)   // 取自 CharacterRepository.currentRelationship
    }

    @Test
    fun 声音原料_按cid去重_跳空cid() = runBlocking {
        coEvery { characterDao.getByUuid("v1") } returns CharacterEntity(uuid = "v1", name = "甲", creationDate = 0L)
        val result = collector.collectVoiceCharacterData(listOf(role("v1"), role("v1"), role(null)))
        assertEquals(setOf("v1"), result.keys)
        coVerify(exactly = 1) { characterDao.getByUuid("v1") }
    }

    // ---- collectProtagonistDynamicState：主角动态态 ----

    @Test
    fun 主角动态态_选中protagonist而非配角() = runBlocking {
        coEvery { characterDao.getByUuid("p1") } returns CharacterEntity(uuid = "p1", name = "主角", creationDate = 0L)
        val roles = listOf(
            role("s1", type = StoryRoleType.SUPPORTING, name = "配角"),
            role("p1", type = StoryRoleType.PROTAGONIST, name = "主角"),
        )
        val (spectrum, quality) = collector.collectProtagonistDynamicState(roles)
        assertNotNull(spectrum)
        assertNotNull(quality)
        coVerify(exactly = 1) { characterDao.getByUuid("p1") }   // 只查主角
        coVerify(exactly = 0) { characterDao.getByUuid("s1") }   // 不碰配角
    }

    @Test
    fun 主角动态态_无主角回null对() = runBlocking {
        val (spectrum, quality) = collector.collectProtagonistDynamicState(
            listOf(role("s1", type = StoryRoleType.SUPPORTING)),
        )
        assertNull(spectrum)
        assertNull(quality)
    }

    @Test
    fun 主角动态态_主角cid为空回null对() = runBlocking {
        val (spectrum, quality) = collector.collectProtagonistDynamicState(
            listOf(role(null, type = StoryRoleType.PROTAGONIST)),
        )
        assertNull(spectrum)
        assertNull(quality)
    }

    @Test
    fun 主角动态态_主角查不到回null对() = runBlocking {
        coEvery { characterDao.getByUuid("p1") } returns null
        val (spectrum, quality) = collector.collectProtagonistDynamicState(
            listOf(role("p1", type = StoryRoleType.PROTAGONIST)),
        )
        assertNull(spectrum)
        assertNull(quality)
    }
}
