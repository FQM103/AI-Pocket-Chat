package com.situ.aichat.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C1 自启动管理组件品牌路由纯函数单测（[BackgroundReliability.autoStartComponentsForBrand]）：
 * 主流国行 OEM 各自有非空候选（不再只小米可达）、未知品牌空（回退应用详情）、品牌关键词大小写/混排稳健。
 */
class AutoStartComponentsTest {

    private fun comps(brand: String) = BackgroundReliability.autoStartComponentsForBrand(brand)

    @Test fun xiaomiFamily_hasMiuiAutostart() {
        for (b in listOf("xiaomi xiaomi", "redmi redmi", "poco poco")) {
            val c = comps(b)
            assertTrue("$b 应有候选", c.isNotEmpty())
            assertEquals("com.miui.securitycenter", c.first().first)
        }
    }

    @Test fun majorOemFamilies_eachNonEmpty() {
        // 非小米国行主流：华为/荣耀·OPPO 系·vivo 系·魅族·三星——此前全部只跳应用详情，修复后各有目标组件
        for (b in listOf("huawei huawei", "honor honor", "oppo oppo", "realme realme",
                         "oneplus oneplus", "vivo vivo", "iqoo iqoo", "meizu meizu", "samsung samsung")) {
            assertTrue("$b 应有自启动候选组件", comps(b).isNotEmpty())
        }
    }

    @Test fun oppoFamily_includesNewAndOldPackages() {
        // ColorOS 包名随版本漂移 coloros/oplus/oppo 三套都备
        val pkgs = comps("oppo oppo").map { it.first }.toSet()
        assertTrue(pkgs.contains("com.coloros.safecenter"))
        assertTrue(pkgs.contains("com.oplus.safecenter"))
    }

    @Test fun unknownBrand_returnsEmpty_fallsBackToAppDetails() {
        assertTrue(comps("someunknownrom someunknownrom").isEmpty())
        assertTrue(comps("").isEmpty())
    }

    @Test fun allComponentsHavePkgAndClass() {
        for (b in listOf("xiaomi", "huawei", "oppo", "vivo", "meizu", "samsung")) {
            comps("$b $b").forEach { (pkg, cls) ->
                assertTrue("pkg 非空", pkg.isNotBlank())
                assertTrue("class 含包前缀", cls.isNotBlank() && cls.contains("."))
            }
        }
    }
}
