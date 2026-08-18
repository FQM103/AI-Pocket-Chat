package com.situ.aichat.ui.world.starmap

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.local.entity.WorldNativeStateEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEventEntity
import com.situ.aichat.data.model.relationshipQuality
import com.situ.aichat.prompt.growth.composeRelationshipDisplay
import com.situ.aichat.world.WorldClock
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.cast.WorldAffinityService
import com.situ.aichat.world.cast.WorldNativeDef
import com.situ.aichat.world.cast.WorldNativeRoster
import com.situ.aichat.world.social.WorldRelationshipBeats
import com.situ.aichat.world.social.WorldRelationshipTypes
import java.time.ZoneId

/**
 * 星图**纯派生**（W10 图纸 §3.2）：把 Room 活数据（关系边 / 角色 / 里程碑 / 原住民态）折成 [StarmapGraph]
 * 与选中卡——零 IO / 零 Compose / 零时钟（now/zone 由 VM 喂）。抽出 VM 使其薄、令派生可 T2 直测。
 * `WorldRelationshipDigest` 的关系提炼格式**零碰**（星图文案全走 R.string·§6）。
 */
internal object StarmapDerive {

    /** 四路活数据 + seed + 「你创建的角色」标签（VM 已从资源取）+ 近事批（VM 已按 now/zone 算）→ 排布后的全量图。 */
    fun buildGraph(
        edges: List<WorldRelationshipEntity>,
        chars: List<CharacterEntity>,
        milestones: List<MilestoneEntity>,
        natives: List<WorldNativeStateEntity>,
        seed: Long,
        userMadeLabel: String,
        recentByPair: Map<String, StarRecent?> = emptyMap(),
    ): StarmapGraph {
        val nodeChars = chars.filter { it.joinedWorld }
        val charByUuid = nodeChars.associateBy { it.uuid }

        val latestMilestone = milestones.groupBy { it.characterUuid }
            .mapValues { (_, ms) -> ms.maxByOrNull { it.establishedDate } }
        val nativeByRecruitedUuid = natives
            .filter { it.recruitedCharacterUuid != null }
            .associateBy { it.recruitedCharacterUuid!! }

        val nodes = nodeChars.map { c ->
            val recruitedDef = nativeByRecruitedUuid[c.uuid]?.let { WorldNativeRoster.byNativeId(it.nativeId) }
            val subtitle = if (recruitedDef != null) {
                "${recruitedDef.occupation} · ${WorldNativeDef.cityNameOf(recruitedDef.cityId, seed)}"
            } else {
                "$userMadeLabel · ${WorldNativeDef.cityNameOf(c.worldHomeCityId, seed)}"
            }
            StarNode(
                characterUuid = c.uuid,
                name = c.name,
                avatarPath = c.avatarPath,
                closeness = c.relationshipQuality.closeness,
                milestoneTitle = latestMilestone[c.uuid]?.let { composeRelationshipDisplay(it.relationshipName, it.phase) },
                subtitle = subtitle,
            )
        }

        // 显示边：!dormant，按 pairKey 并成无向对；端点任一不在节点集 → 丢弃（E3）。
        val displayEdges = edges.filter { !it.dormant }
            .groupBy { WorldIds.pairKey(it.fromId, it.toId) }
            .mapNotNull { (pairKey, rows) -> displayEdge(pairKey, rows, charByUuid, recentByPair[pairKey]) }

        // 待相识：已发现且未招募·花名册查无 def → 跳过（E6）。
        val recruitedSlugToName = natives.filter { it.recruitedCharacterUuid != null }.mapNotNull { st ->
            val slug = WorldNativeRoster.byNativeId(st.nativeId)?.slug ?: return@mapNotNull null
            val name = charByUuid[st.recruitedCharacterUuid]?.name ?: return@mapNotNull null
            slug to name
        }.toMap()
        val pendings = natives.filter { it.discovered && it.recruitedCharacterUuid == null }.mapNotNull { st ->
            val def = WorldNativeRoster.byNativeId(st.nativeId) ?: return@mapNotNull null
            PendingStar(
                nativeId = st.nativeId,
                name = def.name,
                occupation = def.occupation,
                cityId = def.cityId,
                cityName = WorldNativeDef.cityNameOf(def.cityId, seed),
                stagePhrase = WorldAffinityService.stageOf(st, def).phrase,
                oneLiner = def.oneLiner,
                // 引荐提示：该 slug 的出厂边邻居 ∩ 已招募（声明序取第一个）·只展示不消费（W12）。
                referrerName = WorldNativeRoster.factoryNeighborsOf(def.slug).firstNotNullOfOrNull { recruitedSlugToName[it.slug] },
            )
        }

        return StarmapLayout.compute(nodes, displayEdges, pendings)
    }

    /** 两向有向边并成一条显示边（a = 字典序小向·轨迹/渊源取此向·类型并集保序·tension 取两向 max）。 */
    private fun displayEdge(
        pairKey: String,
        rows: List<WorldRelationshipEntity>,
        charByUuid: Map<String, CharacterEntity>,
        recent: StarRecent?,
    ): StarEdge? {
        val first = rows.first()
        val a = minOf(first.fromId, first.toId)
        val b = maxOf(first.fromId, first.toId)
        val aChar = charByUuid[a] ?: return null // 端点不在节点集 → 丢弃（E3）
        val bChar = charByUuid[b] ?: return null
        val aRow = rows.firstOrNull { it.fromId == a }
        val bRow = rows.firstOrNull { it.fromId == b }
        val main = aRow ?: bRow ?: return null
        val aTypes = aRow?.let { typesOrAcquainted(it.typesJson) } ?: emptyList()
        val bTypes = bRow?.let { typesOrAcquainted(it.typesJson) } ?: emptyList()
        val types = (aTypes + bTypes.filter { it !in aTypes }).ifEmpty { listOf(WorldRelationshipTypes.TYPE_ACQUAINTED) }
        return StarEdge(
            pairKey = pairKey,
            aUuid = a, bUuid = b,
            aName = aChar.name, bName = bChar.name,
            aAvatarPath = aChar.avatarPath, bAvatarPath = bChar.avatarPath,
            types = types,
            trajectory = main.trajectoryRaw,
            tension = maxOf(aRow?.tension ?: 0, bRow?.tension ?: 0),
            closenessForward = aRow?.closeness ?: 0, colorForward = aRow?.colorRaw ?: "",
            closenessReverse = bRow?.closeness ?: 0, colorReverse = bRow?.colorRaw ?: "",
            origin = main.origin,
            recent = recent,
        )
    }

    /** 人物卡「TA 的来往」行（该角色所有显示边·色彩取本向）。 */
    fun nodeCard(node: StarNode, graph: StarmapGraph): StarmapCard.Node {
        val rows = graph.edges.filter { it.aUuid == node.characterUuid || it.bUuid == node.characterUuid }.map { e ->
            val isA = e.aUuid == node.characterUuid
            NodeRelRow(
                otherName = if (isA) e.bName else e.aName,
                otherAvatarPath = if (isA) e.bAvatarPath else e.aAvatarPath,
                types = e.types,
                colorRaw = if (isA) e.colorForward else e.colorReverse,
                trajectory = e.trajectory,
            )
        }
        return StarmapCard.Node(node, node.closeness, rows)
    }

    /** 边卡近事：尾部最新一条非 drift/compact 且 7 个世界日内的事件·相对日分档 0/1/2/3..7·超窗→null。 */
    fun starRecentOf(events: List<WorldRelationshipEventEntity>, nowMs: Long, zone: ZoneId): StarRecent? {
        val e = events.lastOrNull { it.kindRaw != WorldRelationshipBeats.DRIFT && it.kindRaw != WorldRelationshipBeats.COMPACT }
            ?: return null
        val diff = WorldClock.localDateOf(nowMs, zone).toEpochDay() - WorldClock.localDateOf(e.happenedAt, zone).toEpochDay()
        if (diff < 0 || diff > 7) return null
        val rel = when (diff) {
            0L -> RelativeDay.TODAY
            1L -> RelativeDay.YESTERDAY
            2L -> RelativeDay.BEFORE
            else -> RelativeDay.RECENT
        }
        return StarRecent(rel, e.summary)
    }

    /** 目标实体消失（删角 / 离开世界 / 被招募走）→ 选中复位 None、卡自动收起（E4）。 */
    fun normalizeSelection(sel: StarmapSelection, graph: StarmapGraph): StarmapSelection = when (sel) {
        StarmapSelection.None, StarmapSelection.You -> sel
        is StarmapSelection.Node -> if (graph.nodes.any { it.characterUuid == sel.characterUuid }) sel else StarmapSelection.None
        is StarmapSelection.Edge -> if (graph.edges.any { it.pairKey == sel.pairKey }) sel else StarmapSelection.None
        is StarmapSelection.Pending -> if (graph.pendings.any { it.nativeId == sel.nativeId }) sel else StarmapSelection.None
    }
}
