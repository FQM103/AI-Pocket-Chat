package com.situ.aichat.ui.story

import com.situ.aichat.story.StoryReaderTypography
import com.situ.aichat.story.StoryTextStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「样式→显示字号」接线 T1（T5 复核 🔵-3·2026-07-13）：shout/emphasis 的烘焙显示字号必须被
 * fontSizeFor 真消费——渲染层块高被字体度量 + int px 量化吞没 2% 级差（Alignment T2 实测 35.0=35.0），
 * 等值比对够不着，故在纯函数层零量化锁死；若有人把 SHOUT/EMPHASIS 分支回退成表原值/bodySp，此测必红。
 */
class StoryReaderFontSizeWiringTest {

    @Test fun fontSizeFor_wiresEveryStyleToItsDisplaySize_allTiers() {
        StoryReaderTypography.TIERS.forEach { t ->
            assertEquals(t.shoutDisplaySp, fontSizeFor(StoryTextStyle.SHOUT, t).value, 1e-4f)
            assertEquals(t.emphasisDisplaySp, fontSizeFor(StoryTextStyle.EMPHASIS, t).value, 1e-4f)
            assertEquals(t.whisperSp.toFloat(), fontSizeFor(StoryTextStyle.WHISPER, t).value, 1e-4f)
            // 其余样式（normal/thought/trembling/angry/excited）一律正文字号。
            listOf(
                StoryTextStyle.NORMAL,
                StoryTextStyle.THOUGHT,
                StoryTextStyle.TREMBLING,
                StoryTextStyle.ANGRY,
                StoryTextStyle.EXCITED,
            ).forEach { style ->
                assertEquals(t.bodySp.toFloat(), fontSizeFor(style, t).value, 1e-4f)
            }
        }
    }
}
