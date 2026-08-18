package com.situ.aichat.story

import com.situ.aichat.data.local.dao.WorldBookDao
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.worldbook.WorldBookVectorService
import com.situ.aichat.worldbook.wbBook
import com.situ.aichat.worldbook.wbEntry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 故事×世界书编排 T2（ST5·MockK 假 DAO/向量服务/设置 + 真激活引擎）：
 * 开关关/无角色/无书零开销早退、首章/续章扫描缓冲装配（剥沉浸标签实证 + 尾段截取）、
 * 独立 2000 字预算生效、时效 delay 恒可触发且不落库、四锚点归并顺序、向量查询口径、激活异常吞掉返 null。
 */
class StoryWorldInfoServiceTest {

    private val dao = mockk<WorldBookDao>()
    private val vectorService = mockk<WorldBookVectorService> {
        coEvery { matchedEntryUuids(any(), any(), any()) } returns emptySet()
    }
    private val settingsRepo = mockk<SettingsRepository> {
        // 全局预算故意设很大：证明故事侧 2000 字预算是覆盖参数、不吃全局设置。
        coEvery { getAppSettings() } returns AppSettings(worldInfoBudgetChars = 999_999)
    }
    private val service = StoryWorldInfoService(dao, vectorService, settingsRepo)

    private fun story(
        worldInfoEnabled: Boolean = true,
        worldSetting: String? = null,
        plotDirection: String? = null,
        pendingChapterBeats: String? = null,
    ) = StoryEntity(
        id = "s1", title = "测试书",
        worldInfoEnabled = worldInfoEnabled, worldSetting = worldSetting,
        plotDirection = plotDirection, pendingChapterBeats = pendingChapterBeats,
    )

    private fun role(characterId: String?) =
        StoryCharacterRoleEntity(roleName = "角色", roleType = StoryRoleType.PROTAGONIST, characterId = characterId)

    private fun chapter(content: String, userChoice: String? = null) =
        StoryChapterEntity(chapterNumber = 3, content = content, userChoice = userChoice)

    private fun stubBooksAndEntries(entries: List<com.situ.aichat.data.local.entity.WorldBookEntryEntity>) {
        coEvery { dao.activeBooksForCharacter("c1") } returns listOf(wbBook("b1"))
        coEvery { dao.entriesForBooks(listOf("b1")) } returns entries
    }

    private fun run(story: StoryEntity, roles: List<StoryCharacterRoleEntity>, latest: StoryChapterEntity?) =
        runBlocking { service.buildWorldInfoSection(story, roles, latest) }

    // ── 早退（零注入零开销） ──

    @Test
    fun 开关关_返回null且零查询() {
        assertNull(run(story(worldInfoEnabled = false), listOf(role("c1")), null))
        coVerify(exactly = 0) { dao.activeBooksForCharacter(any()) }
    }

    @Test
    fun 无关联AI角色_返回null且零查询() {
        assertNull(run(story(), listOf(role(null), role("")), null))
        coVerify(exactly = 0) { dao.activeBooksForCharacter(any()) }
    }

    @Test
    fun 无书_返回null() {
        coEvery { dao.activeBooksForCharacter("c1") } returns emptyList()
        assertNull(run(story(worldSetting = "青云宗"), listOf(role("c1")), null))
        coVerify(exactly = 0) { dao.entriesForBooks(any()) }
    }

    // ── 扫描缓冲装配 ──

    @Test
    fun 首章缓冲_worldSetting与plotDirection触发() {
        stubBooksAndEntries(
            listOf(
                wbEntry("e1", keys = listOf("青云宗"), content = "门派设定"),
                wbEntry("e2", keys = listOf("夺嫡"), content = "朝局设定"),
                wbEntry("e3", keys = listOf("北境"), content = "不该出现"),
            ),
        )
        val out = run(story(worldSetting = "青云宗山门", plotDirection = "卷入夺嫡之争"), listOf(role("c1")), null)
        assertTrue(out!!.contains("门派设定"))
        assertTrue(out.contains("朝局设定"))
        assertFalse(out.contains("不该出现"))
    }

    @Test
    fun 续章缓冲_尾段选择与beats触发_沉浸标签已剥离() {
        stubBooksAndEntries(
            listOf(
                wbEntry("e1", keys = listOf("雪原"), content = "尾段命中"),
                wbEntry("e2", keys = listOf("北境"), content = "选择命中"),
                wbEntry("e3", keys = listOf("王都"), content = "方向命中"),
                wbEntry("e4", keys = listOf("snow"), content = "标签内关键词不该命中"),
            ),
        )
        val out = run(
            story(pendingChapterBeats = "王都异动"),
            listOf(role("c1")),
            chapter(content = "她踏入了雪原[weather:snow]，风声呜咽。", userChoice = "去北境"),
        )
        assertTrue(out!!.contains("尾段命中"))
        assertTrue(out.contains("选择命中"))
        assertTrue(out.contains("方向命中"))
        assertFalse("沉浸标签须剥离后再扫描", out.contains("标签内关键词不该命中"))
    }

    @Test
    fun 续章尾段_只取剥标签后末1500字() {
        stubBooksAndEntries(
            listOf(
                wbEntry("e1", keys = listOf("青云宗"), content = "开头关键词不该命中"),
                wbEntry("e2", keys = listOf("剑冢"), content = "结尾命中"),
            ),
        )
        val out = run(story(), listOf(role("c1")), chapter(content = "青云宗" + "废".repeat(1600) + "剑冢"))
        assertTrue(out!!.contains("结尾命中"))
        assertFalse("超出末 1500 字窗口的开头文本不参与扫描", out.contains("开头关键词不该命中"))
    }

    // ── 预算 / 时效 / 归并 ──

    @Test
    fun 预算2000生效_超预算按order裁掉_不吃全局设置() {
        val keepContent = "留".repeat(1500)
        val dropContent = "裁".repeat(1500)
        stubBooksAndEntries(
            listOf(
                wbEntry("e1", constant = true, order = 200, content = keepContent),
                wbEntry("e2", constant = true, order = 100, content = dropContent),
            ),
        )
        val out = run(story(worldSetting = "随便"), listOf(role("c1")), null)
        assertTrue(out!!.contains(keepContent))
        assertFalse("1500+1500 > 2000：低 order 条目应被裁（全局预算 999999 被覆盖）", out.contains(dropContent))
    }

    @Test
    fun 时效不参与_delay条目恒可触发且状态不落库() {
        stubBooksAndEntries(
            listOf(wbEntry("e1", keys = listOf("雪原"), content = "延迟条目照常激活", delay = 500, sticky = 3, cooldown = 2)),
        )
        val out = run(story(), listOf(role("c1")), chapter(content = "她望向雪原深处。"))
        assertTrue("v1 时效不参与：delay=500 不得拦截", out!!.contains("延迟条目照常激活"))
        coVerify(exactly = 0) { dao.upsertTimedState(any()) }
        coVerify(exactly = 0) { dao.timedStatesForConversation(any()) }
    }

    @Test
    fun 四锚点归并顺序_before_after_suffix_atDepth() {
        stubBooksAndEntries(
            listOf(
                wbEntry("e4", constant = true, position = 4, content = "段四"),
                wbEntry("e2", constant = true, position = 2, content = "段三"),
                wbEntry("e1", constant = true, position = 1, content = "段二"),
                wbEntry("e0", constant = true, position = 0, content = "段一"),
            ),
        )
        val out = run(story(worldSetting = "随便"), listOf(role("c1")), null)
        assertEquals("段一\n段二\n段三\n段四", out)
    }

    @Test
    fun 书源去重_多角色并集只查一次条目() {
        coEvery { dao.activeBooksForCharacter("c1") } returns listOf(wbBook("b1"))
        coEvery { dao.activeBooksForCharacter("c2") } returns listOf(wbBook("b1"), wbBook("b2"))
        coEvery { dao.entriesForBooks(listOf("b1", "b2")) } returns
            listOf(wbEntry("e1", constant = true, content = "设定"))
        val out = run(story(worldSetting = "随便"), listOf(role("c1"), role("c2")), null)
        assertEquals("设定", out)
        coVerify(exactly = 1) { dao.entriesForBooks(listOf("b1", "b2")) }
    }

    // ── 向量条目 ──

    @Test
    fun 向量条目_续章查询等于选择加尾段() {
        stubBooksAndEntries(listOf(wbEntry("ev", vectorized = true, content = "语义设定")))
        val query = slot<String>()
        coEvery { vectorService.matchedEntryUuids(any(), capture(query), any()) } returns setOf("ev")

        val out = run(
            story(pendingChapterBeats = "王都异动"),
            listOf(role("c1")),
            chapter(content = "她踏入了雪原。", userChoice = "去北境"),
        )
        assertEquals("语义设定", out)
        assertTrue(query.captured.contains("去北境"))
        assertTrue(query.captured.contains("她踏入了雪原。"))
        assertFalse("向量查询口径 = 用户选择 + 上一章尾段，不含方向提示", query.captured.contains("王都异动"))
    }

    // ── 失败兜底 ──

    @Test
    fun 激活链路异常_返回null不抛() {
        coEvery { dao.activeBooksForCharacter("c1") } returns listOf(wbBook("b1"))
        coEvery { dao.entriesForBooks(any()) } throws RuntimeException("库炸了")
        assertNull(run(story(worldSetting = "青云宗"), listOf(role("c1")), null))
    }

    // ── hasWorldBooks（ST7c·设置页世界观开关行显隐）──

    @Test
    fun hasWorldBooks_无绑定角色_假() = runBlocking {
        // 只有用户角色（characterId=null）→ 无书源 → 隐藏开关行
        assertFalse(service.hasWorldBooks(listOf(role(null))))
    }

    @Test
    fun hasWorldBooks_绑定角色但无书_假() = runBlocking {
        coEvery { dao.activeBooksForCharacter("c1") } returns emptyList()
        assertFalse(service.hasWorldBooks(listOf(role("c1"), role(null))))
    }

    @Test
    fun hasWorldBooks_任一绑定角色有书_真() = runBlocking {
        coEvery { dao.activeBooksForCharacter("c1") } returns emptyList()
        coEvery { dao.activeBooksForCharacter("c2") } returns listOf(wbBook("b1"))
        assertTrue(service.hasWorldBooks(listOf(role("c1"), role("c2"))))
    }
}
