package com.situ.aichat.ui.story

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.situ.aichat.story.StoryGenPhase
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.theme.AIPocketChatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 灵动岛卷一 T3：[StoryPhaseBar] 的**真机渲染**取证（模拟器实跑·真字体真布局）。
 *
 * 为什么需要它：四段条的正确性有两半——「算得对」由 `StoryProgressModelTest` 锁死，
 * 「画得出来」只有真渲染能证。JVM 单测看不见「段全塌成 0 宽 / 活跃段填充不出现」这类缺陷。
 *
 * 本测把五个阶段各渲染一遍并把整条截图落盘（`/sdcard/Android/data/.../files/storyphasebar_*.png`），
 * 供人工目视；同时做机器可判的硬断言：**活跃段的填充像素必须真的出现**（撰写段半程时，
 * 条上必须能数到足量 accent 色像素——段塌了/填充没画就必然不足）。
 */
@RunWith(AndroidJUnit4::class)
class StoryPhaseBarRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun render(phase: StoryGenPhase, progress: Double, mini: Boolean, tag: String): Int {
        var accent = 0
        compose.setContent {
            AIPocketChatTheme {
                accent = AppTheme.colors.accent.primary.toArgb()
                Column(
                    Modifier
                        .background(Color.White)
                        .padding(8.dp)
                        .width(300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StoryPhaseBar(genPhase = phase, progress = progress, modifier = Modifier.fillMaxWidth(), mini = mini)
                }
            }
        }
        compose.waitForIdle()
        val img = compose.onRoot().captureToImage()
        val hits = countAccent(img, accent)
        val w = img.width
        val h = img.height
        val px = IntArray(w * h)
        img.readPixels(px)
        val dir = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.getExternalFilesDir(null)
        File(dir, "storyphasebar_$tag.png").outputStream().use { out ->
            android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
        println("PHASEBAR[$tag] size=${w}x$h accentPixels=$hits")
        return hits
    }

    /** 数实心 accent 像素（容差 24：抗锯齿会在边缘产生混色）。 */
    private fun countAccent(img: ImageBitmap, accent: Int): Int {
        val px = IntArray(img.width * img.height)
        img.readPixels(px)
        val ar = (accent shr 16) and 0xFF
        val ag = (accent shr 8) and 0xFF
        val ab = accent and 0xFF
        return px.count { p ->
            kotlin.math.abs(((p shr 16) and 0xFF) - ar) < 24 &&
                kotlin.math.abs(((p shr 8) and 0xFF) - ag) < 24 &&
                kotlin.math.abs((p and 0xFF) - ab) < 24
        }
    }

    @Test
    fun 构思段_起步态_一格填充都不该有() {
        // 进度 0：活跃段只有 16% 淡轨道、无填充。数到实心 accent = 假爬（旧假进度定时器的病根）。
        assertEquals(
            "构思段不许出现任何填充像素——真实事件没来就不许爬",
            0,
            render(StoryGenPhase.PREPARING, 0.0, mini = false, tag = "preparing_000"),
        )
    }

    @Test
    fun 撰写段半程_活跃段填充真的画出来了() {
        // 0.45 = 撰写段半程：第 1 段满 + 第 2 段填一半 → 必须画出实心 accent。
        val hits = render(StoryGenPhase.WRITING, 0.45, mini = false, tag = "writing_045")
        assertTrue("撰写段半程条上必须有填充像素（段塌陷/填充未绘就会是 0）", hits > 0)
    }

    @Test
    fun 进度越大_填充像素越多_四段条真的在推进() {
        // 单调性的**渲染级**证据：算得对（StoryProgressModelTest 已锁）不等于画得对。
        // setContent 一个测试只能调一次 → 用可变状态推进，同一棵树上连续取样。
        var state by mutableStateOf(StoryGenPhase.PREPARING to 0.0)
        var accent = 0
        compose.setContent {
            AIPocketChatTheme {
                accent = AppTheme.colors.accent.primary.toArgb()
                Column(Modifier.background(Color.White).padding(8.dp).width(300.dp)) {
                    StoryPhaseBar(genPhase = state.first, progress = state.second, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        fun sample(phase: StoryGenPhase, progress: Double): Int {
            state = phase to progress
            compose.waitForIdle()
            // 段内推进走 gentle 弹簧：等动画稳定后再取样，否则量到的是中途帧。
            compose.mainClock.advanceTimeBy(2_000)
            compose.waitForIdle()
            return countAccent(compose.onRoot().captureToImage(), accent)
        }

        val preparing = sample(StoryGenPhase.PREPARING, 0.0)
        val writingHalf = sample(StoryGenPhase.WRITING, 0.45)
        val finalizing = sample(StoryGenPhase.FINALIZING, 0.75)
        val done = sample(StoryGenPhase.DONE, 1.0)
        println("PHASEBAR[mono] 0=$preparing 0.45=$writingHalf 0.75=$finalizing 1.0=$done")
        assertTrue("0 → 0.45 填充必须变多（实测 $preparing → $writingHalf）", writingHalf > preparing)
        assertTrue("0.45 → 0.75 填充必须变多（实测 $writingHalf → $finalizing）", finalizing > writingHalf)
        assertTrue("0.75 → 1.0 填充必须变多（实测 $finalizing → $done）", done > finalizing)
    }

    @Test
    fun 归档段() {
        assertTrue(render(StoryGenPhase.ARCHIVING, 0.92, mini = false, tag = "archiving_092") > 0)
    }

    @Test
    fun mini档_书架卡片_三dp高也画得出来() {
        // mini 档只有 3dp 高：最容易「算对了但一像素都没画出来」。
        assertTrue(render(StoryGenPhase.WRITING, 0.45, mini = true, tag = "mini_writing_045") > 0)
    }
}
