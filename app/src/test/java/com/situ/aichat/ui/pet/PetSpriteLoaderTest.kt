package com.situ.aichat.ui.pet

import android.content.ComponentCallbacks2
import android.content.Context
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * PetSpriteLoader 缓存行为（K4·2026-07-12 性能线程专项）：LruCache 命中同实例、内存压力收缩后重解码、
 * 缺失资源回 null（帧渲染层兜底 🐾）。用真 assets（petsprites/ 打进主源码），Robolectric 直读。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PetSpriteLoaderTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun firstAssetPath(): String {
        val names = context.assets.list("petsprites").orEmpty()
        assertTrue("petsprites 资产目录应非空", names.isNotEmpty())
        return "petsprites/${names.first()}"
    }

    @Test
    fun `second load hits cache and returns same instance`() {
        val path = firstAssetPath()
        val first = PetSpriteLoader.load(context, path)
        val second = PetSpriteLoader.load(context, path)
        assertNotNull(first)
        assertSame(first, second)
    }

    @Test
    fun `trim on memory pressure evicts so next load redecodes`() {
        val path = firstAssetPath()
        val before = PetSpriteLoader.load(context, path)
        assertNotNull(before)
        // UI_HIDDEN(20) 非弃用等级，且 ≥ RUNNING_CRITICAL(15) 走全清分支（ImageCacheTrim 收缩表）。
        PetSpriteLoader.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        val after = PetSpriteLoader.load(context, path)
        assertNotNull(after)
        assertNotSame(before, after) // 已被收缩淘汰 → 重新解码出新实例
    }

    @Test
    fun `missing asset returns null`() {
        assertNull(PetSpriteLoader.load(context, "petsprites/__missing_sprite_99.png"))
    }
}
