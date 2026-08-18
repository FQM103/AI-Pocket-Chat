package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.StructuredMemory
import com.situ.aichat.data.remote.llm.ChatMessageDto
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 结构化记忆注入·第三人称指名（范围 B·图纸 §7 T-d / 2026-07-14）：结构化记忆**注入**块
 * （[buildCharacterMemoryContent] 第一层·system prompt 内·用户不可见）由旧「你/你们」改为角色名+用户名双名字口径，
 * 与块头「[角色名的记忆]」同调。断言从 §3 映射表 + E8/E9 独立反推。qualifiers=zh-rCN：断言用中文生产文案。
 *
 * 说明：文件名沿用图纸 §2.2/§7 字面（"Voice" 疑为图纸笔误，本测试与语音无关）——见图纸 §11 登记，留复核裁决改名。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class PromptBuilderStructuredMemoryVoiceTest {

    private val fixedNow = Instant.ofEpochMilli(1_750_000_000_000)

    private fun character() = CharacterEntity(uuid = "c1", name = "夏晴子", creationDate = 0L)

    private fun messages() = listOf(
        MessageEntity(messageUUID = "u1", conversationUuid = "c1", roleRaw = "user", content = "在吗", timestamp = 1L),
    )

    private fun allSystemText(
        sm: StructuredMemory,
        profile: UserProfileEntity = UserProfileEntity(nickname = "小明"),
    ): String =
        PromptBuilder.buildMessages(
            character = character(),
            sortedMessages = messages(),
            userProfile = profile,
            appSettings = AppSettings(),
            strings = PromptStrings(RuntimeEnvironment.getApplication()),
            structuredMemory = sm,
            now = fixedNow,
        ).filter { it.role == "system" }.joinToString("\n") { it.content.orEmpty() }

    /** T-d：注入块用角色名+用户名（`夏晴子对小明的印象`），绝不再出现旧第二人称「你对…」；关系要点标题双名字。 */
    @Test
    fun 结构化注入_第三人称双名字_不含第二人称() {
        val sys = allSystemText(
            StructuredMemory(
                nicknameFromChar = "小笨蛋",
                impressionOfUser = "温柔细心",
                comfortStyle = "轻声安慰",
            ),
        )
        assertTrue("印象行用角色名+用户名", sys.contains("夏晴子对小明的印象：温柔细心"))
        assertFalse("绝不再出现旧第二人称「你对…印象」", sys.contains("你对小明的印象"))
        assertTrue("关系要点标题用角色名+用户名", sys.contains("## 夏晴子与小明之间的关系要点"))
        assertFalse("关系要点标题绝不再是旧「你与…」", sys.contains("你与小明之间的关系要点"))
        // 角色称呼用户：`夏晴子称呼小明为「小笨蛋」`（call_user 参序 c,u,称呼）。
        assertTrue("角色称呼用户用双名字", sys.contains("夏晴子称呼小明为「小笨蛋」"))
        // 安慰方式（R1 补齐·comfort=第 10 条·参序 c,u,值）：整块无第二人称尾巴。
        assertTrue("安慰方式行用角色名+用户名", sys.contains("夏晴子通常安慰小明的方式：轻声安慰"))
        assertFalse("绝不再出现旧第二人称「你通常安慰…」", sys.contains("你通常安慰"))
    }

    /** T2-A2（图纸一·A2）：用户人设块城市行用真实用户名（`ctx.resolvedUserName`），不再是裸「用户当前所在城市」。 */
    @Test
    fun 城市行用真实用户名不含裸用户() {
        val sys = allSystemText(
            StructuredMemory(),
            profile = UserProfileEntity(nickname = "小明", cityName = "杭州"),
        )
        assertTrue("城市行用真实用户名", sys.contains("小明当前所在城市：杭州"))
        assertFalse("不再是裸「用户当前所在城市」", sys.contains("用户当前所在城市"))
    }

    /** E9：结构化字段为空的那行本就不 add（`if isNotEmpty`）——未设的字段不得产出其记忆行。 */
    @Test
    fun 空字段不注入对应行() {
        val sys = allSystemText(StructuredMemory(impressionOfUser = "温柔细心"))
        assertTrue("已设字段注入", sys.contains("夏晴子对小明的印象"))
        assertFalse("未设 insideJoke 不产出专属梗行", sys.contains("之间的专属梗"))
        assertFalse("未设 firstConflict 不产出分歧行", sys.contains("的第一次分歧"))
    }
}
