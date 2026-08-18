package com.situ.aichat.world.cast

import com.situ.aichat.data.local.entity.WorldUserResidentEntity
import com.situ.aichat.util.StringListJson
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.atlas.WorldAtlas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [WorldResidentService.defOf] / [WorldResidentService.clampAge] T1-1 / T1-2（战役 B 图纸 §7·E4/E5）：
 * def 映射逐字对 §3.1 模板、E4 城查无回退、三档 fuelBias 落值、threshold=105、年龄钳位。
 *
 * 断言从图纸 §3.1 规格**独立反推**（模板串逐字重写、权重手算），不照搬 [WorldResidentService] 实现。
 * 纯 JVM（[WorldAtlas] 无 Android 依赖）。
 */
class WorldResidentDefMappingTest {

    private val seed = 1L

    private fun entity(
        slug: String = "resident_ab12cd34",
        name: String = "江晚棠",
        gender: String = "female",
        age: Int = 26,
        cityId: String = "city_yunye",
        occupation: String = "旧书店店主",
        personaBrief: String = "安静，记性好得吓人",
        traits: List<String> = listOf("温吞", "毒舌"),
        freeformLore: String = "她有一只不让人碰的旧怀表",
        initialRelationText: String = "是老板娘的表妹",
        fuelBias: String = "balanced",
    ) = WorldUserResidentEntity(
        slug = slug, name = name, gender = gender, age = age, cityId = cityId, occupation = occupation,
        personaBrief = personaBrief, traitsJson = StringListJson.encode(traits), freeformLore = freeformLore,
        initialRelationText = initialRelationText, fuelBias = fuelBias, avatarPath = null, createdAt = 100L,
    )

    @Test
    fun `T1-1 defOf 全字段逐项对 §3_1 模板`() {
        val def = WorldResidentService.defOf(entity(), seed)
        assertEquals("resident_ab12cd34", def.slug)
        assertEquals("江晚棠", def.name)
        assertEquals("female", def.gender)
        assertEquals(26, def.fixedAge)
        assertEquals("云野镇所在区", "yunze", def.regionId)
        assertEquals("city_yunye", def.cityId)
        assertNull("生成/用户居民无常驻地点", def.placeId)
        assertEquals("旧书店店主", def.occupation)
        assertEquals("剪影副标 = 职业", "旧书店店主", def.oneLiner)
        assertEquals("安静，记性好得吓人。性格底色：温吞、毒舌", def.personality)
        assertEquals("", def.appearance)
        assertEquals("她有一只不让人碰的旧怀表\n是老板娘的表妹", def.backstory)
        assertEquals("说话贴合性格底色：温吞、毒舌", def.speakingStyle)
        assertEquals("", def.catchphrases)
        assertEquals("", def.interests)
        assertEquals("你好呀，我是江晚棠。之前就见过你几次，今天总算说上话了。", def.greeting)
        assertEquals(105, def.recruitThreshold)
    }

    @Test
    fun `T1-1 backstory 空段过滤_只留非空`() {
        assertEquals("只有自由设定", WorldResidentService.defOf(entity(freeformLore = "只有自由设定", initialRelationText = ""), seed).backstory)
        assertEquals("只有初始关系", WorldResidentService.defOf(entity(freeformLore = "", initialRelationText = "只有初始关系"), seed).backstory)
        assertEquals("", WorldResidentService.defOf(entity(freeformLore = "", initialRelationText = ""), seed).backstory)
    }

    @Test
    fun `T1-1 E4 cityId 查无_regionId 回退家乡城所在区`() {
        val homeRegion = WorldAtlas.of(seed).cityById(WorldIds.HOME_CITY_ID)!!.regionId
        val def = WorldResidentService.defOf(entity(cityId = "city_does_not_exist"), seed)
        assertEquals(homeRegion, def.regionId)
    }

    @Test
    fun `T1-1 三档 fuelBias 落官方真值域内`() {
        WorldResidentService.defOf(entity(fuelBias = "balanced"), seed).let {
            assertEquals(1.0, it.narrativeWeight, 0.0); assertEquals(1.0, it.giftWeight, 0.0)
        }
        WorldResidentService.defOf(entity(fuelBias = "narrative"), seed).let {
            assertEquals(1.2, it.narrativeWeight, 0.0); assertEquals(0.6, it.giftWeight, 0.0)
        }
        WorldResidentService.defOf(entity(fuelBias = "gift"), seed).let {
            assertEquals(1.0, it.narrativeWeight, 0.0); assertEquals(1.2, it.giftWeight, 0.0)
        }
        // 未知倾向兜底 balanced（防脏数据崩）。
        WorldResidentService.defOf(entity(fuelBias = "???"), seed).let {
            assertEquals(1.0, it.narrativeWeight, 0.0); assertEquals(1.0, it.giftWeight, 0.0)
        }
    }

    @Test
    fun `T1-2 clampAge 钳 1 到 999_空与非数字默认 26`() {
        assertEquals(26, WorldResidentService.clampAge(""))
        assertEquals(26, WorldResidentService.clampAge("   "))
        assertEquals(26, WorldResidentService.clampAge("abc"))
        assertEquals(26, WorldResidentService.clampAge("26"))
        assertEquals(30, WorldResidentService.clampAge(" 30 "))
        assertEquals(1, WorldResidentService.clampAge("0"))
        assertEquals(1, WorldResidentService.clampAge("-5"))
        assertEquals(999, WorldResidentService.clampAge("1000"))
        assertEquals(999, WorldResidentService.clampAge("999"))
    }
}
