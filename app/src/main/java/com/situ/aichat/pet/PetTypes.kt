package com.situ.aichat.pet

/**
 * 宠物核心枚举与平衡常量（1:1 iOS `PetTypes.swift`）。enum 存 rawValue 字符串，`fromRaw` 未知值保守回退
 * （与项目其它枚举一致）。游戏平衡常量（成长阈值/恢复次数/技能门槛）是代码常量，不入 AppSettings。
 */

/** 宠物种类（普通 4 + 隐藏奇幻 3），1:1 iOS `PetSpecies`。 */
enum class PetSpecies(val raw: String, val displayName: String, val isHidden: Boolean) {
    CAT("cat", "猫咪", false),
    DOG("dog", "狗狗", false),
    RABBIT("rabbit", "兔兔", false),
    HAMSTER("hamster", "仓鼠", false),
    DRAGON("dragon", "小龙", true),
    UNICORN("unicorn", "独角兽", true),
    SPIRIT("spirit", "精灵", true);

    companion object {
        private val byRaw = entries.associateBy { it.raw }
        fun fromRaw(raw: String): PetSpecies = byRaw[raw] ?: CAT
        /** 所有普通宠物（领养可选；排除隐藏款）。 */
        val normalSpecies: List<PetSpecies> get() = entries.filter { !it.isHidden }
    }
}

/** 成长阶段（5 阶递进），1:1 iOS `PetGrowthStage`。 */
enum class PetGrowthStage(val raw: String, val displayName: String) {
    BABY("baby", "宝宝"),
    YOUNG("young", "幼年"),
    TEEN("teen", "少年"),
    ADULT("adult", "成年"),
    SPECIAL("special", "特殊形态");

    companion object {
        private val byRaw = entries.associateBy { it.raw }
        fun fromRaw(raw: String): PetGrowthStage = byRaw[raw] ?: BABY
    }
}

/** 性格（领养随机分配），1:1 iOS `PetPersonalityType`。 */
enum class PetPersonalityType(val raw: String, val displayName: String) {
    LIVELY("lively", "活泼"),
    LAZY("lazy", "慵懒"),
    CLINGY("clingy", "粘人"),
    INDEPENDENT("independent", "独立"),
    TIMID("timid", "胆小");

    companion object {
        private val byRaw = entries.associateBy { it.raw }
        fun fromRaw(raw: String): PetPersonalityType = byRaw[raw] ?: LIVELY
    }
}

/** 忽略阶段（严重度按声明顺序递增；只能恶化不能自动回退），1:1 iOS `PetNeglectPhase`。 */
enum class PetNeglectPhase(val raw: String, val displayName: String) {
    NONE("none", "正常"),
    UNHAPPY("unhappy", "不开心"),
    UPSET("upset", "闹情绪"),
    SICK("sick", "生病"),
    RAN_AWAY("ranAway", "离家出走");

    /** 严重度序号（ordinal 即递增顺序），用于「只允许恶化」判定。 */
    val severity: Int get() = ordinal

    companion object {
        private val byRaw = entries.associateBy { it.raw }
        fun fromRaw(raw: String): PetNeglectPhase = byRaw[raw] ?: NONE
    }
}

/** 宠物成长事件类型，1:1 iOS `PetGrowthLogEntry.PetGrowthEventType`。 */
enum class PetGrowthEventType(val raw: String) {
    FED("fed"),
    CLEANED("cleaned"),
    PLAYED("played"),
    CHAT_INTERACTION("chatInteraction"),
    STAGE_UP("stageUp"),
    NEGLECT_ADVANCE("neglectAdvance"),
    NEGLECT_RECOVER("neglectRecover"),
    ADOPTED("adopted"),
    SPECIAL_EVENT("specialEvent"),
    TREATED("treated"),
    SEARCH_ATTEMPT("searchAttempt"),
    FOUND("found"),
    TRICK_LEARNED("trickLearned"),
    WALK_COMPLETED("walkCompleted"),
    EVOLVED("evolved");

    companion object {
        private val byRaw = entries.associateBy { it.raw }
        fun fromRaw(raw: String): PetGrowthEventType = byRaw[raw] ?: SPECIAL_EVENT
    }
}

/** 成长积分阈值（累计 growthPoints 与当前阶段查表比较，非增量），1:1 iOS `PetGrowthThresholds`。 */
object PetGrowthThresholds {
    val thresholds: Map<PetGrowthStage, Int> = mapOf(
        PetGrowthStage.BABY to 100,
        PetGrowthStage.YOUNG to 300,
        PetGrowthStage.TEEN to 600,
        PetGrowthStage.ADULT to 1000,
    )

    /** 当前阶段升级所需累计积分；特殊形态返回 null（满级）。 */
    fun threshold(stage: PetGrowthStage): Int? = thresholds[stage]

    /** 下一阶段；特殊形态返回 null。 */
    fun nextStage(stage: PetGrowthStage): PetGrowthStage? = when (stage) {
        PetGrowthStage.BABY -> PetGrowthStage.YOUNG
        PetGrowthStage.YOUNG -> PetGrowthStage.TEEN
        PetGrowthStage.TEEN -> PetGrowthStage.ADULT
        PetGrowthStage.ADULT -> PetGrowthStage.SPECIAL
        PetGrowthStage.SPECIAL -> null
    }
}

/** 看病/寻回平衡常量，1:1 iOS `PetRecoveryThresholds`。 */
object PetRecoveryThresholds {
    const val TREATMENTS_TO_HEAL = 3
    const val ATTEMPTS_TO_FIND = 5
    const val TRUST_RECOVERY_PER_CARE = 0.2
}

/** 技能学习的玩耍次数里程碑（playCount 门槛 → 技能），1:1 iOS `PetTrickMilestones`。 */
object PetTrickMilestones {
    data class Trick(val plays: Int, val trickId: String, val name: String)

    val milestones: List<Trick> = listOf(
        Trick(10, "sit", "坐下"),
        Trick(30, "shake", "握手"),
        Trick(60, "roll", "打滚"),
        Trick(100, "dance", "跳舞"),
    )
}
