package com.situ.aichat.world.stage

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.PetDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.local.dao.WorldNativeDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.notification.NotificationScheduleRules
import com.situ.aichat.world.WorldClock
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.atlas.WorldAtlas
import com.situ.aichat.world.atlas.WorldCuratedCities
import com.situ.aichat.world.cast.WorldAffinityService
import com.situ.aichat.world.cast.WorldAffinityStage
import com.situ.aichat.world.cast.WorldNativeRoster
import com.situ.aichat.world.travel.WorldTravelService
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 演员站位模式（W9d 图纸 §3.2·R1 🟡-2 裁决）。[AT_HOME] = 白天在家（民居呈现位·卡片正常呼吸·无月牙·下标「在家」）；
 * [SLEEPING] = 睡眠判定命中（民居呈现位·月牙 +「睡着了」）。两者同民居锚，区别在呼吸/月牙/文案。
 */
enum class StageMode { AT_PLACE, IN_TOWN, SLEEPING, AT_HOME }

/**
 * 加入世界的角色此刻在镇上的站位（W9d 图纸 §3.2）。
 *
 * [statusLine] = **代码拼的动态活动行**「{periodLabel}·{activity}」（有当前非睡眠事件时）；固定文案的四态
 * （无事件/在城中/来访/睡着）取空串 `""`，由 UI 层按 [mode]+[visiting] 解析 §4.10 资源串（服务层无 Context·§2 dep）。
 */
data class StagedCharacter(
    val uuid: String,
    val name: String,
    val avatarPath: String?,
    val mode: StageMode,
    val placeId: String?, // AT_PLACE 时非空
    val statusLine: String,
    val visiting: Boolean, // presence 城 ≠ 自家城
)

/** 未招募原住民的站位（W9d 图纸 §3.2）。[placeId] = 精修城常驻点（程序城 null）。 */
data class StagedNative(
    val nativeId: String,
    val slug: String,
    val name: String,
    val discovered: Boolean,
    val placeId: String?,
    val oneLiner: String,
    val stage: WorldAffinityStage,
)

/** 用户家城里的宠物入口（W9d 图纸 §3.2·序 = adoptedDate 升序·呈现上限 3）。 */
data class StagedPet(val petUuid: String, val ownerUuid: String, val name: String)

/** 一座城的演员表快照（W9d 图纸 §3.2）。 */
data class WorldTownCast(
    val cityId: String,
    val characters: List<StagedCharacter>,
    val natives: List<StagedNative>,
    val pets: List<StagedPet>,
)

/** 用户在途 chip 数据（W9d 图纸 §3.2/§4.8）。 */
data class UserTravelChip(val destCityId: String, val destName: String, val arriveAtMs: Long)

/**
 * 单角色此刻位置行（W13 聊天状态行图纸 §3.6）：派生只读态。[kind] 五态映射 UI 胶囊；AT_PLACE 携 placeName/placeType，
 * TRAVELING 携 destCityId/destCityName（去 TA 要到的地方等 TA）。
 */
data class WorldPresenceLine(
    val kind: Kind,
    val placeName: String?,
    val placeType: WorldStageResolver.PlaceType?,
    // placeId：AT_PLACE 携带（图纸 §3.6 focusSpec `interior:{cityId}:{placeId}` 需·§3.6 数据类原列表遗漏·见 §11）。
    val placeId: String?,
    val cityId: String,
    val cityName: String,
    val destCityId: String?,
    val destCityName: String?,
) {
    enum class Kind { AT_PLACE, AT_HOME, SLEEPING, IN_TOWN, TRAVELING }
}

/**
 * 日程生成的世界上下文（W9d 图纸 §3.5/§4.4·只加输入）：世界城名（覆盖「所在城市」+ 入库 cityName）、
 * 本城真实地点名表（剔除「你的家」·程序城空）、天气行（prompt 追加）、天气词/emoji（入库两列）。
 */
data class WorldScheduleContext(
    val cityName: String,
    val placeNames: List<String>,
    val weatherLine: String,
    val weatherCondition: String,
    val weatherEmoji: String,
)

/**
 * 世界演员表服务（W9d 图纸 §3.2/§4.6·§14.A-1/2/5/6/8/9）：把「谁此刻在哪座城、在哪个地点」落成快照供 W9 地图呈现，
 * 并转发 W6 招募触发（发现/偶遇·幂等·gate 决策 15）。
 *
 * 站位管线（读侧·§3.2）：加入角色经 [WorldTravelService] 位置三态 + 日程 [NotificationScheduleRules.currentEvent]
 * 经 [WorldStageResolver] 兜底解析落到镇上；原住民按当前城/未招募上镇（已识名卡/未识神秘卡）；宠物只在用户家城。
 * **不碰钱路、不改任何 world 既有文件**——只读 DAO + 转调既有服务。时区统一用世界态 [WorldClock.resolveZone]。
 */
@Singleton
class WorldStageService @Inject constructor(
    private val characterDao: CharacterDao,
    private val scheduleDao: ScheduleDao,
    private val worldNativeDao: WorldNativeDao,
    private val petDao: PetDao,
    private val worldDao: WorldDao,
    private val travelService: WorldTravelService,
    private val affinityService: WorldAffinityService,
) {

    private companion object {
        /** 呈现上限（§4.6A·宠物前 3）。 */
        const val MAX_PETS = 3
        const val ACTIVITY_MAX_CHARS = 14

        /** 用户家 placeId（§4.4 日程地点表剔除·唯一一处不解析给角色的地点）。 */
        const val USER_HOME_PLACE_ID = "yunye_home"
    }

    /**
     * 城市演员表快照（§3.2·IO）：加入角色（presence + 日程 + 睡眠）/ 未招募原住民（已识/神秘）/ 用户家城宠物。
     * 无世界态（未初始化）→ 空快照。
     */
    suspend fun castOf(cityId: String, nowMs: Long): WorldTownCast {
        // 🔵-6：首启时序空窗兜底——W6 ensureSeeded 挂 Runner 前台通行证，首会话直进世界可能撞上「原住民未播种」空窗。
        // 幂等（只补缺）·IO 已在后台线程·失败不阻断演员表（§4「无空手而归」）。
        runCatching { affinityService.ensureSeeded() }
        val state = worldDao.getState() ?: return WorldTownCast(cityId, emptyList(), emptyList(), emptyList())
        val zone = WorldClock.resolveZone(state.userTimezoneId)
        val cityPlaces = WorldCuratedCities.PLACES
            .filter { it.cityId == cityId }
            .map { WorldStageResolver.CityPlace(it.id, it.name) }

        // 1. 加入世界的角色。
        val characters = characterDao.getInWorld().mapNotNull { ch ->
            val presence = travelService.characterPresence(ch.uuid, nowMs)
            if (presence.inTransitToCityId != null) return@mapNotNull null // 在途 → 不上任何镇
            if (presence.cityId != cityId) return@mapNotNull null // presence 异城 → 不上本镇
            stageCharacter(ch.uuid, ch.name, ch.avatarPath, cityId != ch.worldHomeCityId, cityPlaces, nowMs, zone)
        }

        // 2. 未招募原住民（当前城匹配 + 招募指针空）。
        val natives = worldNativeDao.getAll().mapNotNull { row ->
            if (row.recruitedCharacterUuid != null) return@mapNotNull null // 已招募 → 走角色管线
            val def = WorldNativeRoster.byNativeId(row.nativeId) ?: return@mapNotNull null
            if ((row.currentCityId ?: def.cityId) != cityId) return@mapNotNull null
            StagedNative(
                nativeId = row.nativeId,
                slug = def.slug,
                name = def.name,
                discovered = row.discovered,
                placeId = def.placeId, // 程序城 null
                oneLiner = def.oneLiner,
                stage = WorldAffinityService.stageOf(row, def),
            )
        }

        // 3. 用户家城宠物（序 = adoptedDate 升序·上限 3）。
        val pets = if (cityId == state.userHomeCityId) {
            petDao.getAll().sortedBy { it.adoptedDate }.take(MAX_PETS)
                .map { StagedPet(it.uuid, it.characterUuid, it.name) }
        } else {
            emptyList()
        }

        return WorldTownCast(cityId, characters, natives, pets)
    }

    /**
     * 把一个在本镇的角色落成站位（§3.2 step 1）。visiting（presence 城 ≠ 自家城）→ 恒 IN_TOWN（不解析异城日程·
     * 宁可模糊绝不站错）。在家城 → 读当日日程当前事件：睡眠 → SLEEPING；否则兜底解析 location。
     */
    private suspend fun stageCharacter(
        uuid: String,
        name: String,
        avatarPath: String?,
        visiting: Boolean,
        cityPlaces: List<WorldStageResolver.CityPlace>,
        nowMs: Long,
        zone: java.time.ZoneId,
    ): StagedCharacter {
        fun card(mode: StageMode, placeId: String?, statusLine: String) =
            StagedCharacter(uuid, name, avatarPath, mode, placeId, statusLine, visiting)

        if (visiting) return card(StageMode.IN_TOWN, null, "") // 恒 IN_TOWN·不解析

        val dayStart = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val schedule = scheduleDao.scheduleFor(uuid, dayStart)
        val events = if (schedule != null) scheduleDao.eventsForSchedule(schedule.uuid) else emptyList()
        val event = NotificationScheduleRules.currentEvent(events, nowMs)
            ?: return card(StageMode.IN_TOWN, null, "") // 无日程/无当前事件 → IN_TOWN（固定文案）

        if (NotificationScheduleRules.isSleepEvent(event, nowMs, zone)) {
            return card(StageMode.SLEEPING, null, "") // 睡眠 → SLEEPING（固定「睡着了」文案）
        }

        return when (val r = WorldStageResolver.resolve(event.location, cityPlaces)) {
            is WorldStageResolver.Resolution.AtPlace -> card(StageMode.AT_PLACE, r.placeId, activityLine(event))
            // AT_HOME_HOUSE → AT_HOME（R1 🟡-2·民居呈现位·呼吸卡·statusLine 保活动行→「上午·在家做饭」照常显示；空则 UI 走「在家」body）。
            WorldStageResolver.Resolution.AtHomeHouse -> card(StageMode.AT_HOME, null, activityLine(event))
            WorldStageResolver.Resolution.InTown -> card(StageMode.IN_TOWN, null, activityLine(event))
        }
    }

    /** 活动行（§4.10·「{periodLabel}·{activity}」·activity 截 14 字加 …·periodLabel 空则只 activity）。 */
    private fun activityLine(event: ScheduleEventEntity): String {
        val act = if (event.activity.length > ACTIVITY_MAX_CHARS) {
            event.activity.take(ACTIVITY_MAX_CHARS) + "…"
        } else {
            event.activity
        }
        return if (event.periodLabel.isBlank()) act else "${event.periodLabel}·$act"
    }

    /** 用户在途 chip（§3.2·`now < arriveAt` → 目的城名经 atlas 解析）。 */
    suspend fun userTravel(nowMs: Long): UserTravelChip? {
        val travel = worldDao.getTravel(WorldIds.USER_ID) ?: return null
        if (nowMs >= travel.arriveAt) return null
        val state = worldDao.getState() ?: return null
        val destName = WorldAtlas.of(state.seed).cityById(travel.toCityId)?.name ?: "远方"
        return UserTravelChip(travel.toCityId, destName, travel.arriveAt)
    }

    /**
     * 单角色此刻位置行（W13 图纸 §3.6·聊天状态行供血）：查无 / 未加入世界 → null；**世界未建（state null）→ null·
     * 绝不 ensureCreated**（聊天路径零建世副作用）；在途 → TRAVELING；否则经 [castOf] 复用取本城演员表按 uuid 命中，
     * 按 [StagedCharacter.mode] 五态映射（未命中边缘竞态 → 退 IN_TOWN·E16）。placeName/placeType 由 placeId 经图集
     * 地点与 [WorldStageResolver.placeTypeOf] 只读取。
     */
    suspend fun presenceLineFor(characterUuid: String, nowMs: Long): WorldPresenceLine? {
        val character = characterDao.getByUuid(characterUuid) ?: return null
        if (!character.joinedWorld) return null
        val state = worldDao.getState() ?: return null // 绝不建世（§9·聊天零副作用）
        val atlas = WorldAtlas.of(state.seed)
        fun cityNameOf(id: String) = atlas.cityById(id)?.name ?: "远方"

        val presence = travelService.characterPresence(characterUuid, nowMs)
        presence.inTransitToCityId?.let { dest ->
            return WorldPresenceLine(
                kind = WorldPresenceLine.Kind.TRAVELING,
                placeName = null, placeType = null, placeId = null,
                cityId = presence.cityId, cityName = cityNameOf(presence.cityId),
                destCityId = dest, destCityName = cityNameOf(dest),
            )
        }

        fun line(
            kind: WorldPresenceLine.Kind,
            placeName: String? = null,
            placeType: WorldStageResolver.PlaceType? = null,
            placeId: String? = null,
        ) = WorldPresenceLine(kind, placeName, placeType, placeId, presence.cityId, cityNameOf(presence.cityId), null, null)

        val staged = castOf(presence.cityId, nowMs).characters.firstOrNull { it.uuid == characterUuid }
            ?: return line(WorldPresenceLine.Kind.IN_TOWN) // E16 演员表未命中（换城竞态）→ 退 IN_TOWN
        return when (staged.mode) {
            StageMode.AT_PLACE -> line(
                WorldPresenceLine.Kind.AT_PLACE,
                placeName = atlas.placesOf(presence.cityId).firstOrNull { it.id == staged.placeId }?.name,
                placeType = staged.placeId?.let { WorldStageResolver.placeTypeOf(it) },
                placeId = staged.placeId,
            )
            StageMode.AT_HOME -> line(WorldPresenceLine.Kind.AT_HOME)
            StageMode.SLEEPING -> line(WorldPresenceLine.Kind.SLEEPING)
            StageMode.IN_TOWN -> line(WorldPresenceLine.Kind.IN_TOWN)
        }
    }

    /** 用户当前所在城（gate 用·转调 [WorldTravelService.userPresence]）。 */
    suspend fun userPresenceCityId(nowMs: Long): String = travelService.userPresence(nowMs).cityId

    /** 用户是否在途（gate 用·转调 [WorldTravelService.isUserTraveling]）。 */
    suspend fun isUserTraveling(nowMs: Long): Boolean = travelService.isUserTraveling(nowMs)

    /**
     * W6 招募触发转发（§3.2·幂等·先验 gate：用户在原住民所在城 && 未在途，否则 no-op 返 false）：
     * 未发现 → discover + recordEncounter 返 true（= 新发现）；已发现 → recordEncounter 返 false。
     */
    suspend fun onMeetNative(nativeId: String, nowMs: Long): Boolean {
        val def = WorldNativeRoster.byNativeId(nativeId) ?: return false
        val row = worldNativeDao.get(nativeId) ?: return false
        if (row.recruitedCharacterUuid != null) return false // 已招募（已是角色）→ no-op
        val nativeCity = row.currentCityId ?: def.cityId
        // gate（决策 15）：用户须在该城且未在途。
        if (userPresenceCityId(nowMs) != nativeCity) return false
        if (isUserTraveling(nowMs)) return false

        val newlyDiscovered = !row.discovered
        if (newlyDiscovered) affinityService.discover(nativeId, nowMs)
        affinityService.recordEncounter(nativeId, nowMs)
        return newlyDiscovered
    }

    /**
     * 日程生成的世界上下文（§3.5/§4.4·写侧只加输入）：基于角色 [CharacterEntity.worldHomeCityId] 解析世界城名 +
     * 本城地点名表（剔除用户家「你的家」·程序城空表）+ 当日（日间）天气行与词/emoji。无世界态 → null（退现行为）。
     */
    suspend fun scheduleContextFor(character: CharacterEntity, dateMillis: Long): WorldScheduleContext? {
        val state = worldDao.getState() ?: return null
        val zone = WorldClock.resolveZone(state.userTimezoneId)
        val cityId = character.worldHomeCityId
        val cityName = WorldAtlas.of(state.seed).cityById(cityId)?.name ?: "远方"
        val placeNames = WorldCuratedCities.PLACES
            .filter { it.cityId == cityId && it.id != USER_HOME_PLACE_ID } // 剔除「你的家」（§4.4）
            .map { it.name }
        val localDate = Instant.ofEpochMilli(dateMillis).atZone(zone).toLocalDate()
        val kind = WorldWeather.kindOf(state.seed, cityId, localDate)
        val word = WorldWeather.word(kind, night = false) // 日程 = 日间天气词
        val emoji = WorldWeather.emoji(kind, night = false)
        return WorldScheduleContext(
            cityName = cityName,
            placeNames = placeNames,
            weatherLine = "今天${cityName}的天气：$word", // §4.4 锁死格式
            weatherCondition = word,
            weatherEmoji = emoji,
        )
    }
}
