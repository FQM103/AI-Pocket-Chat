package com.situ.aichat.notification

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 「发出即投递」标记缓冲（P6.1d）。安卓没有 iOS 的「通知已送达」回调，故通知**真正弹出时**
 * （[Notifier.post]）落一条本标记；App 回前台时 [StreakNotificationBridgeService] 把标记排干、
 * 建 [com.situ.aichat.data.local.entity.NotificationDeliveryRecordEntity] 并物化成会话消息。
 *
 * **为何用轻量标记而非到点直写 Room**：通知弹出常发生在 App 被国行 ROM 杀掉、由 `AlarmManager` 广播
 * 拉起 [NotificationAlarmReceiver] 的最脆弱时刻——此处只 `commit` 一次 SharedPreferences（同步落盘、
 * 无需 Hilt 图 / 无需开 Room），最稳；所有入库 / 物化的重活留到回前台的协程里做（**最贴 iOS：iOS 在
 * 弹出时也什么都不做，全部 DB 工作在前台扫描时完成**）。
 *
 * 是 Kotlin `object`（同 [Notifier]）：[NotificationAlarmReceiver] 非 Hilt，可直接静态调用、无需注入。
 */
object PendingDeliveryStore {

    private const val TAG = "PendingDeliveryStore"
    private const val PREFS_NAME = "notif_pending_delivery"
    private const val KEY_MARKERS = "markers"

    private val json = Json { ignoreUnknownKeys = true }
    private val markerListSerializer = ListSerializer(PendingDelivery.serializer())
    private val lock = Any()

    /** 一条待物化通知的快照（发出时记下，回前台据此建台账 + 插消息）。 */
    @Serializable
    data class PendingDelivery(
        val deliveryIdentifier: String,
        val characterId: String,
        val category: String,
        val conversationUuid: String,
        val notificationBody: String,
        val requestIdentifier: String,
        val scheduledAt: Long,
        val deliveredAt: Long,
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 通知发出时追加一条标记（同步落盘，扛进程被杀）。并发弹出用进程内锁串行化读-改-写。 */
    fun appendDelivered(context: Context, marker: PendingDelivery) {
        synchronized(lock) {
            val updated: List<PendingDelivery> = read(context) + marker
            runCatching {
                prefs(context).edit(commit = true) {
                    putString(KEY_MARKERS, json.encodeToString(markerListSerializer, updated))
                }
            }.onFailure { Log.w(TAG, "写待物化标记失败", it) }
        }
    }

    /** 取出全部标记并清空（回前台物化前调一次）。失败 / 无数据返回空表。 */
    fun drainAll(context: Context): List<PendingDelivery> = synchronized(lock) {
        val current = read(context)
        if (current.isNotEmpty()) {
            prefs(context).edit { remove(KEY_MARKERS) }
        }
        current
    }

    private fun read(context: Context): List<PendingDelivery> {
        val raw = prefs(context).getString(KEY_MARKERS, null) ?: return emptyList()
        return runCatching { json.decodeFromString(markerListSerializer, raw) }
            .onFailure { Log.w(TAG, "解析待物化标记失败，丢弃", it) }
            .getOrDefault(emptyList())
    }
}
