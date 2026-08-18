package com.situ.aichat.world.social

import com.situ.aichat.data.model.AppSettings

/**
 * 角色↔角色关系的**类型 / 情感色彩 / 轨迹常量 + 轴钳位 + 里程碑阈值 + 恋爱门**（契约
 * `FABLE5_WORLD_SYSTEM_PROPOSAL.md` §8.A / W4 图纸 §3.1·数值逐字锁死·图纸 §9 禁改）。
 *
 * 本对象只放「关系模型的物理常数」——不含任何随机、IO、时钟；per-事件的轴增减表 / 文案模板在
 * [WorldRelationshipBeats]。恋爱门语义 = **池物理排除**（关 = 心动/暗恋根本不产出，不是概率为 0）。
 */
object WorldRelationshipTypes {

    // MARK: - 类型（可复合·存 typesJson 数组·§3.1）

    /** 初识即得。 */
    const val TYPE_ACQUAINTED = "相识"

    /** closeness 首次 ≥[MILESTONE_FRIEND_CLOSENESS] 里程碑追加。 */
    const val TYPE_FRIEND = "朋友"

    /** closeness 首次 ≥[MILESTONE_CLOSE_CLOSENESS] 里程碑追加。 */
    const val TYPE_CLOSE = "密友"

    /** 恋爱里程碑追加类型（romance 门后·checkMilestones 尾步·W10 契约决策 39）。 */
    const val TYPE_ROMANCE = "恋人"

    // 对手 / 同好 / 旧识 / 心动 一并在类型域内，但 W4 引擎不产出（出厂关系属 W6）——此处不列常量，避免误用。

    // MARK: - 情感色彩池（colorRaw 取值域·§3.1）

    /** 基础色彩池（恒可用·per-事件从各自子池抽·见 [WorldRelationshipBeats.BeatAxes.colors]）。 */
    val BASE_COLORS: List<String> = listOf(
        "好奇", "投缘", "感激", "护着", "惦记", "敬重", "别扭", "较劲", "释然", "更亲近", "淡漠",
    )

    /** 恋爱门后追加色彩（`worldRomanceEnabled=false` 时**绝不产出**·护栏#1·池物理排除）。 */
    val ROMANCE_COLORS: List<String> = listOf("心动", "暗恋")

    /** 恋爱色彩甜点命中时主方向升格为此色（§3.3）。 */
    const val COLOR_HEARTBEAT = "心动"

    // MARK: - 轨迹（§3.1）

    const val TRAJ_WARMING = "warming"
    const val TRAJ_COOLING = "cooling"
    const val TRAJ_STABLE = "stable"

    /**
     * 轨迹判定（§3.1 锁死）：本次事件 closeness 增 且 tension 不增 → warming；tension 增 或 closeness 减 →
     * cooling；其余保持不变。按 edge 各自的**已落轴增减**判（主方向用满额、反方向用折减后的增减）。
     */
    fun trajectoryFor(closenessDelta: Int, tensionDelta: Int, current: String): String = when {
        closenessDelta > 0 && tensionDelta <= 0 -> TRAJ_WARMING
        tensionDelta > 0 || closenessDelta < 0 -> TRAJ_COOLING
        else -> current
    }

    // MARK: - 轴钳位 / 里程碑 / 恋爱门（§3.1）

    /** 一切落轴处必经：钳到 0–100。 */
    fun clampAxis(v: Int): Int = v.coerceIn(0, 100)

    /** 渐远漂移的 closeness 地板（§3.4·drift 只减到此为止）。 */
    const val DRIFT_FLOOR = 10

    /** closeness 首次 ≥ 此值 → types+朋友 + 里程碑事件（各触发一次）。 */
    const val MILESTONE_FRIEND_CLOSENESS = 35

    /** closeness 首次 ≥ 此值 → types+密友 + 里程碑事件（各触发一次）。 */
    const val MILESTONE_CLOSE_CLOSENESS = 70

    /** 恋爱里程碑双向 closeness 门槛（与 [MILESTONE_CLOSE_CLOSENESS] 同值·独立常量防误改联动·W10 决策 39）。 */
    const val ROMANCE_CLOSENESS = 70

    /** 恋爱门：关 = 色彩池/类型池物理排除心动/暗恋（不是概率为 0，是池里没有）。 */
    fun romanceAllowed(settings: AppSettings): Boolean = settings.worldRomanceEnabled
}
