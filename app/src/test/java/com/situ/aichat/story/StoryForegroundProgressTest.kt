package com.situ.aichat.story

import com.situ.aichat.story.StoryGenerationTaskManager.GenerationProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 灵动岛卷一 T1-2：⑤ Live Update 的**选路**纯逻辑——从「活跃生成映射」挑代表转成
 * [com.situ.aichat.foreground.ForegroundActivity.StoryProgress]。
 *
 * 多故事并发取进度最高一路（单药丸只能显示一个·E3），storyId 随之切换（点击深链跟着人走）；
 * 空映射 → null（让出故事槽）；分数钳 0–1；两条文案由 [StoryProgressModel] 再生成、不吃传入的 phase 字段。
 */
class StoryForegroundProgressTest {

    private fun gp(
        progress: Double,
        title: String,
        genPhase: StoryGenPhase = StoryGenPhase.WRITING,
        chapterNumber: Int = 1,
    ) = GenerationProgress(
        progress = progress,
        genPhase = genPhase,
        phase = StoryProgressModel.phaseLabel(genPhase, chapterNumber),
        storyTitle = title,
        chapterNumber = chapterNumber,
    )

    @Test
    fun 空映射_返回null_让出故事槽() {
        assertNull(leadForegroundProgress(emptyMap()))
    }

    @Test
    fun 单路_透传分数标题章号并带上storyId() {
        val r = leadForegroundProgress(mapOf("story-a" to gp(0.4, "甲", chapterNumber = 7)))!!
        assertEquals("story-a", r.storyId)
        assertEquals(0.4, r.overall, 0.0)
        assertEquals("甲", r.title)
        assertEquals(7, r.chapterNumber)
    }

    @Test
    fun E3_多路并发_取进度最高的一路_storyId随之切换() {
        val r = leadForegroundProgress(
            mapOf(
                "a" to gp(0.2, "甲"),
                "b" to gp(0.8, "乙"),
                "c" to gp(0.5, "丙"),
            ),
        )!!
        assertEquals("乙", r.title)
        assertEquals("b", r.storyId) // 深链必须跟着胜出的那本书走
        assertEquals(0.8, r.overall, 0.0)
    }

    @Test
    fun 越界分数_钳到0到1() {
        assertEquals(1.0, leadForegroundProgress(mapOf("a" to gp(1.5, "x")))!!.overall, 0.0)
        assertEquals(0.0, leadForegroundProgress(mapOf("a" to gp(-0.3, "x")))!!.overall, 0.0)
    }

    @Test
    fun 两条文案按阶段与章号再生成() {
        val r = leadForegroundProgress(
            mapOf("a" to gp(0.92, "甲", genPhase = StoryGenPhase.ARCHIVING, chapterNumber = 12)),
        )!!
        assertEquals("正在记下这段故事…", r.phaseLabel)
        assertEquals("归档", r.shortLabel)
        assertEquals(StoryGenPhase.ARCHIVING, r.genPhase)
    }

    @Test
    fun 完成态_文案带章号() {
        val r = leadForegroundProgress(
            mapOf("a" to gp(1.0, "甲", genPhase = StoryGenPhase.DONE, chapterNumber = 5)),
        )!!
        assertEquals("第 5 章写好了", r.phaseLabel)
        assertEquals("完成", r.shortLabel)
    }
}
