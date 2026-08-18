package com.situ.aichat.story

import com.situ.aichat.data.model.CustomStoryPrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CustomStoryPrompts` tests (P11.1a), reverse-derived from iOS `Models/Story.swift`
 * `CustomStoryPrompts` :4-48: hasAnyValue, composeForCreation priority
 * (user > non-empty override > null), decode null/empty/invalid → null, encode omits nulls.
 */
class CustomStoryPromptsTest {

    @Test fun has_any_value_false_only_when_all_null() {
        assertFalse(CustomStoryPrompts().hasAnyValue)
        assertTrue(CustomStoryPrompts(genreTechniques = "x").hasAnyValue)
        assertTrue(CustomStoryPrompts(writerIdentity = "x").hasAnyValue)
        assertTrue(CustomStoryPrompts(writingRules = "x").hasAnyValue)
    }

    @Test fun compose_user_value_wins() {
        val r = CustomStoryPrompts.composeForCreation(
            userWriterIdentity = "uW", userGenreTechniques = "uG", userWritingRules = "uR",
            overrideWriterIdentity = "oW", overrideGenreTechniques = "oG", overrideWritingRules = "oR",
        )
        assertEquals("uW", r.writerIdentity)
        assertEquals("uG", r.genreTechniques)
        assertEquals("uR", r.writingRules)
    }

    @Test fun compose_falls_back_to_nonempty_override_then_null() {
        val r = CustomStoryPrompts.composeForCreation(
            userWriterIdentity = null, userGenreTechniques = null, userWritingRules = null,
            overrideWriterIdentity = "oW", overrideGenreTechniques = "", overrideWritingRules = "",
        )
        assertEquals("oW", r.writerIdentity)   // 用户空 → 非空 override
        assertEquals(null, r.genreTechniques)  // 用户空 + override 空 → null
        assertEquals(null, r.writingRules)
    }

    @Test fun decode_null_empty_invalid_returns_null() {
        assertEquals(null, CustomStoryPrompts.decode(null))
        assertEquals(null, CustomStoryPrompts.decode(""))
        assertEquals(null, CustomStoryPrompts.decode("not json"))
    }

    @Test fun encode_then_decode_round_trips_and_omits_nulls() {
        val p = CustomStoryPrompts(writerIdentity = "你是大师")
        val json = CustomStoryPrompts.encode(p)
        // encodeDefaults=false → null 字段省略，仅含 writerIdentity。
        assertFalse(json.contains("genreTechniques"))
        assertFalse(json.contains("writingRules"))
        assertFalse(json.contains("pacingPreference"))
        assertEquals(p, CustomStoryPrompts.decode(json))
    }

    // ── 卷三 V2：第四字段 pacingPreference（图纸 §5 E1/E2）──

    @Test fun has_any_value_counts_pacing_preference() {
        assertTrue(CustomStoryPrompts(pacingPreference = "慢热").hasAnyValue)
        assertFalse(CustomStoryPrompts(pacingPreference = null).hasAnyValue)
    }

    @Test fun old_json_without_pacing_decodes_to_null_and_keeps_三旧字段() {
        // 卷二及以前落库的 JSON（只有三个字段），decode 后 pacing = null、三旧字段原样。
        val legacy = """{"genreTechniques":"技法","writerIdentity":"身份","writingRules":"规则"}"""
        val decoded = CustomStoryPrompts.decode(legacy)!!
        assertEquals("技法", decoded.genreTechniques)
        assertEquals("身份", decoded.writerIdentity)
        assertEquals("规则", decoded.writingRules)
        assertEquals(null, decoded.pacingPreference)
    }

    @Test fun old_json_resave_with_pacing_does_not_lose_旧字段() {
        val legacy = """{"genreTechniques":"技法","writerIdentity":"身份","writingRules":"规则"}"""
        val merged = CustomStoryPrompts.decode(legacy)!!.copy(pacingPreference = "快节奏")
        val reDecoded = CustomStoryPrompts.decode(CustomStoryPrompts.encode(merged))!!
        assertEquals(merged, reDecoded)
        assertEquals("技法", reDecoded.genreTechniques)
        assertEquals("快节奏", reDecoded.pacingPreference)
    }

    @Test fun 未来多出的未知键_decode不炸() {
        // ignoreUnknownKeys 双向兼容：更高版本写的键回到旧解码器不应崩。
        val future = """{"writerIdentity":"身份","somethingNew":"x"}"""
        assertEquals("身份", CustomStoryPrompts.decode(future)?.writerIdentity)
    }

    // ── 文字忌口：第五字段 bannedExpressions（图纸 §7 T1-3·E7/E10）──

    @Test fun has_any_value_counts_banned_expressions() {
        // E10：只填忌口、其余四栏全空 → JSON 必须落库，否则用户的覆盖静默丢失
        assertTrue(CustomStoryPrompts(bannedExpressions = "不许写雨").hasAnyValue)
        assertFalse(CustomStoryPrompts(bannedExpressions = null).hasAnyValue)
    }

    @Test fun old_json_without_banned_decodes_to_null_and_keeps_旧四字段() {
        // E7：卷三及以前落库的 JSON（无 bannedExpressions 键）→ decode 得 null = 跟随全局，旧字段原样。
        val legacy = """{"genreTechniques":"技法","writerIdentity":"身份","writingRules":"规则","pacingPreference":"慢热"}"""
        val decoded = CustomStoryPrompts.decode(legacy)!!
        assertEquals("技法", decoded.genreTechniques)
        assertEquals("慢热", decoded.pacingPreference)
        assertEquals(null, decoded.bannedExpressions)
        // 补填忌口后回存，旧四字段一个不丢
        val reDecoded = CustomStoryPrompts.decode(
            CustomStoryPrompts.encode(decoded.copy(bannedExpressions = "本书忌口")),
        )!!
        assertEquals("技法", reDecoded.genreTechniques)
        assertEquals("身份", reDecoded.writerIdentity)
        assertEquals("规则", reDecoded.writingRules)
        assertEquals("慢热", reDecoded.pacingPreference)
        assertEquals("本书忌口", reDecoded.bannedExpressions)
    }

    @Test fun normalized_pacing_trims_clamps_and_nulls_blank() {
        assertEquals("慢热，多写日常", CustomStoryPrompts.normalizedPacing("  慢热，多写日常  "))
        assertEquals(null, CustomStoryPrompts.normalizedPacing(null))
        assertEquals(null, CustomStoryPrompts.normalizedPacing(""))
        assertEquals(null, CustomStoryPrompts.normalizedPacing("   　 "))
        // 钳位 300 字（故事二期 D-8：100→300）：301 字截成 300；恰 300 字原样（±1 精度）。
        assertEquals(300, CustomStoryPrompts.normalizedPacing("节".repeat(301))!!.length)
        assertEquals(300, CustomStoryPrompts.normalizedPacing("节".repeat(300))!!.length)
        assertEquals(299, CustomStoryPrompts.normalizedPacing("节".repeat(299))!!.length)
        // 放宽的证据：老上限那一档（101–300 字）现在必须**原样保留**，不许再被截。
        assertEquals(101, CustomStoryPrompts.normalizedPacing("节".repeat(101))!!.length)
        assertEquals(200, CustomStoryPrompts.normalizedPacing("节".repeat(200))!!.length)
        // 先 trim 再截：前后空白不占额度。
        assertEquals(300, CustomStoryPrompts.normalizedPacing("   " + "节".repeat(300) + "   ")!!.length)
    }

    // ── 故事二期卷一：第八/九/十字段 sceneBeats / tasteProfile / sceneSnapshotEnabled（图纸 §7 T1-2）──

    @Test fun has_any_value_counts_三个新字段() {
        // 只动了新字段、其余全空 → JSON 必须落库，否则用户的本书覆盖/开关静默丢失
        assertTrue(CustomStoryPrompts(sceneBeats = "本书节拍").hasAnyValue)
        assertTrue(CustomStoryPrompts(tasteProfile = "本书画像").hasAnyValue)
        assertTrue(CustomStoryPrompts(sceneSnapshotEnabled = false).hasAnyValue)
        // 「本书关闭」是空串而非 null——同样得留住 JSON，否则关不掉
        assertTrue(CustomStoryPrompts(sceneBeats = "").hasAnyValue)
        assertFalse(CustomStoryPrompts(sceneBeats = null, tasteProfile = null, sceneSnapshotEnabled = null).hasAnyValue)
    }

    @Test fun has_any_prompt_content_收两个内容字段_不收开关() {
        // 内容类（节拍/画像）计入「已填写」；开关不是提示词内容，只动开关那一行不许谎报已填写
        assertTrue(CustomStoryPrompts(sceneBeats = "本书节拍").hasAnyPromptContent)
        assertTrue(CustomStoryPrompts(tasteProfile = "本书画像").hasAnyPromptContent)
        assertFalse(CustomStoryPrompts(sceneSnapshotEnabled = false).hasAnyPromptContent)
    }

    @Test fun effective_scene_snapshot_null与true都算开() {
        assertTrue("老书（无此键）默认开", CustomStoryPrompts().effectiveSceneSnapshot)
        assertTrue(CustomStoryPrompts(sceneSnapshotEnabled = true).effectiveSceneSnapshot)
        assertFalse("只有显式 false 才是关", CustomStoryPrompts(sceneSnapshotEnabled = false).effectiveSceneSnapshot)
    }

    @Test fun old_json_without_三个新字段_decodes_to_null_and_keeps_旧字段() {
        // 卷一之前落库的 JSON（七字段）→ 三新字段 decode 得 null（跟随全局 / 快照默认开），旧字段原样
        val legacy = """{"genreTechniques":"技法","writerIdentity":"身份","writingRules":"规则",""" +
            """"pacingPreference":"慢热","bannedExpressions":"本书忌口","chapterChoicesEnabled":false}"""
        val decoded = CustomStoryPrompts.decode(legacy)!!
        assertEquals(null, decoded.sceneBeats)
        assertEquals(null, decoded.tasteProfile)
        assertEquals(null, decoded.sceneSnapshotEnabled)
        assertTrue("老书的场景快照默认开", decoded.effectiveSceneSnapshot)

        // 补填三新字段后回存，旧六字段一个不丢
        val reDecoded = CustomStoryPrompts.decode(
            CustomStoryPrompts.encode(
                decoded.copy(sceneBeats = "本书节拍", tasteProfile = "本书画像", sceneSnapshotEnabled = false),
            ),
        )!!
        assertEquals("技法", reDecoded.genreTechniques)
        assertEquals("身份", reDecoded.writerIdentity)
        assertEquals("规则", reDecoded.writingRules)
        assertEquals("慢热", reDecoded.pacingPreference)
        assertEquals("本书忌口", reDecoded.bannedExpressions)
        assertEquals(false, reDecoded.chapterChoicesEnabled)
        assertEquals("本书节拍", reDecoded.sceneBeats)
        assertEquals("本书画像", reDecoded.tasteProfile)
        assertEquals(false, reDecoded.sceneSnapshotEnabled)
    }

    @Test fun 空串本书关闭态能活过编解码往返() {
        // ""（本书关闭）与 null（跟随全局）是两态，encodeDefaults=false 下空串是非默认值必须被写出
        val p = CustomStoryPrompts(sceneBeats = "", tasteProfile = "")
        val back = CustomStoryPrompts.decode(CustomStoryPrompts.encode(p))!!
        assertEquals("", back.sceneBeats)
        assertEquals("", back.tasteProfile)
    }
}
