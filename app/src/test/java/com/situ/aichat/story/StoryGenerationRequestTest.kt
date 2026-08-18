package com.situ.aichat.story

import com.situ.aichat.data.model.MaxOutputLength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `makeGenerationRequest` (11.1e-4) 测试，反推 iOS `makeGenerationRequest` :313-332：
 * system=创作 prompt，user=首章/续章引导语，maxTokens=preferredCreationMaxTokens。
 *
 * temperature 断言口径（卷一 V1·图纸 §7 T2-1）：**不再钉 0.7**——温度由调用方（故事创作温度设置）传入，
 * 本函数的规格只是「原样透传」，故按「传什么得什么」反推（见 [temperature_is_passed_through_verbatim]）。
 *
 * user message 断言口径（2026-07-27 末句要点复述）：末句 = 开场语 + 空行 + 要点清单，故此处只钉
 * **开场语逐字不变**（首行），要点清单本体归 [StoryCreationUserMessageTest]。
 */
class StoryGenerationRequestTest {

    /** 末句开场语 = user message 首行（要点清单在其后，见 [StoryCreationUserMessageTest]）。 */
    private fun openingLineOf(req: StoryGenerationRequest): String = req.messages[1].content!!.lines().first()

    @Test fun first_chapter_user_message_and_fields() {
        val req = makeGenerationRequest(
            prompt = "创作提示", chapterNumber = 1, chapterLengthPreference = 1_500, baseChapterLength = 1_500, isThinkingModel = false, temperature = 1.0,
        )
        assertEquals(2, req.messages.size)
        assertEquals("system", req.messages[0].role)
        assertEquals("创作提示", req.messages[0].content)
        assertEquals("user", req.messages[1].role)
        assertEquals("请开始创作第一章。", openingLineOf(req))
        assertEquals(1.0, req.temperature, 0.0)
        // preferredCreationMaxTokens(1500, false) = 10000（2026-08-06 全档加倍后的基准）
        assertEquals(10_000, req.maxTokens)
    }

    // ── 温度透传（卷一 V1·T2-1）：故事创作温度设置 → request.temperature，函数自身不再持有任何默认值 ──

    @Test fun temperature_is_passed_through_verbatim() {
        fun tempOf(t: Double) = makeGenerationRequest(
            prompt = "p", chapterNumber = 3, chapterLengthPreference = 1_500, baseChapterLength = 1_500, isThinkingModel = false, temperature = t,
        ).temperature
        assertEquals(1.0, tempOf(1.0), 0.0)
        assertEquals(0.3, tempOf(0.3), 0.0)
        assertEquals(0.0, tempOf(0.0), 0.0)
        assertEquals(2.0, tempOf(2.0), 0.0)
    }

    @Test fun next_chapter_user_message() {
        val req = makeGenerationRequest(
            prompt = "p", chapterNumber = 7, chapterLengthPreference = 1_500, baseChapterLength = 1_500, isThinkingModel = false, temperature = 1.0,
        )
        assertEquals("请继续创作下一章。", openingLineOf(req))
    }

    @Test fun thinking_model_triples_max_tokens() {
        val req = makeGenerationRequest(
            prompt = "p", chapterNumber = 1, chapterLengthPreference = 1_500, baseChapterLength = 1_500, isThinkingModel = true, temperature = 1.0,
        )
        assertEquals(30_000, req.maxTokens) // 10000 × 3（2026-08-06 全档加倍后）
    }

    // ── 固定输出档（卷一 V7·T2-1）：用户档仍覆盖章节长度分档，但思考模型的 ×3 余量不再被绕过 ──

    @Test fun user_max_output_length_overrides_chapter_length_and_keeps_thinking_headroom() {
        // 思考模型：档位值 ×3（含思考过程的额度，旧实现在此早退 → 正文被掐断）
        fun tokensOf(len: MaxOutputLength, thinking: Boolean) = makeGenerationRequest(
            prompt = "p", chapterNumber = 2, chapterLengthPreference = 3_000, baseChapterLength = 3_000, isThinkingModel = thinking,
            temperature = 1.0, maxOutputLength = len,
        ).maxTokens
        assertEquals(6_000, tokensOf(MaxOutputLength.SHORT, thinking = true))        // 2000 × 3
        assertEquals(12_000, tokensOf(MaxOutputLength.MEDIUM, thinking = true))      // 4000 × 3
        assertEquals(24_000, tokensOf(MaxOutputLength.LONG, thinking = true))        // 8000 × 3
        assertEquals(48_000, tokensOf(MaxOutputLength.EXTRA_LONG, thinking = true))  // 16000 × 3
        // 非思考模型：档位值原样，一个 token 不变（B7）
        assertEquals(2_000, tokensOf(MaxOutputLength.SHORT, thinking = false))
        assertEquals(4_000, tokensOf(MaxOutputLength.MEDIUM, thinking = false))
        assertEquals(8_000, tokensOf(MaxOutputLength.LONG, thinking = false))
        assertEquals(16_000, tokensOf(MaxOutputLength.EXTRA_LONG, thinking = false))
    }

    @Test fun auto_output_length_keeps_chapter_length_tiers() {
        // AUTO 档不受 V7 影响：仍走章节长度分档（2026-08-06 全档加倍 6000/10000/14000/20000）× 思考 3（B7）
        fun tokensOf(len: Int, thinking: Boolean) = makeGenerationRequest(
            prompt = "p", chapterNumber = 2, chapterLengthPreference = len, baseChapterLength = len, isThinkingModel = thinking,
            temperature = 1.0, maxOutputLength = MaxOutputLength.AUTO,
        ).maxTokens
        assertEquals(6_000, tokensOf(500, thinking = false))
        assertEquals(10_000, tokensOf(1_500, thinking = false))
        assertEquals(14_000, tokensOf(3_000, thinking = false))
        assertEquals(20_000, tokensOf(5_000, thinking = false))  // EXTRA_LONG 档（2026-07-27 加档）
        assertEquals(42_000, tokensOf(3_000, thinking = true))
        assertEquals(60_000, tokensOf(5_000, thinking = true))   // EXTRA_LONG × 思考 3
        // 结局章 ×1.5（7500）不换挡：仍落 else → 20000
        assertEquals(20_000, tokensOf(7_500, thinking = false))
        // LONG 结局（3000×1.5=4500）跨 3500 界落 20000（旧表 8000·复核 #12 裁决=有意改善：
        // 旧 8000 对结局 5400 字上界偏紧；受硬顶服务商由首调 400 自愈 clamp 8192 兜，仅多一次毫秒级往返）
        assertEquals(20_000, tokensOf(4_500, thinking = false))
    }

    // ── 三明治 user message 末端强指令（图纸 §8 chunk 2·T2-3·L2）──

    /** L2 强指令（2026-08-05 M-C2 换文·「任务书」口径与 system 段 M-C1 对齐）。 */
    @Test fun next_chapter_freeform_user_message_is_directive() { // E2
        val req = makeGenerationRequest(
            prompt = "p", chapterNumber = 7, chapterLengthPreference = 1_500, baseChapterLength = 1_500, isThinkingModel = false, temperature = 1.0,
            freeformDirective = "让他们在雪夜里重逢",
        )
        assertEquals(
            "请继续创作下一章。我已亲笔指定本章的剧情走向：「让他们在雪夜里重逢」——这是本章的任务书，必须照此推进；系统提供的大纲与方向提示仅供参考。",
            openingLineOf(req),
        )
        // 旧措辞整体退场（M-C2 之前的 L2）
        val text = req.messages[1].content!!
        assertFalse(text.contains("我已指定本章的剧情走向"))
        assertFalse(text.contains("它的优先级高于系统提供的方向提示、剧情弧线与大纲"))
    }

    /** T2-4：非 freeform 两态的开场语零改（M-C2 只换 freeform 那一支）。 */
    @Test fun T2_4_默认与首章开场语零改() {
        val plain = makeGenerationRequest(
            prompt = "p", chapterNumber = 7, chapterLengthPreference = 1_500, baseChapterLength = 1_500, isThinkingModel = false, temperature = 1.0,
        )
        assertEquals("请继续创作下一章。", openingLineOf(plain))
        val first = makeGenerationRequest(
            prompt = "p", chapterNumber = 1, chapterLengthPreference = 1_500, baseChapterLength = 1_500, isThinkingModel = false, temperature = 1.0,
        )
        assertEquals("请开始创作第一章。", openingLineOf(first))
    }

    @Test fun first_chapter_short_circuits_even_if_freeform_passed() {
        // 首章恒不传 freeform；即便传入，chapterNumber==1 短路，首句逐字不变
        val req = makeGenerationRequest(
            prompt = "p", chapterNumber = 1, chapterLengthPreference = 1_500, baseChapterLength = 1_500, isThinkingModel = false, temperature = 1.0,
            freeformDirective = "不该出现",
        )
        assertEquals("请开始创作第一章。", openingLineOf(req))
        assertFalse("首章短路后走向原文不得泄漏到要点里", req.messages[1].content!!.contains("不该出现"))
    }

    @Test fun long_freeform_directive_passes_through_uncut() { // E8
        val long = "走".repeat(600)
        val req = makeGenerationRequest(
            prompt = "p", chapterNumber = 3, chapterLengthPreference = 1_500, baseChapterLength = 1_500, isThinkingModel = false, temperature = 1.0,
            freeformDirective = long,
        )
        assertTrue(req.messages[1].content!!.contains(long))
    }
}
