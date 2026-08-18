package com.situ.aichat.world.link

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEventEntity
import com.situ.aichat.util.StringListJson
import com.situ.aichat.world.WorldClock
import com.situ.aichat.world.WorldIds
import java.time.ZoneId

/**
 * 关系提炼**纯函数**（W5 图纸 §3.3 / §4.1 / 契约 §9「关系 → 上下文提炼」·零 LLM·格式字面量锁死·图纸 §9 禁改）：
 * 把当前角色的多维关系边压成 N=3 条「人话」提炼行，注入聊天让角色真实记得彼此，又不撑爆 prompt。
 *
 * 选 3（锁死顺序·去重后取前 3）：① 话题相关（对端名出现在 query·≤1）② 最近有事（7 天内近事·happenedAt 降序）
 * ③ 最亲（closeness 降序·平手 uuid 升序）。渲染格式 = §4.1（`【与{对端名}｜{types}】{状态句}{近事句}`）逐字锁死。
 * `internal` 便于 T1 单测。入参已由 [WorldChatContextProvider] 预过滤（edges: fromId=self·!dormant·对端在世界内）。
 */
internal object WorldRelationshipDigest {

    /** N=3 提炼行上限（图纸 §9 禁改·契约决策 19）。 */
    const val MAX_LINES = 3

    /**
     * @param edges 本视角出向边（fromId=self·!dormant·对端在世界内）
     * @param recentEventByPair pairKey → 该对 7 天内最新一条非 rel_compact 事件（无则不含该键）
     * @param namesById uuid → 对端名
     */
    fun build(
        self: CharacterEntity,
        edges: List<WorldRelationshipEntity>,
        recentEventByPair: Map<String, WorldRelationshipEventEntity>,
        namesById: Map<String, String>,
        queryText: String,
        nowMs: Long,
        zone: ZoneId,
    ): List<String> {
        if (edges.isEmpty()) return emptyList()
        val selected = LinkedHashSet<String>() // 去重·保序（toId）

        // ① 话题相关 ≤1：对端名（长度 ≥2）子串出现在 query → 命中里 closeness 最高（平手 uuid 升序）。
        edges.filter { e ->
            val name = namesById[e.toId]
            name != null && name.length >= 2 && queryText.contains(name)
        }.sortedWith(compareByDescending<WorldRelationshipEntity> { it.closeness }.thenBy { it.toId })
            .firstOrNull()?.let { selected.add(it.toId) }

        // ② 最近有事：有 7 天内近事的对，按事件 happenedAt 降序（平手 uuid 升序）。
        edges.filter { recentEventByPair.containsKey(WorldIds.pairKey(self.uuid, it.toId)) }
            .sortedWith(
                compareByDescending<WorldRelationshipEntity> { recentEventByPair.getValue(WorldIds.pairKey(self.uuid, it.toId)).happenedAt }
                    .thenBy { it.toId },
            ).forEach { selected.add(it.toId) }

        // ③ 最亲：closeness 降序（平手 uuid 升序）。
        edges.sortedWith(compareByDescending<WorldRelationshipEntity> { it.closeness }.thenBy { it.toId })
            .forEach { selected.add(it.toId) }

        val byId = edges.associateBy { it.toId }
        return selected.take(MAX_LINES).map { toId ->
            render(byId.getValue(toId), namesById.getValue(toId), recentEventByPair[WorldIds.pairKey(self.uuid, toId)], nowMs, zone)
        }
    }

    /** 单条提炼行渲染（§4.1·锁死）：`【与{名}｜{types}】{状态句}{近事句}`。 */
    private fun render(edge: WorldRelationshipEntity, name: String, recent: WorldRelationshipEventEntity?, nowMs: Long, zone: ZoneId): String =
        "【与$name｜${renderTypes(edge.typesJson)}】${renderStatus(edge.closeness, edge.colorRaw)}${renderRecent(edge, recent, nowMs, zone)}"

    /** {types}：typesJson 数组末尾至多 2 个·保序·`·` 连接；空 → `相识`。 */
    private fun renderTypes(typesJson: String): String {
        val types = StringListJson.decode(typesJson)
        return if (types.isEmpty()) "相识" else types.takeLast(2).joinToString("·")
    }

    /** {状态句} = {亲密档}，{色彩句}；色彩不在表内（含空串）→ 省略色彩句连同逗号。 */
    private fun renderStatus(closeness: Int, colorRaw: String): String {
        val intimacy = when {
            closeness < 15 -> "你们刚认识不久"
            closeness < 35 -> "你们正慢慢熟络起来"
            closeness < 70 -> "你们关系不错"
            else -> "你们交情很深"
        }
        val color = COLOR_CLAUSES[colorRaw]
        return if (color != null) "$intimacy，$color" else intimacy
    }

    /** {近事句}：7 天内近事存在时 = [气氛有些僵前缀？]；{相对日}：{summary}；无近事整句省略。 */
    private fun renderRecent(edge: WorldRelationshipEntity, recent: WorldRelationshipEventEntity?, nowMs: Long, zone: ZoneId): String {
        recent ?: return ""
        val tense = if (edge.tension >= 40 && edge.colorRaw != "别扭" && edge.colorRaw != "较劲") "，气氛有些僵" else ""
        val diff = WorldClock.localDateOf(nowMs, zone).toEpochDay() - WorldClock.localDateOf(recent.happenedAt, zone).toEpochDay()
        val relDay = when (diff) {
            0L -> "今天"
            1L -> "昨天"
            2L -> "前天"
            else -> "几天前" // 3..7
        }
        return "$tense；$relDay：${recent.summary}"
    }

    /** 情感色彩 → 色彩句映射（13 色·锁死·图纸 §4.1/§9）。 */
    private val COLOR_CLAUSES: Map<String, String> = mapOf(
        "好奇" to "你对 TA 挺好奇",
        "投缘" to "和 TA 很聊得来",
        "感激" to "你心里记着 TA 的好",
        "护着" to "你有点护着 TA",
        "惦记" to "你时常惦记 TA",
        "敬重" to "你打心底敬重 TA",
        "别扭" to "眼下和 TA 有点别扭",
        "较劲" to "你俩暗暗较着劲",
        "释然" to "前阵子的疙瘩解开了",
        "更亲近" to "最近走得更近了",
        "淡漠" to "你对 TA 淡淡的",
        "心动" to "你对 TA 有点心动",
        "暗恋" to "你悄悄喜欢着 TA",
    )
}
