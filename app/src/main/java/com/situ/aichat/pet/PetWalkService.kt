package com.situ.aichat.pet

import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.util.DateFormatters
import java.time.ZoneId
import java.util.UUID
import kotlin.math.min
import kotlin.random.Random

/**
 * 宠物散步服务（1:1 iOS `PetWalkService`）：开始散步、完成检查、随机事件结算、纪念品收集。
 * 散步时长 30 分钟（真实时间），**不限每日次数**（但当天 >3 次触发过度散步衰减惩罚，见
 * [PetCareService.applyDecay]）。
 *
 * Android 适配：同 [PetCareService]——iOS `CharacterPet` 是可变 @Model 原地改 + SwiftData 自动存；
 * Android 的 [CharacterPetEntity] 是不可变 Room 行，故 [startWalk]/[checkAndSettle] 是**纯函数**
 * （entity 进 → 新 entity 出，调用方负责 upsert）。`now` 注入替 iOS `Date()`、`random` 注入替
 * iOS `randomElement()`/`Int.random` 便于确定性单测。散步拾到的金币（3~15）由调用方在 P9 货币系统入账。
 */
object PetWalkService {

    /** 散步时长（毫秒）= iOS `walkDuration = 30 * 60` 秒。 */
    const val WALK_DURATION_MS: Long = 30L * 60L * 1000L

    // MARK: - 散步状态

    /** 宠物当前散步状态（1:1 iOS `WalkState`）。 */
    sealed interface WalkState {
        /** 没在散步。 */
        data object Idle : WalkState
        /** 散步中（剩余毫秒）。 */
        data class Walking(val startTime: Long, val remainingMs: Long) : WalkState
        /** 散步完成，待结算。 */
        data class Completed(val startTime: Long) : WalkState
    }

    /** 查询宠物散步状态（1:1 iOS `walkState`）。 */
    fun walkState(pet: CharacterPetEntity, now: Long = System.currentTimeMillis()): WalkState {
        val startTime = pet.metadata.walkStartTime ?: return WalkState.Idle
        val elapsed = now - startTime
        return if (elapsed >= WALK_DURATION_MS) {
            WalkState.Completed(startTime)
        } else {
            WalkState.Walking(startTime, WALK_DURATION_MS - elapsed)
        }
    }

    /** 是否可以开始散步（忽略阶段 ∈ {none, unhappy} 且未在散步中；不限每日次数）。1:1 iOS `canStartWalk`。 */
    fun canStartWalk(pet: CharacterPetEntity): Boolean {
        val phase = pet.neglectPhase
        if (phase != PetNeglectPhase.NONE && phase != PetNeglectPhase.UNHAPPY) return false
        if (pet.metadata.walkStartTime != null) return false
        return true
    }

    // MARK: - 开始散步

    /**
     * 开始散步（设 walkStartTime/lastWalkDate，累加当天散步次数，跨天自动重置）。1:1 iOS `startWalk`。
     * @return 新 entity；不可开始（健康不足/已在散步）返回 null（对应 iOS 返回 false）。
     */
    fun startWalk(
        pet: CharacterPetEntity,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): CharacterPetEntity? {
        if (!canStartWalk(pet)) return null
        var metadata = pet.metadata.copy(walkStartTime = now, lastWalkDate = now)
        // 当天散步计数（跨天自动重置；isDateInToday 用 startOfDay 比对，同 PetCareService）
        val isToday = metadata.lastWalkCountDate?.let { countDate ->
            DateFormatters.startOfDayMillis(countDate, zone) == DateFormatters.startOfDayMillis(now, zone)
        } ?: false
        metadata = if (isToday) {
            metadata.copy(dailyWalkCount = metadata.dailyWalkCount + 1)
        } else {
            metadata.copy(dailyWalkCount = 1, lastWalkCountDate = now)
        }
        return pet.copy(
            petMetadataJson = PetJson.encodeMetadata(metadata),
            lastInteractionDate = now,
        )
    }

    // MARK: - 散步结算

    /** 散步结算结果（1:1 iOS `WalkSettlement` + 新 entity）。 */
    data class WalkSettlement(
        /** 结算后的新 entity（调用方负责 upsert）。 */
        val pet: CharacterPetEntity,
        val eventDescription: String,
        val souvenir: PetSouvenir?,
        val moodBonus: Int,
        val growthBonus: Int,
        /** 拾到的金币（3~15 随机），调用方负责实际入账（P9 货币系统）。 */
        val coinsReward: Int,
    )

    /**
     * 检查散步是否完成并结算（仅 completed 时）。1:1 iOS `checkAndSettle`。
     * @return 结算结果；散步未完成返回 null。
     */
    fun checkAndSettle(
        pet: CharacterPetEntity,
        settings: AppSettings?,
        now: Long = System.currentTimeMillis(),
        random: Random = Random.Default,
    ): WalkSettlement? {
        if (walkState(pet, now) !is WalkState.Completed) return null

        // 选择随机事件（按物种过滤）
        val candidates = candidatesFor(pet.species)
        val event = candidates.randomOrNull(random) ?: walkEvents[0]

        // 应用奖励
        val growthGain = event.growthBonus + (settings?.petGrowthPointsPerPlay ?: 8)
        val newHappiness = min(100, pet.happiness + event.moodBonus)

        // 收集纪念品（已收集的跳过，避免数据无限增长）
        val newSouvenir: PetSouvenir? = event.souvenir?.let { s ->
            val alreadyCollected = pet.metadata.souvenirs.any { it.name == s.name }
            if (alreadyCollected) {
                null
            } else {
                PetSouvenir(
                    id = UUID.randomUUID().toString(),
                    name = s.name,
                    emoji = s.emoji,
                    obtainedDate = now,
                    eventDescription = event.description,
                )
            }
        }
        var metadata = pet.metadata
        if (newSouvenir != null) metadata = metadata.copy(souvenirs = metadata.souvenirs + newSouvenir)
        // 清除散步状态
        metadata = metadata.copy(walkStartTime = null)

        // 记录成长日志
        val summary = if (newSouvenir != null) {
            "${pet.name}散步回来了！带回了${newSouvenir.emoji}${newSouvenir.name}"
        } else {
            "${pet.name}散步回来了！${event.description}"
        }
        val entry = PetGrowthLogEntry(
            id = UUID.randomUUID().toString(),
            timestamp = now,
            type = PetGrowthEventType.WALK_COMPLETED.raw,
            summary = summary,
        )
        val log = (pet.growthLog + entry).let { if (it.size > 50) it.takeLast(50) else it }

        val updated = pet.copy(
            happiness = newHappiness,
            growthPoints = pet.growthPoints + growthGain,
            totalInteractions = pet.totalInteractions + 1,
            lastInteractionDate = now,
            petMetadataJson = PetJson.encodeMetadata(metadata),
            petGrowthLogJson = PetJson.encodeGrowthLog(log),
        )
        return WalkSettlement(
            pet = updated,
            eventDescription = event.description,
            souvenir = newSouvenir,
            moodBonus = event.moodBonus,
            growthBonus = growthGain,
            coinsReward = (3..15).random(random),
        )
    }

    // MARK: - 散步事件池

    /** 散步事件（description + 可选纪念品 + 奖励 + 物种过滤）。`internal` 便于单测核对过滤/数值。 */
    internal data class WalkEvent(
        val description: String,
        val souvenir: SouvenirSpec?,
        val moodBonus: Int,
        val growthBonus: Int,
        val speciesFilter: Set<PetSpecies>?, // null = 所有种类
    )

    /** 纪念品规格（name + emoji），同时用于事件池与图鉴 [allSouvenirTypes]。 */
    data class SouvenirSpec(val name: String, val emoji: String)

    /** 按物种过滤候选事件（speciesFilter==null 通用 / 否则需含该物种）。1:1 iOS checkAndSettle 的 filter。 */
    internal fun candidatesFor(species: PetSpecies): List<WalkEvent> =
        walkEvents.filter { event ->
            val filter = event.speciesFilter ?: return@filter true
            species in filter
        }

    /** 所有可能的纪念品类型（从 [walkEvents] 去重提取，用于图鉴展示）。1:1 iOS `allSouvenirTypes`。 */
    val allSouvenirTypes: List<SouvenirSpec> by lazy {
        val seen = HashSet<String>()
        walkEvents.mapNotNull { e ->
            val s = e.souvenir ?: return@mapNotNull null
            if (!seen.add(s.name)) null else s
        }
    }

    internal val walkEvents: List<WalkEvent> = listOf(
        // ========== 通用事件（无纪念品，纯体验） ==========
        WalkEvent("在公园长椅上晒了会儿太阳", null, 10, 3, null),
        WalkEvent("在草地上打了个滚", null, 15, 2, null),
        WalkEvent("和路过的小朋友玩了一会儿", null, 18, 4, null),
        WalkEvent("看到了美丽的夕阳", null, 20, 3, null),
        WalkEvent("在河边看了一会儿鱼", null, 12, 3, null),
        WalkEvent("在树荫下打了个盹", null, 8, 2, null),
        WalkEvent("被路过的老奶奶摸了摸头", null, 15, 3, null),
        WalkEvent("在喷泉旁听了一会儿水声", null, 10, 2, null),
        WalkEvent("看到了一只流浪猫", null, 8, 3, null),
        WalkEvent("在花园里闻了闻花香", null, 12, 2, null),
        WalkEvent("追着自己的影子跑了一圈", null, 15, 2, null),
        WalkEvent("在沙地上画了个爪印", null, 10, 2, null),
        WalkEvent("遇到了一只友善的鸽子", null, 8, 3, null),
        WalkEvent("绕着大树转了好几圈", null, 12, 2, null),
        WalkEvent("在桥上看了一会儿风景", null, 14, 3, null),
        WalkEvent("踩了好多落叶，咔嚓咔嚓", null, 10, 2, null),
        WalkEvent("和另一只宠物对视了一会儿", null, 8, 3, null),
        WalkEvent("在凉亭里休息了一下", null, 10, 2, null),
        WalkEvent("听到了远处的音乐", null, 12, 2, null),
        WalkEvent("在小路上悠闲地散步", null, 8, 2, null),
        WalkEvent("看到了一道美丽的彩虹", null, 20, 4, null),
        WalkEvent("在草丛里发呆了一会儿", null, 6, 2, null),
        WalkEvent("被小孩子追着跑了一阵", null, 16, 3, null),
        WalkEvent("在池塘边看青蛙跳水", null, 12, 3, null),
        WalkEvent("站在山坡上吹了吹风", null, 14, 2, null),
        WalkEvent("和公园里的狗狗交了朋友", null, 20, 4, null),
        WalkEvent("在小溪边洗了洗爪子", null, 10, 2, null),
        WalkEvent("看到了满天的星星", null, 18, 3, null),
        WalkEvent("在竹林里听风声", null, 12, 2, null),
        WalkEvent("遇到了卖气球的叔叔", null, 10, 2, null),
        WalkEvent("在秋千上荡了一会儿", null, 15, 3, null),
        WalkEvent("躲在灌木丛后面玩捉迷藏", null, 14, 3, null),
        WalkEvent("在花田里跑来跑去", null, 16, 3, null),
        WalkEvent("跟着蝴蝶走了一段路", null, 12, 2, null),
        WalkEvent("在阳光下伸了个大懒腰", null, 8, 2, null),
        WalkEvent("看到了一群大雁飞过天空", null, 14, 3, null),
        WalkEvent("在木栈道上散步，听木板声", null, 10, 2, null),
        WalkEvent("在公园的草坪上翻了个肚皮", null, 16, 2, null),
        WalkEvent("看到远处的烟花", null, 20, 4, null),
        WalkEvent("在雨后闻到了泥土的清香", null, 12, 2, null),
        // ========== 通用纪念品：自然类 ==========
        WalkEvent("发现了一块漂亮的小石头", SouvenirSpec("小石头", "🪨"), 8, 5, null),
        WalkEvent("捡到了一片四叶草", SouvenirSpec("四叶草", "🍀"), 12, 8, null),
        WalkEvent("在花丛中找到了一朵干花", SouvenirSpec("干花", "🌸"), 10, 5, null),
        WalkEvent("在河边捡到了一个贝壳", SouvenirSpec("小贝壳", "🐚"), 10, 5, null),
        WalkEvent("发现了一片完美的红枫叶", SouvenirSpec("红枫叶", "🍁"), 12, 5, null),
        WalkEvent("捡到了一根弯弯的树枝", SouvenirSpec("奇怪树枝", "🪵"), 6, 4, null),
        WalkEvent("发现了一朵小蘑菇", SouvenirSpec("小蘑菇", "🍄"), 10, 5, null),
        WalkEvent("捡到了一颗松果", SouvenirSpec("松果", "🌰"), 8, 4, null),
        WalkEvent("找到了一片心形的叶子", SouvenirSpec("心形叶", "🌿"), 14, 6, null),
        WalkEvent("在溪边发现了一块苔藓石", SouvenirSpec("苔藓石", "🧱"), 8, 4, null),
        WalkEvent("捡到了一朵蒲公英", SouvenirSpec("蒲公英", "🌼"), 10, 5, null),
        WalkEvent("发现了一颗完整的橡果", SouvenirSpec("橡果", "🫒"), 8, 4, null),
        WalkEvent("找到了一根竹笋", SouvenirSpec("小竹笋", "🎋"), 10, 5, null),
        WalkEvent("在草丛中发现了一朵小雏菊", SouvenirSpec("小雏菊", "🌻"), 12, 5, null),
        WalkEvent("捡到了一片银杏叶", SouvenirSpec("银杏叶", "🍂"), 10, 5, null),
        // ========== 通用纪念品：天气/天文类 ==========
        WalkEvent("在雨后的叶子上看到了水珠", SouvenirSpec("露珠", "💧"), 10, 5, null),
        WalkEvent("在雪地里发现了冰晶", SouvenirSpec("雪花结晶", "❄️"), 14, 6, null),
        WalkEvent("捡到了一片被阳光穿透的树叶", SouvenirSpec("阳光叶片", "☀️"), 12, 5, null),
        WalkEvent("在夜空中看到了流星", SouvenirSpec("流星碎片", "🌠"), 20, 8, null),
        WalkEvent("发现了一块月光石", SouvenirSpec("月光石", "🌙"), 14, 6, null),
        WalkEvent("在晨雾中找到了一颗露珠宝石", SouvenirSpec("晨露宝珠", "💎"), 16, 7, null),
        WalkEvent("捡到了被闪电击中的小石子", SouvenirSpec("雷击石", "⚡"), 12, 6, null),
        WalkEvent("在彩虹出现时捡到了彩色玻璃", SouvenirSpec("彩虹玻璃", "🌈"), 18, 7, null),
        // ========== 通用纪念品：人文/小物件类 ==========
        WalkEvent("发现了一颗闪闪发光的弹珠", SouvenirSpec("彩色弹珠", "🔮"), 8, 6, null),
        WalkEvent("捡到了一个复古瓶盖", SouvenirSpec("复古瓶盖", "🧲"), 6, 4, null),
        WalkEvent("在地上找到了一枚硬币", SouvenirSpec("幸运硬币", "🪙"), 12, 5, null),
        WalkEvent("捡到了一个彩色纽扣", SouvenirSpec("彩色纽扣", "🔵"), 6, 4, null),
        WalkEvent("发现了一张旧邮票", SouvenirSpec("旧邮票", "📮"), 10, 5, null),
        WalkEvent("捡到了一个小铃铛", SouvenirSpec("小铃铛", "🔔"), 10, 5, null),
        WalkEvent("找到了一颗玻璃弹珠", SouvenirSpec("透明弹珠", "🫧"), 8, 4, null),
        WalkEvent("在长椅下发现了一把小钥匙", SouvenirSpec("神秘钥匙", "🗝️"), 14, 6, null),
        WalkEvent("捡到了一个小徽章", SouvenirSpec("小徽章", "🏅"), 10, 5, null),
        WalkEvent("发现了一个迷你地球仪", SouvenirSpec("迷你地球仪", "🌍"), 12, 6, null),
        WalkEvent("找到了一枚星形别针", SouvenirSpec("星星别针", "⭐"), 10, 5, null),
        WalkEvent("捡到了一个小音乐盒零件", SouvenirSpec("音乐盒齿轮", "⚙️"), 12, 6, null),
        WalkEvent("发现了一块彩色马赛克碎片", SouvenirSpec("马赛克碎片", "🎨"), 8, 5, null),
        WalkEvent("捡到了一颗彩色珠子", SouvenirSpec("彩色珠子", "📿"), 8, 4, null),
        WalkEvent("在旧书摊边捡到了一张书签", SouvenirSpec("旧书签", "🔖"), 8, 4, null),
        // ========== 通用纪念品：动物/生物类 ==========
        WalkEvent("找到了一个空蜗牛壳", SouvenirSpec("蜗牛壳", "🐌"), 8, 5, null),
        WalkEvent("发现了一只小瓢虫停在叶子上", SouvenirSpec("瓢虫翅膀", "🐞"), 12, 5, null),
        WalkEvent("在草丛里发现了一个蝉蜕", SouvenirSpec("蝉蜕", "🦗"), 8, 5, null),
        WalkEvent("捡到了一片蜂巢碎片", SouvenirSpec("蜂巢碎片", "🍯"), 10, 5, null),
        WalkEvent("找到了一根鸟巢上掉下来的小枝", SouvenirSpec("鸟巢小枝", "🪺"), 10, 5, null),
        WalkEvent("在地上发现了一颗鸟蛋壳碎片", SouvenirSpec("蛋壳碎片", "🥚"), 8, 4, null),
        WalkEvent("捡到了一片蝉翼", SouvenirSpec("透明蝉翼", "🪽"), 12, 6, null),
        // ========== 通用纪念品：食物类 ==========
        WalkEvent("在树下捡到了一颗核桃", SouvenirSpec("小核桃", "🥜"), 8, 4, null),
        WalkEvent("发现了一串野生蓝莓", SouvenirSpec("野蓝莓", "🫐"), 12, 5, null),
        WalkEvent("在灌木丛中找到了一颗野草莓", SouvenirSpec("野草莓", "🍓"), 14, 5, null),
        WalkEvent("捡到了一颗栗子", SouvenirSpec("毛栗子", "🌰"), 8, 4, null),
        WalkEvent("发现了一颗小樱桃", SouvenirSpec("小樱桃", "🍒"), 10, 5, null),
        WalkEvent("在田边找到了一穗小麦", SouvenirSpec("金色麦穗", "🌾"), 8, 4, null),
        // ========== 通用纪念品：稀有/珍贵类 ==========
        WalkEvent("挖到了一块小化石", SouvenirSpec("远古化石", "🦕"), 18, 8, null),
        WalkEvent("在溪流中发现了一颗小珍珠", SouvenirSpec("小珍珠", "🦪"), 16, 7, null),
        WalkEvent("在岩缝中找到了一块紫水晶", SouvenirSpec("紫水晶", "🟣"), 18, 8, null),
        WalkEvent("发现了一块琥珀", SouvenirSpec("琥珀", "🟠"), 16, 7, null),
        WalkEvent("在山洞入口找到了一块钟乳石", SouvenirSpec("钟乳石碎片", "🏔️"), 14, 7, null),
        WalkEvent("在沙滩上发现了一块海玻璃", SouvenirSpec("海玻璃", "🩵"), 12, 6, null),
        WalkEvent("捡到了一块磁铁矿石", SouvenirSpec("磁铁石", "🧲"), 14, 6, null),
        WalkEvent("在河床里找到了一块玉石", SouvenirSpec("小玉石", "🟢"), 16, 7, null),
        WalkEvent("发现了一颗陨石碎片", SouvenirSpec("陨石碎片", "☄️"), 20, 10, null),
        WalkEvent("挖到了一个古代陶片", SouvenirSpec("古代陶片", "🏺"), 14, 7, null),
        // ========== 通用纪念品：季节/节日类 ==========
        WalkEvent("在春天捡到了一瓣桃花", SouvenirSpec("桃花瓣", "🌷"), 12, 5, null),
        WalkEvent("在夏天找到了一只蝉的外壳", SouvenirSpec("金蝉壳", "🦟"), 10, 5, null),
        WalkEvent("在秋天收集了一把橡子帽", SouvenirSpec("橡子帽", "🎩"), 10, 5, null),
        WalkEvent("在冬天找到了一根冰柱", SouvenirSpec("小冰柱", "🧊"), 12, 5, null),
        WalkEvent("捡到了一个小南瓜", SouvenirSpec("迷你南瓜", "🎃"), 12, 5, null),
        WalkEvent("发现了一朵薰衣草", SouvenirSpec("薰衣草", "💜"), 12, 5, null),
        WalkEvent("在梅树下捡到了梅花瓣", SouvenirSpec("梅花瓣", "🩷"), 12, 5, null),
        WalkEvent("找到了一朵向日葵种子盘", SouvenirSpec("向日葵盘", "🌻"), 10, 5, null),
        // ========== 通用纪念品：趣味/奇特类 ==========
        WalkEvent("在树洞里找到了一张小纸条", SouvenirSpec("神秘纸条", "📜"), 14, 6, null),
        WalkEvent("发现了一个被冻住的泡泡", SouvenirSpec("冰冻泡泡", "🫧"), 16, 7, null),
        WalkEvent("捡到了一根有趣的恐龙形状树根", SouvenirSpec("恐龙树根", "🦖"), 14, 6, null),
        WalkEvent("在石头上发现了一个小手印化石", SouvenirSpec("手印化石", "🖐️"), 16, 7, null),
        WalkEvent("找到了一个精致的鸟窝", SouvenirSpec("迷你鸟窝", "🪹"), 12, 6, null),
        WalkEvent("在废弃花盆里发现了一株多肉", SouvenirSpec("小多肉", "🪴"), 14, 6, null),
        WalkEvent("捡到了一个万花筒碎片", SouvenirSpec("万花筒片", "🎆"), 12, 6, null),
        WalkEvent("发现了一块有纹路的木化石", SouvenirSpec("木化石", "🪸"), 14, 7, null),
        WalkEvent("在地上找到了一个弹弓", SouvenirSpec("小弹弓", "🏹"), 10, 5, null),
        WalkEvent("捡到了一把迷你扇子", SouvenirSpec("迷你扇子", "🪭"), 8, 4, null),
        WalkEvent("发现了一个漂流瓶", SouvenirSpec("漂流瓶", "🍶"), 16, 7, null),
        WalkEvent("在草丛里找到了一只玩具兵", SouvenirSpec("玩具小兵", "🎖️"), 10, 5, null),
        WalkEvent("捡到了一张旧明信片", SouvenirSpec("旧明信片", "🖼️"), 10, 5, null),
        WalkEvent("发现了一根孔雀羽毛", SouvenirSpec("孔雀羽毛", "🦚"), 16, 7, null),
        WalkEvent("在池塘里捞到了一颗彩色石", SouvenirSpec("五彩石", "🪩"), 12, 6, null),
        // ========== 猫咪/精灵专属 ==========
        WalkEvent("追着一只蝴蝶跑了半天", SouvenirSpec("蝴蝶标本", "🦋"), 15, 7, setOf(PetSpecies.CAT, PetSpecies.SPIRIT)),
        WalkEvent("在树上发现了一根漂亮的羽毛", SouvenirSpec("羽毛", "🪶"), 10, 5, setOf(PetSpecies.CAT, PetSpecies.SPIRIT)),
        WalkEvent("抓到了一只萤火虫放在瓶子里", SouvenirSpec("萤火虫瓶", "✨"), 16, 7, setOf(PetSpecies.CAT, PetSpecies.SPIRIT)),
        WalkEvent("在月光下发现了一颗猫眼石", SouvenirSpec("猫眼石", "😺"), 18, 8, setOf(PetSpecies.CAT, PetSpecies.SPIRIT)),
        WalkEvent("捉到了一只蜻蜓的翅膀印记", SouvenirSpec("蜻蜓翅印", "🪡"), 12, 6, setOf(PetSpecies.CAT, PetSpecies.SPIRIT)),
        WalkEvent("在夜晚追到了一只飞蛾", SouvenirSpec("月光蛾翅", "🦋"), 14, 6, setOf(PetSpecies.CAT, PetSpecies.SPIRIT)),
        WalkEvent("在树洞里找到了一团毛线球", SouvenirSpec("毛线球", "🧶"), 12, 5, setOf(PetSpecies.CAT, PetSpecies.SPIRIT)),
        WalkEvent("从鱼塘里叼了一条小鱼", SouvenirSpec("小鱼干", "🐟"), 16, 7, setOf(PetSpecies.CAT, PetSpecies.SPIRIT)),
        // ========== 狗狗/龙专属 ==========
        WalkEvent("在草地上挖到了一根骨头", SouvenirSpec("神秘骨头", "🦴"), 18, 6, setOf(PetSpecies.DOG, PetSpecies.DRAGON)),
        WalkEvent("在公园挖到了一个旧玩具球", SouvenirSpec("旧玩具球", "⚾"), 14, 5, setOf(PetSpecies.DOG, PetSpecies.DRAGON)),
        WalkEvent("在海边挖到了一只海星", SouvenirSpec("小海星", "⭐"), 16, 7, setOf(PetSpecies.DOG, PetSpecies.DRAGON)),
        WalkEvent("从泥巴里刨出了一块奇石", SouvenirSpec("泥中奇石", "💠"), 12, 6, setOf(PetSpecies.DOG, PetSpecies.DRAGON)),
        WalkEvent("用鼻子闻到了一块松露", SouvenirSpec("野生松露", "🟤"), 18, 8, setOf(PetSpecies.DOG, PetSpecies.DRAGON)),
        WalkEvent("捡回来了一根巨大的树枝", SouvenirSpec("巨型树枝", "🌲"), 14, 5, setOf(PetSpecies.DOG, PetSpecies.DRAGON)),
        WalkEvent("在沙滩上挖出了一个宝箱碎片", SouvenirSpec("宝箱碎片", "🗃️"), 16, 7, setOf(PetSpecies.DOG, PetSpecies.DRAGON)),
        WalkEvent("从草丛里叼回了一只旧手套", SouvenirSpec("旧手套", "🧤"), 10, 4, setOf(PetSpecies.DOG, PetSpecies.DRAGON)),
        // ========== 兔兔/独角兽专属 ==========
        WalkEvent("找到了一根特别甜的胡萝卜", SouvenirSpec("黄金胡萝卜", "🥕"), 15, 5, setOf(PetSpecies.RABBIT, PetSpecies.UNICORN)),
        WalkEvent("在花丛中找到了一颗花蜜糖", SouvenirSpec("花蜜糖", "🍬"), 14, 6, setOf(PetSpecies.RABBIT, PetSpecies.UNICORN)),
        WalkEvent("发现了一朵会发光的花", SouvenirSpec("萤光花", "🌺"), 16, 7, setOf(PetSpecies.RABBIT, PetSpecies.UNICORN)),
        WalkEvent("在草地上找到了一顶小花冠", SouvenirSpec("花冠", "👑"), 14, 6, setOf(PetSpecies.RABBIT, PetSpecies.UNICORN)),
        WalkEvent("收集了一捧彩色花瓣", SouvenirSpec("彩色花瓣", "🎀"), 12, 5, setOf(PetSpecies.RABBIT, PetSpecies.UNICORN)),
        WalkEvent("在仙境般的草地上发现了仙尘", SouvenirSpec("仙尘", "✨"), 18, 8, setOf(PetSpecies.RABBIT, PetSpecies.UNICORN)),
        WalkEvent("找到了一颗会变色的石头", SouvenirSpec("变色石", "🔮"), 16, 7, setOf(PetSpecies.RABBIT, PetSpecies.UNICORN)),
        WalkEvent("发现了一片闪闪发光的苜蓿草", SouvenirSpec("星光苜蓿", "🌟"), 14, 6, setOf(PetSpecies.RABBIT, PetSpecies.UNICORN)),
        // ========== 仓鼠专属 ==========
        WalkEvent("发现了一颗亮晶晶的种子", SouvenirSpec("星星种子", "✨"), 12, 6, setOf(PetSpecies.HAMSTER)),
        WalkEvent("找到了一颗超大的葵花籽", SouvenirSpec("巨型葵花籽", "🌻"), 14, 6, setOf(PetSpecies.HAMSTER)),
        WalkEvent("收集了一小堆各种种子", SouvenirSpec("种子宝库", "🫘"), 12, 5, setOf(PetSpecies.HAMSTER)),
        WalkEvent("在仓库角落发现了花生", SouvenirSpec("花生仁", "🥜"), 10, 5, setOf(PetSpecies.HAMSTER)),
        WalkEvent("找到了一颗完美的玉米粒", SouvenirSpec("金色玉米粒", "🌽"), 12, 5, setOf(PetSpecies.HAMSTER)),
        WalkEvent("在谷仓旁捡到了一粒小麦", SouvenirSpec("麦粒", "🌾"), 8, 4, setOf(PetSpecies.HAMSTER)),
        WalkEvent("收藏了一颗迷你南瓜籽", SouvenirSpec("南瓜籽", "🎃"), 10, 5, setOf(PetSpecies.HAMSTER)),
        WalkEvent("找到了一颗开心果壳做的小碗", SouvenirSpec("果壳小碗", "🥣"), 12, 6, setOf(PetSpecies.HAMSTER)),
    )
}
