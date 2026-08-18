package com.situ.aichat.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [truncateScheduleSubtitle] 单测（P0-17）。**断言反推 iOS `ChatView+Navigation.subtitleText`**：
 * `count > 8 ? prefix(8)+"…" : self`，以字素簇为单位（Swift .count/.prefix）。重点验证「结尾 emoji 不被截半」。
 */
class ScheduleSubtitleTruncationTest {

    @Test
    fun moreThan8Clusters_truncatesToFirst8PlusEllipsis() {
        // 11 个汉字簇 → 取前 8 + "…"
        assertEquals("在公司开会讨论项…", truncateScheduleSubtitle("在公司开会讨论项目方案"))
    }

    @Test
    fun exactly8Clusters_unchanged() {
        assertEquals("上班开会写代码呢", truncateScheduleSubtitle("上班开会写代码呢")) // 8 簇
    }

    @Test
    fun fewerThan8Clusters_unchanged() {
        assertEquals("睡觉 😴", truncateScheduleSubtitle("睡觉 😴")) // 4 簇
    }

    @Test
    fun graphemeBased_notUtf16Length_emojiNotCut() {
        // "工作中 😀😀😀" = 7 字素簇（≤8 不截），但 UTF-16 长度=10（每 emoji 2 单元）——
        // 朴素 take(8) 会把第 3 个 emoji 的代理对截半；字素簇版本必须原样返回，证明按簇而非 UTF-16 长度。
        assertEquals("工作中 😀😀😀", truncateScheduleSubtitle("工作中 😀😀😀"))
    }

    @Test
    fun truncationCutsAtClusterBoundary_notMidEmoji() {
        // 10 簇（8 汉字 + 空格 + emoji）→ 前 8 = 8 个汉字，截在簇边界、不含空格/emoji。
        assertEquals("做饭洗碗拖地擦窗…", truncateScheduleSubtitle("做饭洗碗拖地擦窗 🍳"))
    }

    @Test
    fun empty_unchanged() {
        assertEquals("", truncateScheduleSubtitle(""))
    }
}
