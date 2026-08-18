package com.situ.aichat.pet

import com.situ.aichat.data.local.entity.CharacterPetEntity

/**
 * 宠物「重点养护动作」——需求标题指向的优先操作（驱动 S2/S3 心情环 urgent 强调点 + 主屏养护栏高亮）。
 * 对应养护栏：[FEED] 喂食 / [CLEAN] 清洁 / [PLAY] 玩耍 / [TREAT] 看病 / [RETRIEVE] 寻回。
 */
enum class PetCareAction { FEED, CLEAN, PLAY, TREAT, RETRIEVE }

/** 宠物当前最该被回应的需求（emoji + 第一人称文案 + 重点动作；开心/满足时 [action] = null）。 */
data class PetNeed(val emoji: String, val headline: String, val action: PetCareAction?)

/**
 * 当前最优先的需求标题（= [com.situ.aichat.ui.pet.PetMoodType] 的「可操作版」·同款优先级再细化）。
 * 优先级（高→低）：离家出走 → 生病 → 饿(hunger≥70) → 脏(cleanliness≤30) → 难过(happiness<30) →
 * 开心(happiness≥80) → 满足。前 5 档各带一个 [PetCareAction] 重点动作；开心 / 满足无紧急动作（action = null）。
 *
 * 阈值口径：hunger≥70 同 [PetReminderPredictor.HUNGRY_THRESHOLD] 与 PetMoodType；happiness<30 / ≥80 同
 * PetMoodType；cleanliness≤30 = 状态卡新增的「该洗澡」档（夹在饿与难过之间）。UNHAPPY/UPSET 等忽略阶段不单列——
 * 它们伴随低 happiness，落到 happiness<30 档（与 PetMoodType 只显式判 SICK/RAN_AWAY 一致）。
 */
fun petNeedHeadline(pet: CharacterPetEntity): PetNeed = when {
    pet.neglectPhase == PetNeglectPhase.RAN_AWAY ->
        PetNeed("🐾", "我跑丢了，快来找我回家吧", PetCareAction.RETRIEVE)
    pet.neglectPhase == PetNeglectPhase.SICK ->
        PetNeed("🤒", "我生病了，带我去看看吧", PetCareAction.TREAT)
    pet.hunger >= 70 ->
        PetNeed("🍚", "我饿了，想吃点好吃的", PetCareAction.FEED)
    pet.cleanliness <= 30 ->
        PetNeed("🫧", "身上有点脏了，想洗个澡", PetCareAction.CLEAN)
    pet.happiness < 30 ->
        PetNeed("🥺", "有点闷闷不乐，陪我玩会儿好吗", PetCareAction.PLAY)
    pet.happiness >= 80 ->
        PetNeed("😸", "和你在一起好开心！", null)
    else ->
        PetNeed("😌", "现在过得很满足～", null)
}

/**
 * 成长进度占比（累计 growthPoints / 当前阶段升级阈值·钳 0~1），1:1 既有 [PetGrowthThresholds]。
 * 特殊形态（SPECIAL·满级·threshold = null）→ 1f。= 既有详情页内联成长条公式（S3 收口将内联替换为此，单源）。
 */
fun growthProgressFraction(pet: CharacterPetEntity): Float {
    val threshold = PetGrowthThresholds.threshold(pet.growthStage) ?: return 1f
    return (pet.growthPoints.toFloat() / threshold).coerceIn(0f, 1f)
}
