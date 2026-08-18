package com.situ.aichat.ui.starfield

import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.local.entity.PromiseEntity
import kotlin.math.min

/**
 * 记忆星空数据模型（图纸 2026-07-16-记忆星空 §3.1）：三源（里程碑 / 线下见面 / 已兑现约定）的**只读投影**，
 * 零新增表。星 = 一件真实发生过的事；簇 = 一个月的「星座」。
 */

/** 星的三类来源（晕色 / 核径映射见图纸 §4.3）。 */
enum class StarType { MEETING, PROMISE, MILESTONE }

/**
 * 一颗记忆星（图纸 §3.1 锁定字段 = [id]/[type]/[timestampMillis]/[title]/[weight]/[nova]）。
 *
 * [weight] = §4.3 星等输入（= 纵深衰减前的核径 dp）；[nova] = 上次访问后新发生（图纸 J4）。
 * 其余字段 = 详情 sheet（§4.8）三分支载荷——图纸 §3.1 未列，见施工日志 D-2。
 */
data class StarNode(
    /** 源实体 uuid（见面星另作 [com.situ.aichat.ui.offline.MeetingSkyBackdrop] 的 seed）。 */
    val id: String,
    val type: StarType,
    /** MILESTONE=establishedDate / MEETING=startedAtMillis / PROMISE=resolvedAtMillis。<=0 = legacy 见面（图纸 J7）。 */
    val timestampMillis: Long,
    /** MILESTONE=relationshipName / MEETING=activity 空则 location 空则「一次见面」 / PROMISE=content。 */
    val title: String,
    val weight: Float,
    val nova: Boolean = false,
    /** sheet 载荷：见面心情原始值（warm/sweet/melancholic/awkward/neutral/""）。 */
    val moodRaw: String = "",
    /** sheet 载荷：正文——MEETING=summary / MILESTONE=reason / PROMISE 无。 */
    val body: String = "",
    /** sheet 载荷：MILESTONE 的关系阶段（无则空）。 */
    val phase: String = "",
    /** sheet 载荷：见面发起方（未知 null）。 */
    val initiatedByUser: Boolean? = null,
)

/** 布局后的一颗星（坐标 = 画布空间 dp·见 [StarfieldLayout]）。 */
data class PlacedStar(
    val node: StarNode,
    val xDp: Float,
    val yDp: Float,
    /** 纵深衰减后的核径（图纸 §3.2-4）。 */
    val radiusDp: Float,
    /** 纵深衰减后的整星 alpha 系数（§4.3 各层 α 再乘之）。 */
    val alpha: Float,
    /** 簇内 weight 最大者 → 十字芒（图纸 §3.2-7）。 */
    val hero: Boolean,
)

/** 簇内相邻星的连线（图纸 §3.2-5·每簇最多 4 条）。 */
data class StarLink(
    val fromXDp: Float,
    val fromYDp: Float,
    val toXDp: Float,
    val toYDp: Float,
)

/** 一个月的星座（图纸 §3.2）。[depthIndex] 0 = 最近（最下最亮）。 */
data class StarCluster(
    /** 月份标文本：「N月」/「YYYY年N月」/「往昔」。 */
    val label: String,
    val centerXDp: Float,
    val centerYDp: Float,
    val depthIndex: Int,
    /** 时间升序（连线与 hero 判定依赖此序）。 */
    val stars: List<PlacedStar>,
    val links: List<StarLink>,
)

/** 星空页状态（图纸 §3.3）。 */
data class StarfieldUiState(
    val clusters: List<StarCluster> = emptyList(),
    val canvasHeightDp: Float = 0f,
    val starCount: Int = 0,
    /** 单发流星标志：Canvas 播完置回（图纸 §4.5）。 */
    val showMeteor: Boolean = false,
    val selected: StarNode? = null,
    val loading: Boolean = true,
)

/** 「故事」Tab 入口卡状态（图纸 §3.4）。 */
data class EntryCardState(
    val starCount: Int = 0,
    val clusterCount: Int = 0,
    val hasNova: Boolean = false,
)

/**
 * 三源实体 → [StarNode] 的投影（图纸 §3.1·纯函数·星空页与入口卡两 VM 共用单源）。
 *
 * 星等（[StarNode.weight] = 纵深衰减前核径 dp）映射锁定于图纸 §4.3。**约定只收 FULFILLED**——
 * CANCELLED 绝不成星，由调用方先过滤（[build] 再以 `resolvedAtMillis` 非空二次守卫脏数据·E6）。
 */
internal object StarNodes {

    /** 「空态唯一星」（全场仅此一颗里程碑）恒 4.4（图纸 §4.3·见施工日志 D-7）。 */
    const val LONE_MILESTONE_WEIGHT = 4.4f

    /**
     * @param fulfilledPromises 已由调用方过滤为 [com.situ.aichat.data.local.entity.PromiseStatus.FULFILLED]。
     * @param lastVisitMillis 上次访问星空时刻（0 = 首访 → 全场无 nova·图纸 J4）。
     */
    fun build(
        milestones: List<MilestoneEntity>,
        meetings: List<OfflineMeetingMemoryEntity>,
        fulfilledPromises: List<PromiseEntity>,
        lastVisitMillis: Long,
    ): List<StarNode> {
        val nodes = ArrayList<StarNode>(milestones.size + meetings.size + fulfilledPromises.size)
        milestones.mapTo(nodes) { it.toStarNode(lastVisitMillis) }
        meetings.mapTo(nodes) { it.toStarNode(lastVisitMillis) }
        fulfilledPromises.mapNotNullTo(nodes) { it.toStarNodeOrNull(lastVisitMillis) }
        // 空态唯一星：只剩一颗里程碑时恒 4.4——默认 reason「初始设定」算出来只有 3.09，孤星会小到看不见。
        val lone = nodes.singleOrNull()
        return if (lone != null && lone.type == StarType.MILESTONE) listOf(lone.copy(weight = LONE_MILESTONE_WEIGHT)) else nodes
    }

    /** 里程碑星：核径 3.0–4.4（reason 长度加权）。 */
    private fun MilestoneEntity.toStarNode(lastVisit: Long) = StarNode(
        id = uuid,
        type = StarType.MILESTONE,
        timestampMillis = establishedDate,
        title = relationshipName,
        weight = 3.0f + 1.4f * min(reason.length / 60f, 1f),
        nova = isNova(establishedDate, lastVisit),
        body = reason,
        phase = phase.orEmpty(),
    )

    /**
     * 见面星：核径 3.2–4.6（对话轮数加权 = 时长感）。标题空（activity/location 皆空）时由 UI 层补
     * 「一次见面」资源文案——逻辑层不碰字符串资源（施工日志 D-8）。legacy 行 startedAtMillis=0 → 往昔簇（J7）。
     */
    private fun OfflineMeetingMemoryEntity.toStarNode(lastVisit: Long) = StarNode(
        id = uuid,
        type = StarType.MEETING,
        timestampMillis = startedAtMillis,
        title = activity.ifBlank { location },
        weight = 3.2f + 1.4f * min(messageCount / 80f, 1f),
        nova = isNova(startedAtMillis, lastVisit),
        moodRaw = moodRaw,
        body = summary,
        initiatedByUser = initiatedByUser,
    )

    /** 兑现约定星：核径 2.4–3.0（内容长度加权）。`resolvedAtMillis` 为空的脏数据跳过不崩（E6）。 */
    private fun PromiseEntity.toStarNodeOrNull(lastVisit: Long): StarNode? {
        val resolvedAt = resolvedAtMillis ?: return null
        return StarNode(
            id = uuid,
            type = StarType.PROMISE,
            timestampMillis = resolvedAt,
            title = content,
            weight = 2.4f + 0.6f * min(content.length / 40f, 1f),
            nova = isNova(resolvedAt, lastVisit),
        )
    }

    /** 新星 = 上次访问之后发生；首访（[lastVisit] == 0）全场无 nova（图纸 J4）。 */
    private fun isNova(timestampMillis: Long, lastVisit: Long): Boolean =
        lastVisit > 0L && timestampMillis > lastVisit
}
