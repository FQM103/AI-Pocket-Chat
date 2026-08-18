package com.situ.aichat.story

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 每部故事最近阅读到的章节记账（11.1h-1，1:1 iOS `StoryReadingProgressStore` 的章节级进度部分）。
 *
 * iOS 用 UserDefaults 同步键值；安卓沿用 SharedPreferences（同步、读取即取，契合卡片渲染时直接查「上次读到第几章」）。
 * 续读首选章节的纯选择逻辑见 [StoryReadingProgressLogic]；章节内滚动偏移留 11.1i 阅读器（Compose 滚动模型另定）。
 */
@Singleton
class StoryReadingProgressStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /**
     * 保存阅读进度（1:1 iOS `saveProgress` :14-21）：记最近打开的故事 + 该故事上次读到的章 id（可选章号）。
     *
     * 卷三 C3 增记「本次阅读时刻」（[lastReadAtMillis]）供「上回说到」判回访间隔——**增列键**，
     * 三个既有键的读写语义一字不变（图纸 §2.2 B4）。
     *
     * @param nowMillis 本次阅读时刻，默认取系统时钟；测试从真实 now 相对构造（PITFALLS §1e）。
     */
    fun saveProgress(
        storyId: String,
        chapterId: String,
        chapterNumber: Int? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val advancedFrom = advancedFromChapterNumber(storyId)
        prefs.edit {
            putString(KEY_LAST_STORY_ID, storyId)
            putString(CHAPTER_ID_PREFIX + storyId, chapterId)
            if (chapterNumber != null) putInt(CHAPTER_NUM_PREFIX + storyId, chapterNumber)
            putLong(LAST_READ_AT_PREFIX + storyId, nowMillis)
            // 「推进起点」标记自消费：打开的不再是按下推进的那一章，说明这次推进已经翻篇（跳到了新章，
            // 或用户自己回头翻别的章）→ 清掉，免得下次续读还把落点往前推一章、把重读旧章的人踢走。
            if (advancedFrom != null && advancedFrom != chapterNumber) remove(ADVANCED_FROM_PREFIX + storyId)
        }
    }

    /**
     * 记「用户在第 [chapterNumber] 章按下了推进」（= 看完这章并要求写下一章）。由
     * [com.situ.aichat.story.StoryGenerationTaskManager.startGeneration]（用户主动生成的唯一入口）写入；
     * 追更自动生成不经那里，故半夜自动更新的章不会推着阅读进度走。消费方 = [StoryReadingProgressLogic.preferredResumeChapter]。
     */
    fun markAdvancedFrom(storyId: String, chapterNumber: Int) {
        prefs.edit { putInt(ADVANCED_FROM_PREFIX + storyId, chapterNumber) }
    }

    /** 该故事的「推进起点」章号；无记录 / 非正 → null（语义同 [lastReadChapterNumber]）。 */
    fun advancedFromChapterNumber(storyId: String): Int? =
        prefs.getInt(ADVANCED_FROM_PREFIX + storyId, 0).takeIf { it > 0 }

    /**
     * 该故事上次阅读的时刻（毫秒）；从未记过 → null（老用户首次进入即属此列，见图纸 §5 E4：
     * 本次不弹「上回说到」、本次进入写入时间戳、下次回访才生效，自愈无迁移）。
     */
    fun lastReadAtMillis(storyId: String): Long? =
        prefs.getLong(LAST_READ_AT_PREFIX + storyId, 0L).takeIf { it > 0L }

    /** 该故事上次阅读章节的 id（无则 null）。 */
    fun lastReadChapterId(storyId: String): String? = prefs.getString(CHAPTER_ID_PREFIX + storyId, null)

    /** 该故事上次阅读章节编号（不必加载章节对象；无 / 非正 → null，1:1 iOS `value > 0 ? value : nil`）。 */
    fun lastReadChapterNumber(storyId: String): Int? =
        prefs.getInt(CHAPTER_NUM_PREFIX + storyId, 0).takeIf { it > 0 }

    /** 最近打开过的故事 id（书架默认定位用，无则 null）。 */
    fun lastOpenedStoryId(): String? = prefs.getString(KEY_LAST_STORY_ID, null)

    /** 阅读动画开关（1:1 iOS @AppStorage("storyReadingAnimationsEnabled")，默认开）。 */
    fun readingAnimationsEnabled(): Boolean = prefs.getBoolean(KEY_READING_ANIMATIONS, true)

    fun setReadingAnimationsEnabled(enabled: Boolean) = prefs.edit { putBoolean(KEY_READING_ANIMATIONS, enabled) }

    /** 阅读字号档位下标（P1-6 安卓超越，iOS 无字号调节；默认档=iOS 原值。消费端 forIndex 钳位抗脏值）。 */
    fun fontSizeIndex(): Int = prefs.getInt(KEY_FONT_SIZE_INDEX, StoryReaderTypography.DEFAULT_INDEX)

    fun setFontSizeIndex(index: Int) = prefs.edit { putInt(KEY_FONT_SIZE_INDEX, index) }

    /**
     * 每部故事「更新提醒」总闸（追更解锁通知·默认开·ST7c 契约 §6.5 更新与解锁）。关 = 不为该故事排解锁通知
     * （[com.situ.aichat.story.StoryUnlockNotificationScheduler.scheduleUnlock] 处 gate）。per-story 键，无需 DB 迁移。
     */
    fun unlockReminderEnabled(storyId: String): Boolean = prefs.getBoolean(REMINDER_PREFIX + storyId, true)

    fun setUnlockReminderEnabled(storyId: String, enabled: Boolean) = prefs.edit { putBoolean(REMINDER_PREFIX + storyId, enabled) }

    // MARK: - 章节内滚动位置（11.1i，1:1 iOS saveScrollOffset/loadScrollOffset，Compose 模型映射见 StoryScrollRestoreLogic）

    /**
     * 保存当前阅读章节的滚动位置（LazyColumn 首个可见项下标 + 项内像素偏移）。
     * 接近顶部（[StoryScrollRestoreLogic.shouldSave] 为 false）时清除记录，避免下次恢复到无意义的顶部（1:1 iOS `:86-97`）。
     */
    fun saveScrollPosition(storyId: String, chapterId: String, index: Int, offset: Int) {
        val base = SCROLL_PREFIX + storyId
        prefs.edit {
            if (StoryScrollRestoreLogic.shouldSave(index, offset)) {
                putInt(base + SCROLL_INDEX_SUFFIX, index)
                putInt(base + SCROLL_OFFSET_SUFFIX, offset)
                putString(base + SCROLL_CHAPTER_SUFFIX, chapterId)
            } else {
                remove(base + SCROLL_INDEX_SUFFIX)
                remove(base + SCROLL_OFFSET_SUFFIX)
                remove(base + SCROLL_CHAPTER_SUFFIX)
            }
        }
    }

    /**
     * 仅当保存时的章节与当前章节相同才返回滚动位置，避免跨章恢复到错误位置（1:1 iOS `:99-109`）。
     * 同时复核仍达保存阈值，过滤掉历史脏值。
     */
    fun loadScrollPosition(storyId: String, chapterId: String): StoryScrollPosition? {
        val base = SCROLL_PREFIX + storyId
        val savedChapter = prefs.getString(base + SCROLL_CHAPTER_SUFFIX, null) ?: return null
        if (savedChapter != chapterId) return null
        val index = prefs.getInt(base + SCROLL_INDEX_SUFFIX, 0)
        val offset = prefs.getInt(base + SCROLL_OFFSET_SUFFIX, 0)
        return if (StoryScrollRestoreLogic.shouldSave(index, offset)) StoryScrollPosition(index, offset) else null
    }

    private companion object {
        const val PREFS = "story_reading_progress"
        const val KEY_LAST_STORY_ID = "storyReading.lastStoryID"
        const val CHAPTER_ID_PREFIX = "storyReading.chapter."
        const val CHAPTER_NUM_PREFIX = "storyReading.chapterNum."
        const val LAST_READ_AT_PREFIX = "storyReading.lastReadAt."
        const val ADVANCED_FROM_PREFIX = "storyReading.advancedFrom."
        const val SCROLL_PREFIX = "storyReading.scroll."
        const val SCROLL_INDEX_SUFFIX = ".index"
        const val SCROLL_OFFSET_SUFFIX = ".offset"
        const val SCROLL_CHAPTER_SUFFIX = ".chapter"
        const val KEY_READING_ANIMATIONS = "storyReading.animationsEnabled"
        const val KEY_FONT_SIZE_INDEX = "storyReading.fontSizeIndex"
        const val REMINDER_PREFIX = "storyReading.reminder."
    }
}
