package com.situ.aichat.pet

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 宠物里程碑成就系统（100 个成就）。1:1 移植 iOS `Models/PetMilestones.swift`：纯数据定义 + 纯判定函数。
 * 与技能里程碑 [PetTrickMilestones]（4 个技能·玩耍解锁）相互独立——本系统是详情页「成就」图鉴（X/100）。
 * **纯展示不发金币**（对齐 iOS：成就属 M11 图鉴 UI，经济金币只发进化/关系，见 ROADMAP 165）。
 */
object PetMilestones {

    /** 检查条件（1:1 iOS `MilestoneKind`）。 */
    sealed interface MilestoneKind {
        data class Days(val n: Int) : MilestoneKind
        data class Interactions(val n: Int) : MilestoneKind
        data class Tricks(val n: Int) : MilestoneKind
        data class Souvenirs(val n: Int) : MilestoneKind
        data object Evolved : MilestoneKind
        data class PlayCount(val n: Int) : MilestoneKind
        data class GrowthPoints(val n: Int) : MilestoneKind
    }

    /** 单个成就（id 稳定·name/emoji 展示·kind 判定条件）。 */
    data class Milestone(
        val id: String,
        val name: String,
        val emoji: String,
        val kind: MilestoneKind,
    )

    // MARK: - 全部 100 个成就（逐字对齐 iOS PetMilestones.all）

    val all: List<Milestone> = listOf(
        // ===== 陪伴时光（14 个）=====
        Milestone("days1", "初次见面", "🌱", MilestoneKind.Days(1)),
        Milestone("days3", "三日之约", "🌿", MilestoneKind.Days(3)),
        Milestone("days7", "一周陪伴", "🍀", MilestoneKind.Days(7)),
        Milestone("days14", "两周好友", "🌳", MilestoneKind.Days(14)),
        Milestone("days30", "满月纪念", "🌙", MilestoneKind.Days(30)),
        Milestone("days60", "双月之约", "🌗", MilestoneKind.Days(60)),
        Milestone("days90", "季度守护", "🍂", MilestoneKind.Days(90)),
        Milestone("days100", "百日相守", "💯", MilestoneKind.Days(100)),
        Milestone("days150", "半载情深", "🌻", MilestoneKind.Days(150)),
        Milestone("days200", "二百日记", "📖", MilestoneKind.Days(200)),
        Milestone("days365", "周年庆", "🎂", MilestoneKind.Days(365)),
        Milestone("days500", "五百日情", "🏆", MilestoneKind.Days(500)),
        Milestone("days730", "两年之约", "💎", MilestoneKind.Days(730)),
        Milestone("days1000", "千日传说", "👑", MilestoneKind.Days(1000)),

        // ===== 互动达人（14 个）=====
        Milestone("interact5", "初次互动", "👋", MilestoneKind.Interactions(5)),
        Milestone("interact10", "渐渐熟悉", "🤗", MilestoneKind.Interactions(10)),
        Milestone("interact25", "日常伙伴", "🫂", MilestoneKind.Interactions(25)),
        Milestone("interact50", "亲密伙伴", "🤝", MilestoneKind.Interactions(50)),
        Milestone("interact100", "百次陪伴", "💕", MilestoneKind.Interactions(100)),
        Milestone("interact200", "形影不离", "💗", MilestoneKind.Interactions(200)),
        Milestone("interact300", "默契搭档", "🎯", MilestoneKind.Interactions(300)),
        Milestone("interact500", "灵魂伴侣", "✨", MilestoneKind.Interactions(500)),
        Milestone("interact750", "命中注定", "🌟", MilestoneKind.Interactions(750)),
        Milestone("interact1000", "千次之约", "🎊", MilestoneKind.Interactions(1000)),
        Milestone("interact1500", "无尽陪伴", "♾️", MilestoneKind.Interactions(1500)),
        Milestone("interact2000", "两千印记", "🏅", MilestoneKind.Interactions(2000)),
        Milestone("interact3000", "不离不弃", "💖", MilestoneKind.Interactions(3000)),
        Milestone("interact5000", "永恒之约", "🌈", MilestoneKind.Interactions(5000)),

        // ===== 玩耍大师（12 个）=====
        Milestone("play5", "初次玩耍", "🎈", MilestoneKind.PlayCount(5)),
        Milestone("play10", "玩伴", "🎪", MilestoneKind.PlayCount(10)),
        Milestone("play20", "游戏达人", "🎮", MilestoneKind.PlayCount(20)),
        Milestone("play50", "快乐源泉", "🎡", MilestoneKind.PlayCount(50)),
        Milestone("play100", "百玩不厌", "🎠", MilestoneKind.PlayCount(100)),
        Milestone("play200", "游戏王", "🃏", MilestoneKind.PlayCount(200)),
        Milestone("play300", "玩耍之神", "🏆", MilestoneKind.PlayCount(300)),
        Milestone("play500", "不知疲倦", "⚡", MilestoneKind.PlayCount(500)),
        Milestone("play750", "永动机", "🔄", MilestoneKind.PlayCount(750)),
        Milestone("play1000", "千次欢笑", "😂", MilestoneKind.PlayCount(1000)),
        Milestone("play1500", "玩耍传奇", "🎭", MilestoneKind.PlayCount(1500)),
        Milestone("play2000", "极限玩家", "🌋", MilestoneKind.PlayCount(2000)),

        // ===== 收藏家（20 个）=====
        Milestone("souv1", "初次收获", "🎁", MilestoneKind.Souvenirs(1)),
        Milestone("souv3", "小小收藏", "📦", MilestoneKind.Souvenirs(3)),
        Milestone("souv5", "收集爱好", "🧺", MilestoneKind.Souvenirs(5)),
        Milestone("souv8", "宝贝盒子", "🗃️", MilestoneKind.Souvenirs(8)),
        Milestone("souv10", "收藏家", "🏆", MilestoneKind.Souvenirs(10)),
        Milestone("souv15", "寻宝猎人", "🗺️", MilestoneKind.Souvenirs(15)),
        Milestone("souv20", "宝藏守护", "💰", MilestoneKind.Souvenirs(20)),
        Milestone("souv25", "探险家", "🧭", MilestoneKind.Souvenirs(25)),
        Milestone("souv30", "博物馆长", "🏛️", MilestoneKind.Souvenirs(30)),
        Milestone("souv35", "珍品鉴赏", "🔍", MilestoneKind.Souvenirs(35)),
        Milestone("souv40", "收藏大师", "🎖️", MilestoneKind.Souvenirs(40)),
        Milestone("souv50", "半数达成", "⭐", MilestoneKind.Souvenirs(50)),
        Milestone("souv60", "资深藏家", "🌟", MilestoneKind.Souvenirs(60)),
        Milestone("souv70", "接近完美", "💫", MilestoneKind.Souvenirs(70)),
        Milestone("souv75", "四分之三", "🎯", MilestoneKind.Souvenirs(75)),
        Milestone("souv80", "几乎齐全", "🔥", MilestoneKind.Souvenirs(80)),
        Milestone("souv85", "执着收集", "💪", MilestoneKind.Souvenirs(85)),
        Milestone("souv90", "终极猎人", "🦅", MilestoneKind.Souvenirs(90)),
        Milestone("souv95", "就差一点", "😤", MilestoneKind.Souvenirs(95)),
        Milestone("souv100", "完美图鉴", "👑", MilestoneKind.Souvenirs(100)),

        // ===== 成长之路（14 个）=====
        Milestone("growth10", "萌芽", "🌱", MilestoneKind.GrowthPoints(10)),
        Milestone("growth25", "初露锋芒", "🌿", MilestoneKind.GrowthPoints(25)),
        Milestone("growth50", "茁壮成长", "🌳", MilestoneKind.GrowthPoints(50)),
        Milestone("growth100", "小有成就", "🎯", MilestoneKind.GrowthPoints(100)),
        Milestone("growth200", "稳步前进", "📈", MilestoneKind.GrowthPoints(200)),
        Milestone("growth300", "不断进步", "🚀", MilestoneKind.GrowthPoints(300)),
        Milestone("growth500", "实力派", "💪", MilestoneKind.GrowthPoints(500)),
        Milestone("growth750", "精英之路", "⚡", MilestoneKind.GrowthPoints(750)),
        Milestone("growth1000", "千分大师", "🏆", MilestoneKind.GrowthPoints(1000)),
        Milestone("growth1500", "超越极限", "🌟", MilestoneKind.GrowthPoints(1500)),
        Milestone("growth2000", "传说之路", "🔱", MilestoneKind.GrowthPoints(2000)),
        Milestone("growth3000", "三千世界", "🌍", MilestoneKind.GrowthPoints(3000)),
        Milestone("growth5000", "无上境界", "☀️", MilestoneKind.GrowthPoints(5000)),
        Milestone("growth10000", "万点传奇", "👑", MilestoneKind.GrowthPoints(10000)),

        // ===== 技能大师（4 个）=====
        Milestone("trick1", "初学乍练", "📚", MilestoneKind.Tricks(1)),
        Milestone("trick2", "学有所成", "🎓", MilestoneKind.Tricks(2)),
        Milestone("trick3", "多才多艺", "🎪", MilestoneKind.Tricks(3)),
        Milestone("trick4", "才艺满分", "🏅", MilestoneKind.Tricks(4)),

        // ===== 特殊成就（22 个，凑满 100）=====
        Milestone("evolved", "奇幻蜕变", "🦋", MilestoneKind.Evolved),

        // 综合里程碑（组合条件用单一最高要求代替，保持纯数据设计）
        Milestone("days2_int10", "新手上路", "🛤️", MilestoneKind.Interactions(10)),
        Milestone("growth50_d7", "一周精进", "📊", MilestoneKind.GrowthPoints(50)),
        Milestone("play30_d14", "勤奋玩家", "🎲", MilestoneKind.PlayCount(30)),
        Milestone("int400", "坚持不懈", "🔨", MilestoneKind.Interactions(400)),
        Milestone("int600", "持之以恒", "⛏️", MilestoneKind.Interactions(600)),
        Milestone("int800", "恒心如铁", "🛡️", MilestoneKind.Interactions(800)),
        Milestone("play400", "乐此不疲", "🎯", MilestoneKind.PlayCount(400)),
        Milestone("play600", "极致玩家", "🎪", MilestoneKind.PlayCount(600)),
        Milestone("play800", "至尊玩家", "👾", MilestoneKind.PlayCount(800)),
        Milestone("growth150", "蓄势待发", "🌊", MilestoneKind.GrowthPoints(150)),
        Milestone("growth400", "突飞猛进", "🚁", MilestoneKind.GrowthPoints(400)),
        Milestone("growth600", "势不可挡", "🌪️", MilestoneKind.GrowthPoints(600)),
        Milestone("growth800", "登峰造极", "⛰️", MilestoneKind.GrowthPoints(800)),
        Milestone("growth1200", "炉火纯青", "🔥", MilestoneKind.GrowthPoints(1200)),
        Milestone("growth1800", "出神入化", "🌀", MilestoneKind.GrowthPoints(1800)),
        Milestone("growth2500", "神乎其技", "🎇", MilestoneKind.GrowthPoints(2500)),
        Milestone("growth4000", "登堂入室", "🏰", MilestoneKind.GrowthPoints(4000)),
        Milestone("growth7500", "传世之作", "📜", MilestoneKind.GrowthPoints(7500)),
        Milestone("days250", "长情守候", "🕰️", MilestoneKind.Days(250)),
        Milestone("days400", "四百日光", "🌅", MilestoneKind.Days(400)),
        Milestone("days600", "六百日月", "🌄", MilestoneKind.Days(600)),
    )

    // MARK: - 成就判定（1:1 iOS achievedIDs）

    /** 批量判断已达成的里程碑 ID（接收预计算的快照值，避免重复解码）。纯函数。 */
    fun achievedIDs(
        daysSinceAdoption: Int,
        totalInteractions: Int,
        tricksCount: Int,
        souvenirCount: Int,
        isSpecial: Boolean,
        playCount: Int,
        growthPoints: Int,
    ): Set<String> {
        val result = HashSet<String>()
        for (m in all) {
            val done = when (val k = m.kind) {
                is MilestoneKind.Days -> daysSinceAdoption >= k.n
                is MilestoneKind.Interactions -> totalInteractions >= k.n
                is MilestoneKind.Tricks -> tricksCount >= k.n
                is MilestoneKind.Souvenirs -> souvenirCount >= k.n
                MilestoneKind.Evolved -> isSpecial
                is MilestoneKind.PlayCount -> playCount >= k.n
                is MilestoneKind.GrowthPoints -> growthPoints >= k.n
            }
            if (done) result.add(m.id)
        }
        return result
    }

    /**
     * P1-34 新解锁判定（纯函数·安卓超越：iOS 成就解锁零反馈）：[oldBaseline]=null 表示从未计算
     * （老数据回填/新宠物首算）→ 返回 null（静默 seed，绝不把历史成就当新解锁补播）；否则返回
     * newSet−oldBaseline 增量、按 [all] 定义顺序；无增量 → null。集合收缩（导入旧备份）只随基线
     * 覆写、不报解锁。
     */
    fun newlyUnlocked(oldBaseline: Set<String>?, newSet: Set<String>): List<Milestone>? {
        if (oldBaseline == null) return null
        val added = newSet - oldBaseline
        if (added.isEmpty()) return null
        return all.filter { it.id in added }
    }

    /**
     * 自领养至今的完整天数（1:1 iOS `Calendar.dateComponents([.day], from: adoptedDate, to: now)`，**无 +1**——
     * 区别于详情页「在一起 N 天」展示的 +1）。用设备时区按日历整天计（DST 安全；国行无 DST 时等价 millis/86_400_000）。
     */
    fun daysSinceAdoption(
        adoptedMillis: Long,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int {
        if (nowMillis <= adoptedMillis) return 0
        return ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(adoptedMillis).atZone(zone),
            Instant.ofEpochMilli(nowMillis).atZone(zone),
        ).toInt().coerceAtLeast(0)
    }
}
