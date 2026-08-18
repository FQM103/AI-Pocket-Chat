package com.situ.aichat.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 成长数据类 / 编解码单测。重点：关系质感**初始值非全 0**、缺字段回落默认（= iOS Codable decodeIfPresent）、
 * `"fun"` 键映射 funValue、setValue clamp、GrowthEventType.fromRaw、JSON 往返一致。
 */
class CharacterGrowthTypesTest {

    // MARK: - 性格光谱（全初始 50）

    @Test fun personalityDecodeDefaultsAll50() {
        val p = GrowthJson.decodePersonalitySpectrum("")
        assertEquals(50, p.extroversion)
        assertEquals(50, p.openness)
        assertEquals(listOf(50, 50, 50, 50, 50, 50, 50, 50), p.values)
    }

    @Test fun personalitySetValueClampsAndIndexes() {
        assertEquals(100, PersonalitySpectrum().setValue(0, 150).extroversion) // 高位 clamp
        assertEquals(0, PersonalitySpectrum().setValue(0, -5).extroversion)    // 低位 clamp
        assertEquals(30, PersonalitySpectrum().setValue(7, 30).openness)        // index 7 = openness
    }

    @Test fun personalityValuesOrder() {
        val p = PersonalitySpectrum(extroversion = 1, emotionality = 2, adventurousness = 3, warmth = 4, humor = 5, independence = 6, curiosity = 7, openness = 8)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), p.values)
    }

    // MARK: - 关系质感（初始非全 0；"fun" 键）

    @Test fun relationshipDecodeDefaultsMatchIosInitial() {
        val r = GrowthJson.decodeRelationshipQuality("")
        assertEquals(10, r.familiarity)
        assertEquals(20, r.trust)
        assertEquals(10, r.closeness)
        assertEquals(10, r.rapport)
        assertEquals(35, r.respect)
        assertEquals(20, r.funValue)
        assertEquals(5, r.tension)
        assertEquals(5, r.attachment)
    }

    @Test fun relationshipPartialDecodeKeepsDefaultsAndMapsFunKey() {
        // 缺字段回落默认；"fun" JSON 键 → funValue（@SerialName）
        val r = GrowthJson.decodeRelationshipQuality("""{"trust":50,"fun":80}""")
        assertEquals(50, r.trust)
        assertEquals(80, r.funValue)
        assertEquals(10, r.familiarity) // 默认保留
        assertEquals(35, r.respect)     // 默认保留
    }

    @Test fun relationshipSetValueIndexes() {
        assertEquals(90, RelationshipQuality().setValue(5, 90).funValue) // index 5 = fun
        assertEquals(100, RelationshipQuality().setValue(7, 200).attachment) // index 7 = attachment, clamp
        assertEquals(listOf(10, 20, 10, 10, 35, 20, 5, 5), RelationshipQuality().values)
    }

    @Test fun relationshipRoundTrip() {
        val q = RelationshipQuality(familiarity = 42, trust = 71, funValue = 63, attachment = 88)
        assertEquals(q, GrowthJson.decodeRelationshipQuality(GrowthJson.encode(q)))
    }

    // MARK: - 动态兴趣（heat 缺省 50）

    @Test fun dynamicInterestHeatDefault50() {
        val list = GrowthJson.decodeDynamicInterests("""[{"name":"手冲咖啡","discoveredDate":0,"lastMentionedDate":0}]""")
        assertEquals(1, list.size)
        assertEquals("手冲咖啡", list[0].name)
        assertEquals(50, list[0].heat)
    }

    // MARK: - GrowthEventType.fromRaw（iOS rawValue → 枚举，未知回落 majorEvent）

    @Test fun growthEventTypeFromRaw() {
        assertEquals(GrowthEventType.PERSONALITY_SHIFT, GrowthEventType.fromRaw("personalityShift"))
        assertEquals(GrowthEventType.RELATIONSHIP_CHANGE, GrowthEventType.fromRaw("relationshipChange"))
        assertEquals(GrowthEventType.INTEREST_DISCOVERED, GrowthEventType.fromRaw("interestDiscovered"))
        assertEquals(GrowthEventType.GIFT_SENT, GrowthEventType.fromRaw("giftSent"))
        assertEquals(GrowthEventType.MAJOR_EVENT, GrowthEventType.fromRaw("majorEvent"))
        assertEquals(GrowthEventType.MAJOR_EVENT, GrowthEventType.fromRaw("bogusUnknown")) // 非法回落
    }
}
