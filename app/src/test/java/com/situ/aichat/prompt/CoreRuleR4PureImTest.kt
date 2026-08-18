package com.situ.aichat.prompt

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 普通聊天「纯 IM 短信体」的**运行时唯一承载** = 核心规则 r4（`pb_core_r4`·每次普通聊天恒注入）的定案锁
 * （2026-07-13 用户拍板：日常聊天纯文字，丰富动作/神态/环境交线下见面模式扛）。
 *
 * 断言从「纯 IM 决定」独立反推（非照搬实现文案），钉两件：
 * 1. r4 保留对括号小动作（（笑）（摸摸头））/旁白的绝对禁令；
 * 2. r4 把正向出口写足——拿掉括号动作这根暖场杆后，模型仍握有一套纯文字暖场招式（语气词/波浪号/emoji/
 *    标点节奏/短消息连发），禁令必配「那就这样做」，防矫枉过正变冷。
 *
 * 注：原「聊天风格守卫 / 线下历史提示」曾另立一版括号动作策略，但它们随「见面去重」后线下消息不再进在线
 * 窗口已成死代码（触发条件 filteredMessages.any{isOfflineMode} 在线恒假），2026-07-13 连同其注入点一并移除；
 * 故纯 IM 策略如今**单源**落在 r4 一处。r4 走资源，默认 locale=en，另有一例切 zh-rCN 锁中文出货文案。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoreRuleR4PureImTest {

    private fun strings() = PromptStrings(RuntimeEnvironment.getApplication())

    @Test fun `r4 keeps the bracket-action ban (en default)`() {
        val core = buildCoreRulesContent(strings(), "Rin", "Yuu")
        assertTrue("仍禁括号小动作示例", core.contains("(pats your head)"))
        assertTrue("仍是绝对禁令", core.contains("never write actions"))
    }

    @Test fun `r4 positive redirect is enriched (en default)`() {
        val core = buildCoreRulesContent(strings(), "Rin", "Yuu")
        assertTrue("正向出口须写足到「短消息连发」", core.contains("back-to-back"))
    }

    @Test @Config(sdk = [34], qualifiers = "zh-rCN")
    fun `r4 chinese ships ban plus enriched positive redirect`() {
        val core = buildCoreRulesContent(strings(), "凛", "小柚")
        assertTrue("中文版仍禁（摸摸头）", core.contains("（摸摸头）"))
        assertTrue("中文版正向出口写足到「拆成几条短消息连发」", core.contains("拆成几条短消息连发"))
    }
}
