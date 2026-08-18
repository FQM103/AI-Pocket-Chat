package com.situ.aichat.ui.world.starmap

import com.situ.aichat.util.StringListJson
import com.situ.aichat.world.social.WorldRelationshipTypes

/**
 * 关系星图（W10·图纸 §3.2/§3.4/§4.7）的 **UI 数据模型 + 纯分类映射**——逻辑层，零 Compose / 零 IO / 零时钟。
 *
 * [StarmapGraph] 由 [StarmapLayout.compute] 排布后产出：节点 / 边 / 待相识各带最终 world-space dp 坐标（[StarPoint]）
 * 与全部显示语义，Compose 端只读不算。分类纯函数（[closenessTier]/[colorPhraseOf]/[typesOrAcquainted]）供
 * `StarmapStrings` 上层查资源，T1-4 独立单测。
 */

/** world-space dp 坐标（原点=「你」·y 向下·同 demo `xy()`）。 */
data class StarPoint(val x: Float, val y: Float)

/** 已在世界的角色星（§3.2 节点集）。 */
data class StarNode(
    val characterUuid: String,
    val name: String,
    val avatarPath: String?,
    /** 与你亲密度 = CharacterEntity.relationshipQuality.closeness（定半径 + youTier）。 */
    val closeness: Int,
    /** 里程碑称谓（最新 MilestoneEntity·composeRelationshipDisplay 口径·无→null）。 */
    val milestoneTitle: String?,
    /** 副标：原住民=「{occupation} · {城名}」；你创建的角色=「你创建的角色 · {城名}」。 */
    val subtitle: String,
    val pos: StarPoint = StarPoint(0f, 0f),
)

/**
 * 两角色间的一条显示边（§3.2·两行有向边并成一条无向对）。
 * `a` = pairKey 字典序小的那向（主排序向·轨迹/渊源取此向），`b` = 另一向。
 */
data class StarEdge(
    val pairKey: String,
    val aUuid: String,
    val bUuid: String,
    val aName: String,
    val bName: String,
    val aAvatarPath: String?,
    val bAvatarPath: String?,
    /** 两向 typesJson 的并集（保 a→b 声明序·b→a 独有的追加尾部）。 */
    val types: List<String>,
    /** 主排序向（a→b）的 trajectoryRaw（warming/cooling/stable）。 */
    val trajectory: String,
    /** 两向 tension 的 max（≥40 → 结标记 / 气氛有些僵）。 */
    val tension: Int,
    val closenessForward: Int,
    val colorForward: String,
    val closenessReverse: Int,
    val colorReverse: String,
    /** 渊源 = 主排序向的 origin（空则卡内省略）。 */
    val origin: String,
    /** 近事（7 世界日内最新一条非 drift/compact·VM 批算·关系卡与列表模式共用·超窗/无→null）。 */
    val recent: StarRecent? = null,
    val aPos: StarPoint = StarPoint(0f, 0f),
    val bPos: StarPoint = StarPoint(0f, 0f),
) {
    /** 线宽用的两向 closeness 均值（§4.3）。 */
    val avgCloseness: Float get() = (closenessForward + closenessReverse) / 2f
}

/** 待相识原住民（§3.2·眼缘>0 已发现未招募）。 */
data class PendingStar(
    val nativeId: String,
    val name: String,
    val occupation: String,
    val cityId: String,
    val cityName: String,
    /** 眼缘朦胧四档短语（WorldAffinityStage.phrase·绝不读原始数字）。 */
    val stagePhrase: String,
    /** def 一句话（拼进待相识卡正文）。 */
    val oneLiner: String,
    /** 引荐人名（FACTORY_EDGES 邻居 ∩ 已招募·仅展示不消费·无→null）。 */
    val referrerName: String?,
    val pos: StarPoint = StarPoint(0f, 0f),
)

/** 排布后的全量图（纯 data·Compose 端零计算·§3.4 步 6）。 */
data class StarmapGraph(
    val nodes: List<StarNode>,
    val edges: List<StarEdge>,
    val pendings: List<PendingStar>,
) {
    val isEmpty: Boolean get() = nodes.isEmpty() && pendings.isEmpty()
}

/** 星图屏状态（§3.2·四路首发齐 + seed 就绪 → ready；选中卡由 selection id + 最新数据重 derive）。 */
data class StarmapUiState(
    val ready: Boolean = false,
    val graph: StarmapGraph? = null,
    val listMode: Boolean = false,
    val selection: StarmapSelection = StarmapSelection.None,
    val selectionCard: StarmapCard? = null,
)

/** 选中态（⚠️ 只存 id·绝不冻结实体·§3.2·9d 🔴-2c 教训）。 */
sealed interface StarmapSelection {
    data object None : StarmapSelection
    data object You : StarmapSelection
    data class Node(val characterUuid: String) : StarmapSelection
    data class Edge(val pairKey: String) : StarmapSelection
    data class Pending(val nativeId: String) : StarmapSelection
}

/** 边卡近事（§3.2·7 个世界日内最新一条非 drift/compact 事件）。 */
data class StarRecent(val relativeDay: RelativeDay, val summary: String)

/** 相对日分档（§4.7·同 WorldRelationshipDigest.renderRecent：0/1/2/3..7）。 */
enum class RelativeDay { TODAY, YESTERDAY, BEFORE, RECENT }

/** 人物卡「TA 的来往」一行（§4.7·本向色彩）。 */
data class NodeRelRow(
    val otherName: String,
    val otherAvatarPath: String?,
    val types: List<String>,
    /** 本向（该角色 → 对端）色彩 colorRaw。 */
    val colorRaw: String,
    val trajectory: String,
)

/** 选中卡（由 selection id 从最新数据重 derive·§3.2）。 */
sealed interface StarmapCard {
    data class You(val aroundCount: Int, val pendingCount: Int) : StarmapCard
    data class Node(val node: StarNode, val youCloseness: Int, val rows: List<NodeRelRow>) : StarmapCard
    data class Edge(val edge: StarEdge) : StarmapCard // 近事在 edge.recent
    data class Pending(val pending: PendingStar) : StarmapCard
}

/** 色彩句呈现三态（§4.7·表内查资源 / 表外原样 / 空省略）。 */
sealed interface ColorPhrase {
    /** 表内 13 色 → 资源键后缀（`world_starmap_color_<suffix>`）。 */
    data class Keyed(val keySuffix: String) : ColorPhrase
    /** 表外非空 → 原样显示 colorRaw。 */
    data class Raw(val text: String) : ColorPhrase
    /** 空串 → 省略该子句。 */
    data object Omit : ColorPhrase
}

/** 亲密四档分档（§4.7·closeness <15/<35/<70/≥70 → 1..4·WorldRelationshipDigest.kt:76-81 同界）。 */
fun closenessTier(closeness: Int): Int = when {
    closeness < 15 -> 1
    closeness < 35 -> 2
    closeness < 70 -> 3
    else -> 4
}

/**
 * colorRaw → 色彩句呈现（§4.7·13 色查表·表外原样·空省略）。关系类型全谱同理无白名单——本函数只管色彩子句。
 * 键后缀锁死对齐 §4.7 字符串表。
 */
fun colorPhraseOf(colorRaw: String): ColorPhrase {
    if (colorRaw.isBlank()) return ColorPhrase.Omit
    val suffix = COLOR_PHRASE_KEYS[colorRaw] ?: return ColorPhrase.Raw(colorRaw)
    return ColorPhrase.Keyed(suffix)
}

/** typesJson → 类型列表；空 / 解码失败（decode 不抛·坏数据→空）→ 单类型「相识」（§5 E5·digest renderTypes 同口径）。 */
fun typesOrAcquainted(typesJson: String): List<String> =
    StringListJson.decode(typesJson).ifEmpty { listOf(WorldRelationshipTypes.TYPE_ACQUAINTED) }

/** colorRaw → 资源键后缀（13 色·锁死·§4.7）。 */
private val COLOR_PHRASE_KEYS: Map<String, String> = mapOf(
    "好奇" to "curious",
    "投缘" to "kindred",
    "感激" to "grateful",
    "护着" to "protective",
    "惦记" to "missing",
    "敬重" to "respect",
    "别扭" to "awkward",
    "较劲" to "rivalry",
    "释然" to "relieved",
    "更亲近" to "closer",
    "淡漠" to "distant",
    "心动" to "heartbeat",
    "暗恋" to "crush",
)
