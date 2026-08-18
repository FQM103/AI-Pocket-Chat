package com.situ.aichat.world.atlas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * `WorldNameForge` T1（W3 图纸 §3.5 / E5·E10）：确定性 / 唯一性 / 构名规则 / 金标钉死。
 *
 * 断言从图纸 §3.5 规格独立反推：字库成员、构名规则（单前缀 2 字 / 双前缀 3 字且 p1≠p2）、唯一性占名——
 * 金标值实跑固化并注明「绝不许改」（kotlin.random.Random 序列无法在 JVM 外复算，故用实跑固化法·E10 授权）。
 */
class WorldNameForgeTest {

    // ---- E1 确定性：同随机流同序列 ----

    @Test
    fun cityNameSameSeedSameName() {
        assertEquals(
            WorldNameForge.cityName(Random(42L), mutableSetOf()),
            WorldNameForge.cityName(Random(42L), mutableSetOf()),
        )
    }

    @Test
    fun cityNameStreamIsDeterministic() {
        fun run() = (0 until 20).let {
            val s = Random(7L); val used = mutableSetOf<String>()
            it.map { WorldNameForge.cityName(s, used) }
        }
        assertEquals(run(), run())
    }

    // ---- 构名规则（§3.5 独立反推）----

    @Test
    fun cityNamesObeyConstructionRules() {
        val stream = Random(99L)
        val used = mutableSetOf<String>()
        repeat(200) {
            val name = WorldNameForge.cityName(stream, used)
            // 单前缀 = 2 字（前缀+后缀）；双前缀 = 3 字（p1+p2+后缀·此测无「新」兜底）。
            assertTrue("非法长度: $name", name.length == 2 || name.length == 3)
            assertTrue("首字非前缀: $name", name[0] in WorldNameForge.CITY_PREFIXES)
            assertTrue("尾字非后缀: $name", name.last() in WorldNameForge.CITY_SUFFIXES)
            if (name.length == 3) {
                assertTrue("双前缀第二字非前缀: $name", name[1] in WorldNameForge.CITY_PREFIXES)
                assertNotEquals("双前缀 p1==p2: $name", name[0], name[1]) // §3.5 p1≠p2
            }
        }
    }

    @Test
    fun personNamesObeyConstructionRules() {
        val stream = Random(123L)
        val used = mutableSetOf<String>()
        repeat(200) {
            val name = WorldNameForge.personName(stream, used)
            assertTrue("姓非姓库: $name", name[0] in WorldNameForge.SURNAMES) // 姓 1 字
            assertTrue("名长度非 1–2: $name", name.length == 2 || name.length == 3)
            for (i in 1 until name.length) {
                assertTrue("名字非名库: $name", name[i] in WorldNameForge.GIVEN_NAMES)
            }
        }
    }

    @Test
    fun streetNamesObeyConstructionRules() {
        val stream = Random(55L)
        repeat(100) {
            val name = WorldNameForge.streetName(stream)
            assertEquals("街名恒 2 字: $name", 2, name.length)
            assertTrue(name[0] in WorldNameForge.STREET_PREFIXES)
            assertTrue(name[1] in WorldNameForge.STREET_SUFFIXES)
        }
    }

    // ---- E5 唯一性：占名 + 冲突重抽 ----

    @Test
    fun cityNamesAreGloballyUnique() {
        val stream = Random(2024L)
        val used = mutableSetOf<String>()
        val names = (0 until 100).map { WorldNameForge.cityName(stream, used) }
        assertEquals("有重名", names.size, names.toSet().size)
        assertEquals("used 未逐名占用", names.size, used.size)
    }

    @Test
    fun personNamesUniquePerCity() {
        val stream = Random(2025L)
        val used = mutableSetOf<String>()
        val names = (0 until 50).map { WorldNameForge.personName(stream, used) }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun collisionForcesRedrawFromSameStream() {
        // Random(42L) 首抽 = 苇蓝崖（金标）；预占它 → 同流必须跳过、返回不同名。
        val used = mutableSetOf("苇蓝崖")
        val name = WorldNameForge.cityName(Random(42L), used)
        assertNotEquals("苇蓝崖", name)
        assertTrue(used.contains("苇蓝崖") && used.contains(name))
    }

    // ---- E10 金标钉死（实跑固化·绝不许改）----

    @Test
    fun goldValuesAreFrozen() {
        assertEquals("苇蓝崖", WorldNameForge.cityName(Random(42L), mutableSetOf()))
        assertEquals("星盐桥", WorldNameForge.cityName(Random(1L), mutableSetOf()))
        assertEquals("老街", WorldNameForge.streetName(Random(3L)))
        assertEquals("苏苒树", WorldNameForge.personName(Random(7L), mutableSetOf()))
        assertEquals("屋顶上永远蹲着猫的钟楼", WorldNameForge.landmarkHint(Random(5L)))
        assertEquals("城名来自一场没人记得的约定", WorldNameForge.legendHint(Random(9L)))
        // Random(42L) 连抽 10 城（共享 used）——金标序列·绝不许改。
        val stream = Random(42L)
        val used = mutableSetOf<String>()
        val first10 = (0 until 10).map { WorldNameForge.cityName(stream, used) }
        assertEquals(
            listOf("苇蓝崖", "杏荻坞", "潮城", "岸星港", "月岚湾", "枫港", "岸湾", "枫关", "荷潮里", "沙关"),
            first10,
        )
    }
}
