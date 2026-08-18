package com.situ.aichat.notification

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 调度器本地状态（P6.1c）。安卓 `AlarmManager` 无「列出待发闹钟」的 API（不像 iOS
 * `UNUserNotificationCenter.pendingNotificationRequests()`），所以调度器必须自己记账，才能支持：
 * - 重排前**取消旧闹钟**（按 requestKey）；
 * - **跨角色错峰**（拿到其他角色已排的触发时刻当 reservedDates）；
 * - **「正在看会话」撤回 15 分钟内将触发的通知**（6.1c-ii）；
 * - **状态快照**避免无谓重建（对齐 iOS `shouldRebuild`，iOS 用 UserDefaults）。
 *
 * 用 SharedPreferences 存三个 JSON 块（数据量极小）。判定逻辑（[shouldRebuild]/[reservedFireTimesExcluding]）
 * 为纯函数便于单测。
 */
@Singleton
class NotificationSchedulerStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences("notif_scheduler", Context.MODE_PRIVATE) }

    /** 一条已排通知的记账（requestKey 与精确闹钟 / WorkManager 唯一名一致）。 */
    @Serializable
    data class ScheduledRef(val requestKey: String, val fireAtMillis: Long, val category: String)

    /** 重建判定快照（对齐 iOS StateSnapshot）。 */
    @Serializable
    data class Snapshot(val date: String, val streakLabel: String, val eventCount: Int)

    // MARK: - 已排通知记账

    fun scheduledFor(characterId: String): List<ScheduledRef> = readRegistry()[characterId] ?: emptyList()

    /** 覆盖某角色的已排记账（重排时整批替换）。 */
    fun setScheduled(characterId: String, refs: List<ScheduledRef>) {
        val map = readRegistry().toMutableMap()
        if (refs.isEmpty()) map.remove(characterId) else map[characterId] = refs
        writeRegistry(map)
    }

    fun clearScheduled(characterId: String) = setScheduled(characterId, emptyList())

    fun allCharacterIds(): Set<String> = readRegistry().keys

    /** 其他角色已排的触发时刻（错峰打分用），排除 [excludingCharacterId] 自己。 */
    fun reservedFireTimesExcluding(excludingCharacterId: String): List<Long> =
        reservedFireTimesExcluding(readRegistry(), excludingCharacterId)

    // MARK: - 状态快照

    fun snapshot(characterId: String): Snapshot? = readSnapshots()[characterId]

    fun saveSnapshot(characterId: String, snapshot: Snapshot) {
        val map = readSnapshots().toMutableMap()
        map[characterId] = snapshot
        prefs.edit { putString(KEY_SNAPSHOTS, json.encodeToString(snapshotMapSerializer, map)) }
    }

    fun clearSnapshot(characterId: String) {
        val map = readSnapshots().toMutableMap()
        if (map.remove(characterId) != null) {
            prefs.edit { putString(KEY_SNAPSHOTS, json.encodeToString(snapshotMapSerializer, map)) }
        }
    }

    // MARK: - 随机通知「今天已判断」标记（对齐 iOS randomNotifDecided_{id}）

    fun randomDecidedDate(characterId: String): String? = readRandomDecided()[characterId]

    fun setRandomDecidedDate(characterId: String, dateString: String) {
        val map = readRandomDecided().toMutableMap()
        map[characterId] = dateString
        prefs.edit { putString(KEY_RANDOM_DECIDED, json.encodeToString(stringMapSerializer, map)) }
    }

    /** 删角色时清掉它的「今天已判断」标记（1:1 iOS 删 randomNotifDecided_{id} UserDefaults）。 */
    fun clearRandomDecidedDate(characterId: String) {
        val map = readRandomDecided().toMutableMap()
        if (map.remove(characterId) != null) {
            prefs.edit { putString(KEY_RANDOM_DECIDED, json.encodeToString(stringMapSerializer, map)) }
        }
    }

    // MARK: - 私有 I/O

    private fun readRegistry(): Map<String, List<ScheduledRef>> =
        prefs.getString(KEY_REGISTRY, null)?.let {
            runCatching { json.decodeFromString(registrySerializer, it) }
                .onFailure { Log.w(TAG, "调度记账解析失败(registry),按空表重建: ${it.message}") }
                .getOrNull()
        } ?: emptyMap()

    private fun writeRegistry(map: Map<String, List<ScheduledRef>>) {
        prefs.edit { putString(KEY_REGISTRY, json.encodeToString(registrySerializer, map)) }
    }

    private fun readSnapshots(): Map<String, Snapshot> =
        prefs.getString(KEY_SNAPSHOTS, null)?.let {
            runCatching { json.decodeFromString(snapshotMapSerializer, it) }
                .onFailure { Log.w(TAG, "调度记账解析失败(snapshots),按空表重建: ${it.message}") }
                .getOrNull()
        } ?: emptyMap()

    private fun readRandomDecided(): Map<String, String> =
        prefs.getString(KEY_RANDOM_DECIDED, null)?.let {
            runCatching { json.decodeFromString(stringMapSerializer, it) }
                .onFailure { Log.w(TAG, "调度记账解析失败(randomDecided),按空表重建: ${it.message}") }
                .getOrNull()
        } ?: emptyMap()

    companion object {
        private const val TAG = "NotifSchedulerStore"
        private const val KEY_REGISTRY = "registry"
        private const val KEY_SNAPSHOTS = "snapshots"
        private const val KEY_RANDOM_DECIDED = "random_decided"

        private val json = Json { ignoreUnknownKeys = true }
        private val registrySerializer =
            MapSerializer(String.serializer(), ListSerializer(ScheduledRef.serializer()))
        private val snapshotMapSerializer = MapSerializer(String.serializer(), Snapshot.serializer())
        private val stringMapSerializer = MapSerializer(String.serializer(), String.serializer())

        /** 是否需要重建该角色的通知（日期 / 火花状态 / 日程事件数任一变化 → 重建）。对齐 iOS shouldRebuild。纯函数。 */
        fun shouldRebuild(
            snapshot: Snapshot?,
            todayString: String,
            currentStreakLabel: String,
            currentEventCount: Int,
        ): Boolean {
            if (snapshot == null) return true
            if (snapshot.date != todayString) return true
            if (snapshot.streakLabel != currentStreakLabel) return true
            if (snapshot.eventCount != currentEventCount) return true
            return false
        }

        /** 从记账表取「除某角色外」所有已排触发时刻（错峰用）。纯函数。 */
        fun reservedFireTimesExcluding(
            registry: Map<String, List<ScheduledRef>>,
            excludingCharacterId: String,
        ): List<Long> = registry
            .filterKeys { it != excludingCharacterId }
            .values
            .flatten()
            .map { it.fireAtMillis }
    }
}
