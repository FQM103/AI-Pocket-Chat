package com.situ.aichat.ui.character

import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * E1#0 草稿编解码规格（从行为反推）：进程死亡恢复必须**无损还原编辑中值**——任何字段（含可空生日、
 * 头像路径、双八维滑块、TTS 数值）经 encode→decode 后逐字相等；坏草稿绝不崩（decode→null=丢草稿降级）；
 * 空 JSON 解出全默认（前向兼容：未来加字段后旧草稿仍可恢复）。
 */
class CharacterEditDraftCodecTest {

    private val fullState = CharacterEditState(
        name = "小七",
        gender = "女",
        birthdayMillis = 946684800000L,
        ageModeRaw = AgeMode.FIXED,
        fixedAge = "22",
        occupation = "咖啡师",
        personalityDescription = "温柔但嘴硬",
        appearanceDescription = "栗色短发",
        backstory = "从小在海边长大\n喜欢收集贝壳",
        catchphrases = "「哼，才不是呢」",
        speakingStyle = "短句+语气词",
        exampleDialogues = "用户：早\n小七：早呀～",
        initialInterests = "冲浪, 拉花",
        systemPrompt = "你是小七……",
        voiceIdentifier = "cmn-cn-x-ccc-local",
        remoteVoiceID = "female-qingse",
        ttsEmotionRaw = "happy",
        ttsSpeed = 1.25,
        ttsPitch = -2,
        avatarPath = "avatars/xq.webp",
        personalitySpectrum = PersonalitySpectrum(warmth = 80, humor = 60),
        relationshipQuality = RelationshipQuality(trust = 30),
        relationshipName = "青梅竹马",
        offlineThemeColorHex = "C99A86",
    )

    @Test
    fun roundTrip_isLossless_forFullyPopulatedForm() {
        assertEquals(fullState, CharacterEditDraftCodec.decode(CharacterEditDraftCodec.encode(fullState)))
    }

    @Test
    fun roundTrip_isLossless_forDefaults_includingNullables() {
        val blank = CharacterEditState()
        assertEquals(blank, CharacterEditDraftCodec.decode(CharacterEditDraftCodec.encode(blank)))
    }

    @Test
    fun decode_corruptJson_returnsNull_neverThrows() {
        assertNull(CharacterEditDraftCodec.decode("not-json{{{"))
    }

    @Test
    fun decode_emptyObject_yieldsAllDefaults_forwardCompatible() {
        assertEquals(CharacterEditState(), CharacterEditDraftCodec.decode("{}"))
    }

    @Test
    fun decode_unknownKeys_ignored_backwardCompatible() {
        assertEquals(
            CharacterEditState(name = "A"),
            CharacterEditDraftCodec.decode("""{"name":"A","someFutureField":42}"""),
        )
    }
}
