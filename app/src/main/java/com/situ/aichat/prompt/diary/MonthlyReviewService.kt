package com.situ.aichat.prompt.diary

import android.content.Context
import android.util.Log
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.local.entity.MonthlyReviewEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.DiaryRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.prompt.GeneratedContentValidator
import com.situ.aichat.prompt.PromptStrings
import com.situ.aichat.prompt.memory.MemoryService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 月度回顾生成（R5·契约 §2 F4）：为某个自然月写一封「信式小结」——素材 = 当月已发布用户日记摘录 +
 * 心情分布，每月 1 次 LLM 调用。触发双路：每月 1 日 worker 兜底补上月（[checkAndGenerateLastMonth]·
 * 随日记自动生成开关）+ 列表月分节头部手动「生成回顾」。每月一篇（唯一索引幂等）。
 */
@Singleton
class MonthlyReviewService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contextLog: ContextLogService,
    private val apiConfigRepo: ApiConfigRepository,
    private val diaryRepository: DiaryRepository,
    private val userProfileDao: UserProfileDao,
    private val settingsRepo: SettingsRepository,
) {

    sealed interface Result {
        data class Success(val review: MonthlyReviewEntity) : Result

        /** 已有该月回顾（幂等直取）。 */
        data class Exists(val review: MonthlyReviewEntity) : Result

        /** 该月没有已发布的用户日记 → 无米之炊。 */
        data object Empty : Result
        data object NoApi : Result
        data object Failed : Result
    }

    /** worker 兜底：跨月后补上月（随日记自动生成开关·已有/无素材/无 API 都安静跳过）。 */
    suspend fun checkAndGenerateLastMonth(nowMillis: Long = System.currentTimeMillis()) {
        if (!settingsRepo.getAppSettings().diaryAutoGenerateEnabled) return
        val zone = ZoneId.systemDefault()
        val lastMonthStart = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
            .withDayOfMonth(1).minusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
        generateForMonth(lastMonthStart)
    }

    /** 为 [monthStartMillis] 起的自然月生成回顾（幂等·守卫链见 [Result]）。 */
    suspend fun generateForMonth(monthStartMillis: Long): Result {
        val zone = ZoneId.systemDefault()
        val monthStart = Instant.ofEpochMilli(monthStartMillis).atZone(zone).toLocalDate().withDayOfMonth(1)
        val start = monthStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = monthStart.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()

        diaryRepository.monthlyReviewFor(start)?.let { return Result.Exists(it) }
        val published = diaryRepository.entriesInRange(start, end)
            .filter { !it.isDraft && !it.isPetDiary && it.authorCharacterUuid == null }
            .sortedBy { it.timestamp }
        if (published.isEmpty()) return Result.Empty
        val config = apiConfigRepo.resolveConfigValues(ApiFunction.DIARY_GENERATION) ?: return Result.NoApi

        val strings = PromptStrings(context)
        val userName = userProfileDao.get()?.nickname?.trim()?.takeIf { it.isNotEmpty() }
            ?: strings.s(R.string.diary_user_fallback)
        val moodCounts = published.mapNotNull { it.moodEmoji?.takeIf(String::isNotEmpty) }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key to it.value }
        // prompt 内月份用 yyyy-MM 客观串（无格式耦合）；展示标题由 UI 层用资源模式各自格式化。
        val monthLabel = "%04d-%02d".format(monthStart.year, monthStart.monthValue)
        val system = buildPrompt(
            intro = strings.s(R.string.diary_review_intro, userName, monthLabel),
            reqHeader = strings.s(R.string.diary_prompt_requirements_header),
            reqLines = listOf(
                strings.s(R.string.diary_review_req_letter),
                strings.s(R.string.diary_review_req_detail),
                strings.s(R.string.diary_review_req_words),
                strings.s(R.string.diary_review_req_no_ai),
            ),
            linesHeader = strings.s(R.string.diary_review_lines_header),
            lines = published.take(MAX_LINES).map { entryLine(it, zone) },
            moodHeader = strings.s(R.string.diary_review_mood_header),
            moodLine = moodCounts.joinToString("、") { "${it.first} ${it.second}" },
            outputOnly = strings.s(R.string.diary_prompt_output_only),
        )
        val messages = listOf(
            ChatMessageDto(role = "system", content = system),
            ChatMessageDto(role = "user", content = strings.s(R.string.diary_review_user_message)),
        )
        val content = try {
            MemoryService.strippingThinkingTags(
                contextLog.completion(
                    source = LogSource.DIARY_GENERATION,
                    characterName = "",
                    config = config,
                    messages = messages,
                    temperature = 0.7,
                ),
            ).takeIf { GeneratedContentValidator.isLikelyValid(it) }
        } catch (e: Exception) {
            Log.w(TAG, "月度回顾生成失败: ${e.message}")
            null
        } ?: return Result.Failed

        val review = MonthlyReviewEntity(
            uuid = UUID.randomUUID().toString(),
            monthStartMillis = start,
            content = content,
            moodCountsJson = encodeMoodCounts(moodCounts),
        )
        diaryRepository.insertMonthlyReview(review)
        Log.d(TAG, "月度回顾已生成")
        return Result.Success(review)
    }

    /** 素材行：「yyyy-MM-dd：正文前 [LINE_PREVIEW] 字」（客观日期串·无格式耦合）。 */
    private fun entryLine(entry: DiaryEntryEntity, zone: ZoneId): String {
        val day = Instant.ofEpochMilli(entry.timestamp).atZone(zone).toLocalDate()
        return "$day：${entry.content.take(LINE_PREVIEW)}"
    }

    internal companion object {
        private const val TAG = "MonthlyReview"
        const val MAX_LINES = 40
        const val LINE_PREVIEW = 80

        /** system prompt 装配（纯函数·T1 哨兵）。可选心情段空则省略。 */
        internal fun buildPrompt(
            intro: String,
            reqHeader: String,
            reqLines: List<String>,
            linesHeader: String,
            lines: List<String>,
            moodHeader: String,
            moodLine: String,
            outputOnly: String,
        ): String {
            val parts = mutableListOf(intro, "", reqHeader)
            parts.addAll(reqLines)
            parts.add("")
            parts.add(linesHeader)
            parts.addAll(lines)
            parts.add("")
            if (moodLine.isNotEmpty()) {
                parts.add(moodHeader)
                parts.add(moodLine)
                parts.add("")
            }
            parts.add(outputOnly)
            return parts.joinToString("\n")
        }

        /** `{"😊":8,"😌":6}`（emoji 无需转义·解码见 [decodeMoodCounts]）。 */
        internal fun encodeMoodCounts(counts: List<Pair<String, Int>>): String =
            counts.joinToString(",", prefix = "{", postfix = "}") { "\"${it.first}\":${it.second}" }

        /** 宽松解码（面板展示用·坏数据 → 空）。 */
        internal fun decodeMoodCounts(json: String): List<Pair<String, Int>> =
            Regex("\"([^\"]+)\":(\\d+)").findAll(json)
                .map { it.groupValues[1] to it.groupValues[2].toInt() }
                .toList()
    }
}
