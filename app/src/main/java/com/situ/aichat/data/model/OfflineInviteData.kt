package com.situ.aichat.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 线下见面 邀约/结束卡片的结构化数据（1:1 iOS `OfflineInviteData`，存在 `MessageEntity.content` 的 JSON）。
 * [MessageKind.OFFLINE_INVITE_CARD] / [MessageKind.OFFLINE_END_CARD] 的关联数据。
 *
 * 字段名 1:1 iOS（camelCase：tensionHint/hiddenTension/finalMood），与 iOS 持久化卡片字节兼容
 * （iOS 该 struct 用默认 Codable 键 = camelCase；LLM 工具输出的 snake_case 是另一条解析路径，10.2c 处理）。
 *
 * @param type 判别："offline_invite" / "offline_end"（解析时校验）。
 * @param tensionHint 邀约卡显示的隐晦暗示（≤12 字，不剧透；前端可见）。
 * @param hiddenTension 完整心事种子（给 LLM 常驻指令，前端不显示；进入时写入 markerStart 消息）。
 * @param farewell 已废弃，仅兼容老数据（新路径告别走内容块）。
 * @param finalMood 结束情绪基调：warm/sweet/melancholic/awkward/neutral。
 * @param responded 用户响应："accepted"/"declined"/"continued"（再待一会儿）/null（未响应）。
 */
@Serializable
data class OfflineInviteData(
    val type: String,
    val location: String? = null,
    val activity: String? = null,
    val invitation: String? = null,
    val tensionHint: String? = null,
    val hiddenTension: String? = null,
    val farewell: String? = null,
    val finalMood: String? = null,
    val responded: String? = null,
)

/** 线下邀约/结束卡 JSON 编解码（1:1 iOS `parseOfflineInvite`/`makeOfflineInviteContent`；encodeDefaults=false 省略 null）。 */
object OfflineInviteJson {
    const val TYPE_INVITE = "offline_invite"
    const val TYPE_END = "offline_end"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(data: OfflineInviteData): String = json.encodeToString(OfflineInviteData.serializer(), data)

    /**
     * 解析消息内容为邀约/结束卡（1:1 iOS `parseOfflineInvite`）：
     * - 以 "{" 开头（排除 plainText）；type ∈ {offline_invite, offline_end}；失败 → null。
     */
    fun parse(content: String): OfflineInviteData? {
        if (!content.startsWith("{")) return null
        val data = runCatching { json.decodeFromString<OfflineInviteData>(content) }.getOrNull() ?: return null
        return if (data.type == TYPE_INVITE || data.type == TYPE_END) data else null
    }

    /** 邀约卡 JSON（= iOS `makeOfflineInviteContent`）。 */
    fun makeInvite(
        location: String,
        activity: String,
        invitation: String,
        tensionHint: String? = null,
        hiddenTension: String? = null,
    ): String = encode(
        OfflineInviteData(
            type = TYPE_INVITE,
            location = location,
            activity = activity,
            invitation = invitation,
            tensionHint = tensionHint?.takeIf { it.isNotBlank() },
            hiddenTension = hiddenTension?.takeIf { it.isNotBlank() },
        ),
    )

    /**
     * 结束确认卡 JSON（responded=null，用户点卡片按钮后由状态机改写）。
     * [farewell] 已废弃，仅在 LLM 违反协议填了 farewell（或降级路径）时写入以兼容展示（1:1 iOS handleOfflineMeetingAction endMeeting）。
     */
    fun makeEnd(finalMood: String?, farewell: String? = null): String =
        encode(
            OfflineInviteData(
                type = TYPE_END,
                farewell = farewell?.takeIf { it.isNotBlank() },
                finalMood = finalMood?.takeIf { it.isNotBlank() },
            ),
        )
}
