package com.situ.aichat.world.social

/**
 * 关系事件的 **taxonomy + 轴增减表 + 种类权重 + 文案模板池**（契约 §8.B / W4 图纸 §3.3–3.5·逐字锁死·
 * 图纸 §9 禁改）。纯常量表，零随机 / 零 IO——引擎（[WorldRelationshipEngine]）按对·日种子流从这里抽。
 *
 * 槽位约定：`{a}` 主动方名 / `{b}` 对象方名 / `{spot}` 地点 / `{n}` 结痂条数 / `{trend}` 结痂走向。
 */
object WorldRelationshipBeats {

    // MARK: - kindRaw taxonomy（§3.3）

    const val FIRST_MEET = "rel_first_meet"
    const val OUTING = "rel_outing"
    const val HELP = "rel_help"
    const val GOSSIP = "rel_gossip"
    const val QUARREL_START = "rel_quarrel_start"
    const val QUARREL_COLD = "rel_quarrel_cold"
    const val QUARREL_MEND = "rel_quarrel_mend"
    const val MILESTONE = "rel_milestone"
    const val DRIFT = "rel_drift"
    const val COMPACT = "rel_compact"

    // MARK: - 轴增减表（§3.3·「主→客」方向满额·反向按 §3.4 不对称折减）

    /**
     * 一个 beat 的主方向轴增减 + 色彩子池 + 情绪提示。
     * @property colors 该 kind 的色彩子池（两方向各自独立从中一抽·空 = 色彩不改，如里程碑/漂移/结痂）。
     * @property moodHint 情绪轻碰提示（W5 消费·本块不写任何角色列·见 [WorldMoodTouch]）。
     */
    data class BeatAxes(
        val closeness: Int,
        val trust: Int,
        val tension: Int,
        val colors: List<String>,
        val moodHint: Int,
    )

    /** 七种「有轴增减 + 抽色彩」的 beat（§3.3 前七行·first_meet 的值为初始化值）。 */
    val AXES: Map<String, BeatAxes> = mapOf(
        FIRST_MEET to BeatAxes(closeness = 18, trust = 15, tension = 0, colors = listOf("好奇", "投缘"), moodHint = 1),
        OUTING to BeatAxes(closeness = 6, trust = 3, tension = -5, colors = listOf("投缘", "护着"), moodHint = 1),
        HELP to BeatAxes(closeness = 8, trust = 10, tension = -8, colors = listOf("感激", "护着"), moodHint = 1),
        GOSSIP to BeatAxes(closeness = 2, trust = 0, tension = 0, colors = listOf("惦记", "好奇"), moodHint = 0),
        QUARREL_START to BeatAxes(closeness = -4, trust = 0, tension = 18, colors = listOf("别扭", "较劲"), moodHint = -1),
        QUARREL_COLD to BeatAxes(closeness = 0, trust = 0, tension = 6, colors = listOf("别扭"), moodHint = -1),
        QUARREL_MEND to BeatAxes(closeness = 10, trust = 6, tension = -30, colors = listOf("释然", "更亲近"), moodHint = 1),
    )

    /** 里程碑 moodHint +1（色彩沿用当前·无轴增减·§3.3）。 */
    const val MILESTONE_MOOD_HINT = 1

    /** 渐远漂移 closeness −1（色彩不改·地板见 [WorldRelationshipTypes.DRIFT_FLOOR]·§3.3/§3.4）。 */
    const val DRIFT_CLOSENESS_DELTA = -1

    // MARK: - 有边对出事的种类权重（§3.3 锁死·rand.nextDouble() 按此分段）

    /**
     * 分段：outing 0.35 / help 0.25 / gossip 0.20 / quarrel 弧线 0.20（累进 0.35 / 0.60 / 0.80 / 1.0）。
     * quarrel 命中 → 起一条弧线（返回 [QUARREL_START]）。
     */
    fun edgeEventKind(d: Double): String = when {
        d < 0.35 -> OUTING
        d < 0.60 -> HELP
        d < 0.80 -> GOSSIP
        else -> QUARREL_START
    }

    /** 恋爱色彩甜点门槛 closeness ≥ 60（§3.3）。 */
    const val SWEETSPOT_CLOSENESS = 60

    /** 恋爱色彩甜点掷签概率 0.10（§3.3）。 */
    const val SWEETSPOT_PROB = 0.10

    // MARK: - 文案模板池（§3.5·逐字锁死）

    val FIRST_MEET_TEMPLATES: List<String> = listOf(
        "{a}和{b}在{spot}聊了一路，越聊越投机",
        "{a}在{spot}帮{b}捡起了掉落的东西，两人就此认识",
        "{a}和{b}发现彼此都常来{spot}，就这么熟络起来",
    )

    val OUTING_TEMPLATES: List<String> = listOf(
        "{a}约{b}去{spot}走了走，回来时买了同一样小吃",
        "{a}和{b}在{spot}消磨了一下午，各自都很尽兴",
        "{a}拉着{b}去看{spot}的日落，还拍了张合影",
    )

    val HELP_TEMPLATES: List<String> = listOf(
        "{b}最近有点忙，{a}主动搭了把手",
        "{a}冒雨给{b}送了把伞",
        "{b}搬东西闪了腰，{a}忙前忙后照顾了一天",
    )

    val GOSSIP_TEMPLATES: List<String> = listOf(
        "{a}跟人聊天时提起了{b}，语气里带着笑",
        "{a}听说了{b}的近况，心里惦记了一下",
        "{a}路过{spot}的时候，想起了{b}",
    )

    val QUARREL_START_TEMPLATES: List<String> = listOf(
        "{a}和{b}为一件小事拌了嘴，谁也没先低头",
        "{a}和{b}在{spot}起了点争执，不欢而散",
        "{a}的一句玩笑让{b}不太舒服，气氛一下僵住了",
    )

    val QUARREL_COLD_TEMPLATES: List<String> = listOf(
        "{a}和{b}还别扭着，见了面也只是点点头",
        "{a}想给{b}发消息，打了几行又删掉了",
        "{a}和{b}在{spot}碰见，都装作没看见对方",
    )

    val QUARREL_MEND_TEMPLATES: List<String> = listOf(
        "{b}先递了台阶，{a}顺势就下了，两人和好了",
        "{a}带着{b}爱吃的东西上门，别扭烟消云散",
        "{a}和{b}把话说开了，反而比从前更近了些",
    )

    val MILESTONE_FRIEND_TEMPLATES: List<String> = listOf(
        "{a}和{b}处成了朋友",
        "{a}和{b}越来越熟，算得上朋友了",
    )

    val MILESTONE_CLOSE_TEMPLATES: List<String> = listOf(
        "{a}和{b}成了无话不谈的密友",
        "认识这么久，{a}和{b}已是彼此最信任的人",
    )

    /** 恋爱里程碑（romance 门后·checkMilestones 尾步·W10 决策 39·逐字锁死）。 */
    val MILESTONE_ROMANCE_TEMPLATES: List<String> = listOf(
        "{a}和{b}捅破了那层窗户纸，走到了一起",
        "{a}和{b}在一起了",
    )

    /** 渐远漂移（静默不展示·无 {spot} 槽）。 */
    const val DRIFT_TEMPLATE = "{a}和{b}有阵子没联系了"

    /** 结痂压缩句（{n} 被压条数·{trend} 见 §3.6 走向规则）。 */
    const val COMPACT_TEMPLATE = "这段日子{a}和{b}之间大大小小发生了{n}件事，关系{trend}"

    /** 结痂走向词（§3.6·由被压事件种类比定）。 */
    const val TREND_CLOSER = "更近了"
    const val TREND_FADED = "淡了些"
    const val TREND_UPS_AND_DOWNS = "起起伏伏"

    /** 某 kind 的正文模板池（首识 / 结伴 / 互助 / 惦记 / 拌嘴三步·其余无池）。 */
    fun templatesOf(kind: String): List<String> = when (kind) {
        FIRST_MEET -> FIRST_MEET_TEMPLATES
        OUTING -> OUTING_TEMPLATES
        HELP -> HELP_TEMPLATES
        GOSSIP -> GOSSIP_TEMPLATES
        QUARREL_START -> QUARREL_START_TEMPLATES
        QUARREL_COLD -> QUARREL_COLD_TEMPLATES
        QUARREL_MEND -> QUARREL_MEND_TEMPLATES
        else -> emptyList()
    }
}
