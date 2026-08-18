package com.situ.aichat.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 甲 1·T2-A2：[AvatarStore.save] 的**成品位图**重载（裁剪屏交件 → 落盘）。
 *
 * 断言从规格独立反推（§2.3 B2「头像落库规格 = avatars/ · 512 · q85 · UUID.jpg」），不照搬实现：
 * 落盘产物必须真能解回来、长边真被钳到 512、真落在 avatars/ 目录下且是 .jpg。
 *
 * NATIVE 图形模式：默认 LEGACY 的影子 BitmapFactory 会捏造位图/尺寸，
 * 「压缩落盘 → 解回来尺寸对不对」这类断言在影子下没有真证据（同 [ImageOrientationTest]）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarStoreBitmapTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun bitmap(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        .apply { eraseColor(Color.RED) }

    @Test
    fun 存成品图_落在avatars目录且是jpg() = runBlocking {
        val path = AvatarStore.save(context, bitmap(300, 300))

        assertNotNull("正常位图必须存得下来", path)
        val file = File(path!!)
        assertTrue("文件真的落盘了", file.exists())
        assertTrue("必须落在 avatars/ 目录", file.parentFile!!.name == "avatars")
        assertTrue("必须是 .jpg", file.name.endsWith(".jpg"))
        assertTrue("文件非空", file.length() > 0)
    }

    @Test
    fun 超大成品图_长边钳到512() = runBlocking {
        val path = AvatarStore.save(context, bitmap(2048, 2048))!!

        val decoded = BitmapFactory.decodeFile(path)
        assertNotNull("落盘的图必须能解回来", decoded)
        assertEquals("长边必须钳到 512", 512, decoded.width)
        assertEquals(512, decoded.height)
    }

    @Test
    fun 非正方形成品图_按长边等比缩不变形() = runBlocking {
        // 裁剪屏交的是正方形，但重载本身不该假设——长边 1024 → 512，短边等比减半。
        val path = AvatarStore.save(context, bitmap(1024, 512))!!

        val decoded = BitmapFactory.decodeFile(path)
        assertEquals(512, decoded.width)
        assertEquals("短边须等比缩，不许拉伸", 256, decoded.height)
    }

    @Test
    fun 小于512的成品图_不放大() = runBlocking {
        val path = AvatarStore.save(context, bitmap(200, 200))!!

        val decoded = BitmapFactory.decodeFile(path)
        assertEquals("小图不该被强行放大（scaleToMaxEdge 只缩不放）", 200, decoded.width)
        assertEquals(200, decoded.height)
    }

    @Test
    fun 入参位图不被回收_调用方作用域说了算() = runBlocking {
        val src = bitmap(300, 300)
        AvatarStore.save(context, src)

        assertFalse("绝不许回收调用方的位图（裁剪屏还持有它显示中）", src.isRecycled)
    }

    @Test
    fun 每次存都是新UUID文件_换头像即换path() = runBlocking {
        val a = AvatarStore.save(context, bitmap(120, 120))
        val b = AvatarStore.save(context, bitmap(120, 120))

        assertNotNull(a)
        assertNotNull(b)
        assertTrue("两次落盘必须是不同文件（path 变 = 头像缓存自动失效）", a != b)
    }

    @Test
    fun E6_已回收的位图_返null而非崩_调用方保留旧头像() = runBlocking {
        val dead = bitmap(300, 300).apply { recycle() }

        assertNull("存不下去就老实返 null（?.let 不更新 avatarPath），绝不崩", AvatarStore.save(context, dead))
    }
}
