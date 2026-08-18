package com.situ.aichat.world.link

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.WorldSocialDao
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.MoodHistoryEntry
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.world.SettlementWindow
import com.situ.aichat.world.WorldClock
import com.situ.aichat.world.social.WorldMoodTouch
import com.situ.aichat.world.social.WorldRelationshipBeats as Beats
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 世界事件的**情绪轻碰落笔**（W5 图纸 §3.3 / §4.3 / 契约 §8.B 情绪轻碰三则）：把 W4 留下的 `moodHint`
 * （[WorldMoodTouch] 纯函数）沿窗口逐日**衰减+聚合**成净心情，落到角色现有 `lastMood*` 三列 + 情绪历史。
 *
 * **不抢话（让位守卫）**：世界只在角色近来没被聊天更新过心情时才落笔——已存在同 id 条目、或现存最新条目比本条
 * 更新鲜 → 跳过（聊天心情永远优先·「大幅回暖的钥匙在用户手里」）。**幂等**：条目 id = `world:{charUuid}:{窗末日epochDay}`，
 * 同窗重跑不重复追加。「安慰回写」零新机制——世界近况注入聊天后角色倾诉、用户安慰、TA 下一条回复的 `[mood:]`
 * 经现有管线更新心情。同地点**情绪传染**（[WorldMoodTouch.contagion]）本块**不消费**（需同地点站位·归日程联动落地块）。
 */
@Singleton
class WorldMoodSettler @Inject constructor(
    private val socialDao: WorldSocialDao,
    private val characterDao: CharacterDao,
    private val characterRepo: CharacterRepository,
    private val settingsRepository: SettingsRepository,
) {

    suspend fun settle(state: WorldStateEntity, window: SettlementWindow, zone: ZoneId) {
        val days = window.days
        if (days.isEmpty()) return
        val maxCount = settingsRepository.appSettings.first().moodHistoryMaxCount
        val windowStartMs = days.first().date.atStartOfDay(zone).toInstant().toEpochMilli()
        val lastEpochDay = days.last().epochDay
        // 条目时刻 = 窗末日本地正午（禁真时钟·与记忆 happenedAt 同口径）。
        val entryTimestamp = days.last().date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

        for (character in characterDao.getInWorld()) {
            val byDay = socialDao.eventsSince(windowStartMs)
                .filter { it.actorId == character.uuid || it.targetId == character.uuid }
                .groupBy { WorldClock.localDateOf(it.happenedAt, zone).toEpochDay() }

            // 逐日：running = decay(running) + dayMoodDelta(当日 moodHint)，随后钳 ±2（护栏「轻碰」+ 自然衰减）。
            var running = 0
            for (day in days) {
                val hints = byDay[day.epochDay].orEmpty().map { moodHintOf(it.kindRaw) }
                running = (WorldMoodTouch.decay(running) + WorldMoodTouch.dayMoodDelta(hints)).coerceIn(-2, 2)
            }
            if (running == 0) continue // 净值 0 → 不写

            val id = "world:${character.uuid}:$lastEpochDay"
            val history = GrowthJson.decodeMoodHistory(character.moodHistoryJSON)
            if (history.any { it.id == id }) continue // ① 已存在同 id（同窗重跑）→ 跳过
            val newestTimestamp = history.maxOfOrNull { it.timestamp } ?: Long.MIN_VALUE
            if (newestTimestamp >= entryTimestamp) continue // ② 现存最新更鲜（聊天刚更新过）→ 世界不抢话

            val mood = moodOf(running)
            characterRepo.updateMood(character.uuid, mood.emoji, mood.text, mood.colorName)
            characterRepo.appendMoodHistory(
                character.uuid,
                MoodHistoryEntry(id = id, timestamp = entryTimestamp, emoji = mood.emoji, colorName = mood.colorName, text = mood.text),
                maxCount,
            )
        }
    }

    /** moodHint 取法（图纸 §3.3）：里程碑用 [Beats.MILESTONE_MOOD_HINT]；其余照 AXES 表（cold=−1）；无表项（drift/compact）=0。 */
    private fun moodHintOf(kindRaw: String): Int = when (kindRaw) {
        Beats.MILESTONE -> Beats.MILESTONE_MOOD_HINT
        else -> Beats.AXES[kindRaw]?.moodHint ?: 0
    }

    /** 情绪映射表（图纸 §4.3·锁死）。 */
    private fun moodOf(net: Int): Mood = when (net) {
        2 -> Mood("😄", "green", "心情很好")
        1 -> Mood("🙂", "green", "心情不错")
        -1 -> Mood("😕", "yellow", "有点低落")
        else -> Mood("😞", "red", "闷闷不乐") // net == -2
    }

    private data class Mood(val emoji: String, val colorName: String, val text: String)
}
