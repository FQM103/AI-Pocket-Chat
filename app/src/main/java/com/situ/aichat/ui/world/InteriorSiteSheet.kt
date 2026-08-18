package com.situ.aichat.ui.world

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.situ.aichat.R
import com.situ.aichat.pet.EggNestState
import com.situ.aichat.ui.world.continent.ContinentSite

/**
 * 室内站点卡内容装配（W9d 图纸 §4.6C/§4.10）：把 [InteriorSelection] 转成 [WorldSiteSheet] 的展示 [ContinentSite]
 * + 动作。场景点 = 标题/正文资源无按钮；guest 人物 = 名字/statusLine + 去聊天；原住民 = oneLiner + stage 短语（首遇
 * 追加「打了照面」）无按钮；宠物 = 宠物正文 + 去看看。§2「再拆」小文件（记 §11）。
 */
@Composable
internal fun BoxScope.InteriorSiteSheet(
    selection: InteriorSelection?,
    placeName: String?,
    metNativeId: String?,
    reduceMotion: Boolean,
    onClose: () -> Unit,
    onOpenQuickChat: (String, String, String) -> Unit, // W12 C6：坐下说两句（uuid/name/statusLine）→ 快聊弹窗（聊天页入口移至弹窗头部）
    onOpenMeet: (String, String, String) -> Unit, // W12 C7：去打个招呼（nativeId/name/placeName）→ 初遇弹窗（仅 isWilling）
    onDismissResident: (String, String) -> Unit, // 战役 B（O6·§4.3）：自建未招募居民「送 TA 离开」（nativeId/name）→ 暖纸确认
    onOpenPet: (String) -> Unit,
    // W12.5 家的蛋巢（决策 42·仅在 selection=Nest 时用）：三态站点卡文案 + 动作（空→之约·在孵→去看进度·可孵化→迎接）。
    nestState: EggNestState,
    onOpenNestPact: () -> Unit,
    onWelcomeHatch: (String) -> Unit,
) {
    val sitLabel = stringResource(R.string.world_qc_sit)
    val helloLabel = stringResource(R.string.world_meet_hello)
    val dismissLabel = stringResource(R.string.world_resident_dismiss_action)
    val petVisit = stringResource(R.string.world_pet_visit)
    val metFirst = stringResource(R.string.world_native_met_first_line)

    // Triple 第三项 = firstActionGhost（🟡-2）：仅送 TA 离开一个动作的居民卡令 slot0 也走幽灵；其余卡恒 false。
    val model: Triple<ContinentSite, List<Pair<String, () -> Unit>>, Boolean>? = when (selection) {
        is InteriorSelection.Spot -> {
            val title = stringResource(spotTitleRes(selection.spot.id))
            val body = stringResource(spotBodyRes(selection.spot.id))
            Triple(site(selection.spot.id, title, body), emptyList(), false)
        }
        is InteriorSelection.Person -> {
            val g = selection.staged
            Triple(site(g.uuid, g.name, g.statusLine), listOf(sitLabel to { onOpenQuickChat(g.uuid, g.name, g.statusLine) }), false)
        }
        is InteriorSelection.Native -> {
            val n = selection.staged
            val base = "${n.oneLiner}\n${n.stage.phrase}"
            val body = if (metNativeId == n.nativeId) "$metFirst\n$base" else base
            // isWilling（stage==WILLING·= affinity≥门槛·cast 已算·§4.7）→ 主「去打个招呼」；自建未招募居民 → 幽灵「送 TA 离开」占次位（≤2·E7）。
            val actions = buildList {
                if (n.stage == com.situ.aichat.world.cast.WorldAffinityStage.WILLING) {
                    add(helloLabel to { onOpenMeet(n.nativeId, n.name, placeName.orEmpty()) })
                }
                if (isUserResidentNative(n.nativeId)) {
                    add(dismissLabel to { onDismissResident(n.nativeId, n.name) })
                }
            }
            // 🟡-2：非 WILLING 自建居民只剩「送 TA 离开」一个动作 → slot0 也走幽灵（破坏性动作不冒充主 CTA）。
            val firstActionGhost = isUserResidentNative(n.nativeId) && actions.size == 1
            Triple(site(n.nativeId, n.name, body), actions, firstActionGhost)
        }
        is InteriorSelection.Pet -> {
            val body = stringResource(R.string.world_pet_body, selection.pet.name)
            Triple(site(selection.pet.petUuid, selection.pet.name, body), listOf(petVisit to { onOpenPet(selection.pet.ownerUuid) }), false)
        }
        is InteriorSelection.Nest -> when (val ns = nestState) {
            EggNestState.Empty -> Triple(site("egg_nest", stringResource(R.string.world_nest_title), stringResource(R.string.world_nest_empty_body)),
                listOf(stringResource(R.string.world_nest_empty_cta) to onOpenNestPact), false)
            is EggNestState.Incubating -> Triple(site("egg_nest", stringResource(R.string.world_nest_incubating_title, ns.characterName), stringResource(R.string.world_nest_incubating_body)),
                listOf(stringResource(R.string.world_nest_incubating_cta) to { onOpenPet(ns.characterUuid) }), false) // 去看孵化进度 → petDetail（数值只在那里·决策 42②）
            is EggNestState.Hatchable -> Triple(site("egg_nest", stringResource(R.string.world_nest_hatchable_title), stringResource(R.string.world_nest_hatchable_body)),
                listOf(stringResource(R.string.world_nest_hatchable_cta) to { onWelcomeHatch(ns.characterUuid) }), false)
        }
        null -> null
    }

    WorldSiteSheet(
        site = model?.first,
        reduceMotion = reduceMotion,
        onClose = onClose,
        modifier = Modifier.align(Alignment.BottomCenter),
        actions = model?.second ?: emptyList(),
        firstActionGhost = model?.third ?: false,
    )
}

private fun site(id: String, name: String, body: String): ContinentSite =
    ContinentSite(id, name, isWonder = false, isHome = false, curated = true, x = 0f, z = 0f, markerTop = 0f, buildingCount = 0, body = body)

/**
 * 该 nativeId 是否为用户自建居民（战役 B·§4.3·[WorldCastOverlays] 同包复用）：`native:resident_*`。
 * 官方原住民（人名拼音 slug）恒 false；地图上的 Native 卡本就只出未招募者（招募后成正式角色）→ 无需再验未招募。
 */
internal fun isUserResidentNative(nativeId: String): Boolean =
    nativeId.removePrefix(com.situ.aichat.world.WorldIds.NATIVE_PREFIX)
        .startsWith(com.situ.aichat.world.cast.WorldResidentService.RESIDENT_SLUG_PREFIX)

/** 场景点标题资源（§4.10·十一条·当映射避 getIdentifier）。 */
private fun spotTitleRes(id: String): Int = when (id) {
    "cafe_seat" -> R.string.world_spot_title_cafe_seat
    "cafe_bar" -> R.string.world_spot_title_cafe_bar
    "home_sofa" -> R.string.world_spot_title_home_sofa
    "book_counter" -> R.string.world_spot_title_book_counter
    "eat_stove" -> R.string.world_spot_title_eat_stove
    "kiln_mouth" -> R.string.world_spot_title_kiln_mouth
    "market_stall" -> R.string.world_spot_title_market_stall
    "shop_wheel" -> R.string.world_spot_title_shop_wheel
    "tea_hearth" -> R.string.world_spot_title_tea_hearth
    "fish_ice" -> R.string.world_spot_title_fish_ice
    else -> R.string.world_spot_title_hall_chime
}

/** 场景点正文资源（§4.10·十一条）。 */
private fun spotBodyRes(id: String): Int = when (id) {
    "cafe_seat" -> R.string.world_spot_body_cafe_seat
    "cafe_bar" -> R.string.world_spot_body_cafe_bar
    "home_sofa" -> R.string.world_spot_body_home_sofa
    "book_counter" -> R.string.world_spot_body_book_counter
    "eat_stove" -> R.string.world_spot_body_eat_stove
    "kiln_mouth" -> R.string.world_spot_body_kiln_mouth
    "market_stall" -> R.string.world_spot_body_market_stall
    "shop_wheel" -> R.string.world_spot_body_shop_wheel
    "tea_hearth" -> R.string.world_spot_body_tea_hearth
    "fish_ice" -> R.string.world_spot_body_fish_ice
    else -> R.string.world_spot_body_hall_chime
}
