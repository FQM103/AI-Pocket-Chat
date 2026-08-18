package com.situ.aichat.ui.world.starmap

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 关系星图**确定性排布纯函数**（W10·图纸 §3.4·决策 25「干净亲疏排布」的算法定义）。
 *
 * 零 IO / 零 Compose / 零时钟——输入全由 VM 喂，**同输入必同输出**（T1-1）。坐标系 = world-space dp，
 * 原点=「你」，y 向下（同 demo `xy()`）；角度 −90°=正上方，顺时针（角度增大）累进。五步：半径映射（亲疏轴）→
 * 连通分量分簇 → 簇间分扇+扇内均布 → 碰撞微调（确定性迭代·非物理引擎）→ 待相识外圈错层。`internal` 便于 T1。
 */
internal object StarmapLayout {

    // §3.4 锁死常量（图纸 §9 禁改·不得换默认值 / 四舍五入）。
    private const val R_BASE = 330.0          // r(clo)=330 − 190·clo/100
    private const val R_SPAN = 190.0
    private const val CLUSTER_GAP_DEG = 18.0  // 簇间隙
    private const val START_ANGLE_DEG = -90.0 // 首簇起始角=正上方
    private const val COLLISION_MIN_DP = 64.0 // 两心距阈值
    private const val COLLISION_MAX_ROUNDS = 24
    private const val COLLISION_NUDGE_DEG = 1.5
    private const val PENDING_R_EVEN = 436f    // 待相识外圈偶数位（错层防标签打架）
    private const val PENDING_R_ODD = 456f     // 奇数位

    /** 半径（亲疏轴·§3.4-1）：clo 0..100 → r 330..140，四舍五入到整 dp。 */
    fun radiusOf(closeness: Int): Float = (R_BASE - R_SPAN * closeness / 100.0).roundToInt().toFloat()

    /** 排布（§3.4 全五步）：输入节点/显示边/待相识（无坐标），输出各带最终 xy 的 [StarmapGraph]。 */
    fun compute(nodes: List<StarNode>, edges: List<StarEdge>, pendings: List<PendingStar>): StarmapGraph {
        if (nodes.isEmpty()) {
            return StarmapGraph(emptyList(), edges.map { it.copy(aPos = ORIGIN, bPos = ORIGIN) }, layoutPendings(pendings))
        }

        // 步 2：显示边的连通分量分簇；无边节点各自成单簇。簇排序 =（节点数 desc, 簇内最小 uuid asc）。
        val clusters = connectedComponents(nodes, edges)
            .map { members -> members.sortedWith(compareByDescending<StarNode> { it.closeness }.thenBy { it.characterUuid }) }
            .sortedWith(compareByDescending<List<StarNode>> { it.size }.thenBy { it.minOf { n -> n.characterUuid } })

        // 步 3：分扇 + 扇内均布。可用角 = 360 − 18×簇数；簇 k 扇宽 = 可用角 × n_k/N。
        val total = nodes.size
        val available = 360.0 - CLUSTER_GAP_DEG * clusters.size
        val placed = ArrayList<Placed>(total) // 保簇序（供入场 delay 的 i 按簇序）
        var cursor = START_ANGLE_DEG
        for (cluster in clusters) {
            val width = available * cluster.size / total
            cluster.forEachIndexed { j, node ->
                val angle = cursor + width * (j + 0.5) / cluster.size
                placed += Placed(node, angle, radiusOf(node.closeness))
            }
            cursor += width + CLUSTER_GAP_DEG
        }

        // 步 4：碰撞微调（最多 24 轮·每轮按 (uuidA,uuidB) 字典序扫所有对·两心距<64dp 各沿自身角向 ±1.5°）。
        val byUuidOrder = placed.sortedBy { it.node.characterUuid } // 与 placed 共享同一批 Placed 对象·就地改 angle
        run {
            repeat(COLLISION_MAX_ROUNDS) {
                var changed = false
                for (i in byUuidOrder.indices) {
                    for (k in i + 1 until byUuidOrder.size) {
                        val p = byUuidOrder[i]
                        val q = byUuidOrder[k]
                        if (dist(p, q) < COLLISION_MIN_DP) {
                            // 角小者 −、角大者 +（平角 → uuid 小者 −·扫描序已保 p.uuid < q.uuid）。
                            val pIsSmaller = p.angle < q.angle || p.angle == q.angle
                            if (pIsSmaller) { p.angle -= COLLISION_NUDGE_DEG; q.angle += COLLISION_NUDGE_DEG }
                            else { p.angle += COLLISION_NUDGE_DEG; q.angle -= COLLISION_NUDGE_DEG }
                            changed = true
                        }
                    }
                }
                if (!changed) return@run // 一轮零调整 → 提前结束
            }
        }

        val posByUuid = placed.associate { it.node.characterUuid to polar(it.angle, it.radius.toDouble()) }
        val outNodes = placed.map { it.node.copy(pos = posByUuid.getValue(it.node.characterUuid)) }
        val outEdges = edges.map {
            it.copy(
                aPos = posByUuid[it.aUuid] ?: ORIGIN,
                bPos = posByUuid[it.bUuid] ?: ORIGIN,
            )
        }
        return StarmapGraph(outNodes, outEdges, layoutPendings(pendings))
    }

    /** 步 5：待相识外圈（按 nativeId asc·第 i/M 个角 = −90 + 360×(i+0.5)/M·半径偶 436/奇 456）。 */
    private fun layoutPendings(pendings: List<PendingStar>): List<PendingStar> {
        if (pendings.isEmpty()) return emptyList()
        val sorted = pendings.sortedBy { it.nativeId }
        val m = sorted.size
        return sorted.mapIndexed { i, p ->
            val angle = START_ANGLE_DEG + 360.0 * (i + 0.5) / m
            val radius = if (i % 2 == 0) PENDING_R_EVEN else PENDING_R_ODD
            p.copy(pos = polar(angle, radius.toDouble()))
        }
    }

    /** 连通分量（并查集·端点任一不在节点集的边此前已由 VM 丢弃·此处只连节点集内的边）。 */
    private fun connectedComponents(nodes: List<StarNode>, edges: List<StarEdge>): List<List<StarNode>> {
        val parent = HashMap<String, String>()
        nodes.forEach { parent[it.characterUuid] = it.characterUuid }
        fun find(x: String): String {
            var r = x
            while (parent.getValue(r) != r) r = parent.getValue(r)
            var c = x
            while (parent.getValue(c) != c) { val next = parent.getValue(c); parent[c] = r; c = next }
            return r
        }
        for (e in edges) {
            if (parent.containsKey(e.aUuid) && parent.containsKey(e.bUuid)) {
                parent[find(e.aUuid)] = find(e.bUuid)
            }
        }
        return nodes.groupBy { find(it.characterUuid) }.values.toList()
    }

    private fun polar(angleDeg: Double, r: Double): StarPoint {
        val rad = angleDeg * Math.PI / 180.0
        return StarPoint((cos(rad) * r).toFloat(), (sin(rad) * r).toFloat())
    }

    private fun dist(p: Placed, q: Placed): Double {
        val pp = polar(p.angle, p.radius.toDouble())
        val qq = polar(q.angle, q.radius.toDouble())
        return hypot((pp.x - qq.x).toDouble(), (pp.y - qq.y).toDouble())
    }

    /** 碰撞迭代的可变工作单元（angle 会被微调·radius 固定=亲疏轴）。 */
    private class Placed(val node: StarNode, var angle: Double, val radius: Float)

    private val ORIGIN = StarPoint(0f, 0f)
}
