package com.situ.aichat.prompt

import android.content.res.Configuration
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ChatMessageDto
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * 两语境模型（2026-07-12 图纸 v2·§3-A1/A2/B1/B2·§7 T2-1…T2-11）装配矩阵行为测试：
 * 线下见面换核心规则专版（用户 offlineContent > 内置线下版）、短信腔四件线下退场、moduleScene 二值化
 * （语音/忙碌按在线聊天位）、effectiveScene 场景语义（脏状态回落 / 恢复链路健康线下）。
 * 断言从图纸规格独立反推：核心规则实文（「在 APP 里」/「纯文字聊天软件」/「面对面待在一起」）、模块标题
 * （【聊天格式】/【情绪表达】/【核心规则】）、末尾模式标记（【当前处于线下见面/普通聊天模式】）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class PromptBuilderSceneModeTest {

    // 分割线内部用 ZoneId.systemDefault()——钉死 Asia/Shanghai 保证 T2-3 跨天分割线断言确定性（照 PromptBuilderTimeDividerTest）。
    private val zone = ZoneId.of("Asia/Shanghai")
    private lateinit var originalTz: TimeZone

    @Before
    fun pinTimeZone() {
        originalTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTz)
    }

    private val fixedNow = Instant.ofEpochMilli(1_750_000_000_000)
    private fun strings() = PromptStrings(RuntimeEnvironment.getApplication())
    private fun enStrings(): PromptStrings {
        val base = RuntimeEnvironment.getApplication()
        val cfg = Configuration(base.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        return PromptStrings(base.createConfigurationContext(cfg))
    }

    private fun character() = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)

    private fun plainHistory(): List<MessageEntity> = listOf(
        MessageEntity(messageUUID = "u1", conversationUuid = "conv1", roleRaw = "user", content = "在干嘛", timestamp = fixedNow.toEpochMilli() - 60_000),
        MessageEntity(messageUUID = "a1", conversationUuid = "conv1", roleRaw = "assistant", content = "刚忙完~", timestamp = fixedNow.toEpochMilli() - 30_000),
    )

    private fun offlineHistory(): List<MessageEntity> =
        plainHistory().map { it.copy(isOfflineMode = true, offlineSessionId = "sess1") }

    private fun offlineConv() = ConversationEntity(
        uuid = "conv1", title = "会话", characterUuid = "c1", creationDate = 0L,
        isInOfflineMode = true, currentOfflineSessionId = "sess1",
    )

    /** 脏状态：flag=true 但 sessionId 空白（isOfflineModeHealthy=false）。 */
    private fun dirtyConv() = ConversationEntity(
        uuid = "conv1", title = "会话", characterUuid = "c1", creationDate = 0L,
        isInOfflineMode = true, currentOfflineSessionId = "   ",
    )

    private fun allText(msgs: List<ChatMessageDto>) = msgs.joinToString("\n") { it.content.orEmpty() }
    private fun firstSystem(msgs: List<ChatMessageDto>) = msgs.first { it.role == "system" }.content.orEmpty()

    /** 全 22 系统模块默认，仅 CORE_RULES 按 transform 定制。 */
    private fun jsonWithCore(transform: (PromptModule) -> PromptModule): String =
        PromptModuleService.encodeModules(
            PromptModuleService.defaultModules().map {
                if (it.systemModuleType == SystemModuleType.CORE_RULES) transform(it) else it
            },
        )

    private fun buildOnline(
        appSettings: AppSettings,
        strings: PromptStrings = strings(),
        scene: PromptScene = PromptScene.ONLINE_CHAT,
        history: List<MessageEntity> = plainHistory(),
        conv: ConversationEntity? = null,
    ) = PromptBuilder.buildMessages(
        character = character(), conversation = conv, sortedMessages = history, userProfile = null,
        appSettings = appSettings, strings = strings, now = fixedNow, scene = scene,
    )

    private fun buildOffline(
        appSettings: AppSettings = AppSettings(),
        strings: PromptStrings = strings(),
        scene: PromptScene = PromptScene.OFFLINE_MEETING,
        conv: ConversationEntity = offlineConv(),
    ) = PromptBuilder.buildMessages(
        character = character(), conversation = conv, sortedMessages = offlineHistory(), userProfile = null,
        appSettings = appSettings, strings = strings, now = fixedNow, scene = scene,
    )

    // MARK: - T2-1 普通装配

    @Test
    fun t2_1_online_hasPlainCoreAndSmsModules_noOfflineLeak() {
        // 关掉「主动发起线下见面」邀约守卫（其文案含「线下见面」，与本场景无关）→ 隔离场景切换行为。
        val msgs = buildOnline(AppSettings(characterCanInitiateOfflineMeeting = false))
        val all = allText(msgs)
        assertTrue("核心规则普通版身份句", all.contains("在 APP 里"))
        assertTrue("核心规则普通版禁描写(r4)", all.contains("纯文字聊天软件"))
        assertTrue("短信腔·聊天格式在场", all.contains("【聊天格式】"))
        assertTrue("短信腔·情绪表达在场", all.contains("【情绪表达】"))
        assertFalse("不泄线下核心规则身份句", all.contains("面对面待在一起"))
        assertFalse("不泄沉浸式叙事（线下预设/风格守卫皆未注入）", all.contains("沉浸式叙事"))
        assertFalse("末尾无线下模式标记", all.contains("【当前处于线下见面模式】"))
        // 注：不断言「线下见面」缺席——【约定未来见面】守卫（future_meeting）不受本开关约束恒注入其文案，
        // 与场景切换正交（PromptBuilder :546-547）；线下泄漏由上面「面对面待在一起」精确否定式钉死。
    }

    // MARK: - T2-2 线下装配

    @Test
    fun t2_2_offline_swapsCoreRules_dropsSmsModules() {
        val msgs = buildOffline()
        val all = allText(msgs)
        assertTrue("首条 system 含线下身份句", firstSystem(msgs).contains("面对面待在一起"))
        assertTrue("线下保留 r1", all.contains("记住聊过的细节"))
        assertTrue("线下保留 r3", all.contains("不输出任何系统/审核/政策类元话语"))
        assertTrue("末尾线下模式标记在场", all.contains("【当前处于线下见面模式】"))
        assertFalse("删纯文字禁描写(r4)", all.contains("纯文字聊天软件"))
        assertFalse("删孤立引号禁令(r5)", all.contains("孤立的引号"))
        assertFalse("聊天格式退场", all.contains("【聊天格式】"))
        assertFalse("回复风格退场", all.contains("【回复风格】"))
        assertFalse("情绪表达退场（模块标题）", all.contains("【情绪表达】"))
        // 注：不断言「[mood:」缺席——线下沉浸预设规则 3 明文点名禁 [mood:]（F8·OfflineNarrativePreset:229），
        // 该禁令文案本就含子串「[mood:」；情绪表达模块退场由上面【情绪表达】标题缺席钉死。
    }

    // MARK: - T2-3 脏状态 → 普通装配（V-3）

    @Test
    fun t2_3_dirtyState_fallsBackToOnline_dividerRestored() {
        // 脏状态（flag=true·sessionId 空白）+ 传 OFFLINE_MEETING：isOfflineModeHealthy=false → effectiveScene 回落
        // ONLINE_CHAT、moduleScene=ONLINE_CHAT。用**普通**跨天消息（线下消息在非线下装配会被 PromptBuilderWindow:41
        // 整体剔除，无法留存），跨夜触发时间分割线——这正是 V-3 的判别信号（若误当线下会门控关掉分割线）。
        val crossDay = listOf(
            MessageEntity(messageUUID = "u1", conversationUuid = "conv1", roleRaw = "user", content = "在吗",
                timestamp = sh(2026, 6, 25, 14, 56)),
            MessageEntity(messageUUID = "u2", conversationUuid = "conv1", roleRaw = "user", content = "你看看几点了",
                timestamp = sh(2026, 6, 26, 0, 15)),
        )
        val msgs = PromptBuilder.buildMessages(
            character = character(), conversation = dirtyConv(), sortedMessages = crossDay, userProfile = null,
            appSettings = AppSettings(), strings = strings(), now = shInst(2026, 6, 26, 0, 17),
            scene = PromptScene.OFFLINE_MEETING,
        )
        val all = allText(msgs)
        assertTrue("脏状态按普通版核心规则", all.contains("纯文字聊天软件"))
        assertTrue("脏状态短信腔在场", all.contains("【聊天格式】"))
        assertTrue("V-3 时间分割线恢复注入（effectiveScene=ONLINE_CHAT 门控打开）", all.contains("【时间 · "))
        assertFalse("不用线下核心规则", all.contains("面对面待在一起"))
        assertFalse("末尾非线下模式", all.contains("【当前处于线下见面模式】"))
    }

    // MARK: - T2-4 恢复链路（健康线下 + 传 ONLINE_CHAT）→ 全线下装配（V-4）

    @Test
    fun t2_4_recovery_healthyOfflineWithOnlineScene_fullOffline() {
        val msgs = buildOffline(scene = PromptScene.ONLINE_CHAT) // 健康线下会话 + 硬编码 ONLINE_CHAT
        val all = allText(msgs)
        assertTrue("恢复链路仍走线下核心规则", firstSystem(msgs).contains("面对面待在一起"))
        assertTrue("末尾线下模式标记", all.contains("【当前处于线下见面模式】"))
        assertFalse("短信腔退场", all.contains("【聊天格式】"))
        assertFalse("普通核心规则不出现", all.contains("纯文字聊天软件"))
    }

    // MARK: - T2-5 主 content 自定义：普通用之，线下不受影响（E4）

    @Test
    fun t2_5_customMainContent_onlyOnline_offlineUsesBuiltIn() {
        val json = jsonWithCore { it.copy(content = "自定义主文案XYZ") }
        val online = allText(buildOnline(AppSettings(promptModulesJSON = json)))
        assertTrue("普通聊天用自定义主文案", online.contains("自定义主文案XYZ"))

        val offline = allText(buildOffline(AppSettings(promptModulesJSON = json)))
        assertFalse("线下不含自定义主文案", offline.contains("自定义主文案XYZ"))
        assertTrue("线下用内置线下身份句", offline.contains("面对面待在一起"))
    }

    // MARK: - T2-6 语音装配（scene=VOICE_CALL·非线下）→ 二值化按在线位（B-2）

    @Test
    fun t2_6_voiceCall_binarizesToOnline_keepsSmsModulesAndPlainCore() {
        val all = allText(buildOnline(AppSettings(), scene = PromptScene.VOICE_CALL))
        assertTrue("语音保留聊天格式", all.contains("【聊天格式】"))
        assertTrue("语音保留情绪表达", all.contains("【情绪表达】"))
        assertTrue("语音用核心规则普通版", all.contains("纯文字聊天软件"))
        assertFalse("语音不走线下核心规则", all.contains("面对面待在一起"))
    }

    // MARK: - T2-7 CORE_RULES enabledScenes={ONLINE_CHAT} → 线下无核心规则（过滤先于分流·E5）

    @Test
    fun t2_7_coreRulesScopedOnline_offlineHasNoCoreRules() {
        val json = jsonWithCore { it.copy(enabledScenes = setOf(PromptScene.ONLINE_CHAT)) }
        val offline = allText(buildOffline(AppSettings(promptModulesJSON = json)))
        assertFalse("线下核心规则被开关过滤掉→无标题", offline.contains("【核心规则】"))
        assertFalse("线下也无线下身份句（整模块不注入）", offline.contains("面对面待在一起"))

        val online = allText(buildOnline(AppSettings(promptModulesJSON = json)))
        assertTrue("在线仍注入核心规则", online.contains("【核心规则】"))
    }

    // MARK: - T2-8 en 资源（E11）

    @Test
    fun t2_8_englishOfflineCoreRules() {
        val all = allText(buildOffline(strings = enStrings()))
        assertTrue("英文线下身份句", all.contains("face to face right now"))
    }

    // MARK: - T2-9 offlineContent 非空 → 用用户线下文案 + 宏解析（E13）

    @Test
    fun t2_9_offlineContentUsed_macroResolved() {
        val json = jsonWithCore { it.copy(offlineContent = "线下测试专版{{user}}") }
        val all = allText(buildOffline(AppSettings(promptModulesJSON = json)))
        assertTrue("线下用用户 offlineContent", all.contains("线下测试专版"))
        assertFalse("宏已解析（无残留 {{user}}）", all.contains("{{user}}"))
        assertFalse("不落回内置线下版", all.contains("面对面待在一起"))
    }

    // MARK: - T2-10 offlineContent 空 + content 自定义 → 线下用内置版（不串版·E14）

    @Test
    fun t2_10_emptyOfflineContent_customMain_offlineUsesBuiltIn() {
        val json = jsonWithCore { it.copy(content = "主自定义ABC", offlineContent = "") }
        val all = allText(buildOffline(AppSettings(promptModulesJSON = json)))
        assertTrue("线下落内置专版", all.contains("面对面待在一起"))
        assertFalse("线下不串主自定义", all.contains("主自定义ABC"))
    }

    // MARK: - T2-11 自定义模块场景过滤二值化（E15/V-5 两向钉）

    @Test
    fun t2_11_customModuleSceneBinarization_inVoice() {
        val voiceOnly = PromptModule(
            id = "cust-voice", name = "语音专属", content = "自定义语音标记X", sortOrder = 100,
            position = PromptModulePosition.PREFIX, enabledScenes = setOf(PromptScene.VOICE_CALL),
        )
        val onlineOnly = PromptModule(
            id = "cust-online", name = "在线专属", content = "自定义在线标记Y", sortOrder = 101,
            position = PromptModulePosition.PREFIX, enabledScenes = setOf(PromptScene.ONLINE_CHAT),
        )
        val json = PromptModuleService.encodeModules(PromptModuleService.defaultModules() + listOf(voiceOnly, onlineOnly))
        val all = allText(buildOnline(AppSettings(promptModulesJSON = json), scene = PromptScene.VOICE_CALL))
        assertFalse("仅 VOICE_CALL 的模块二值化后不注入（moduleScene=ONLINE_CHAT）", all.contains("自定义语音标记X"))
        assertTrue("仅 ONLINE_CHAT 的模块语音场景注入", all.contains("自定义在线标记Y"))
    }

    // MARK: - 时间工具（Asia/Shanghai 固定，禁真时钟）

    private fun sh(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long = shInst(y, mo, d, h, mi).toEpochMilli()
    private fun shInst(y: Int, mo: Int, d: Int, h: Int, mi: Int): Instant =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant()
}
