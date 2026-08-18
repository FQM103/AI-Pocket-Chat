package com.situ.aichat.ui.world

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.world.continent.ContinentSite

/**
 * 小镇头像卡覆盖层（W9d 图纸 §4.6A·town demo:L24-31/L221-227 逐值）+ 卡→人物站点卡内容装配（§4.6C）。
 * 卡片位置由 [TownSceneView] 投影后经 modifier 定位；色单源 [WorldSceneColors]。
 */

/** 一张小镇卡（角色/原住民名/神秘/宠物/睡眠）·底部中心锚·呼吸相位按 [phaseIndex]×0.6s（睡眠/神秘无呼吸）。 */
@Composable
internal fun TownCastCard(kind: CastCardKind, phaseIndex: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    when (kind) {
        is CastCardKind.Character -> {
            val a11y = stringResource(R.string.world_card_character_a11y, kind.staged.name, "")
            // R1 🟡-2：睡眠→「睡着了」+ 月牙；白天在家→「在家」无月牙呼吸卡；其余无下标。
            val sub = when {
                kind.sleeping -> stringResource(R.string.world_card_sleeping)
                kind.atHome -> stringResource(R.string.world_card_at_home)
                else -> null
            }
            AvatarCard(kind.staged.name, kind.staged.avatarPath, sub, breathe = !kind.sleeping, moon = kind.sleeping, phaseIndex, a11y, onClick, modifier)
        }
        is CastCardKind.Native -> {
            if (kind.staged.discovered) {
                val a11y = stringResource(R.string.world_card_character_a11y, kind.staged.name, "")
                AvatarCard(kind.staged.name, null, null, breathe = true, moon = false, phaseIndex, a11y, onClick, modifier)
            } else {
                MysteryCard(onClick, modifier)
            }
        }
        is CastCardKind.Pet -> PetCard(kind.staged.name, onClick, modifier)
    }
}

/** 小镇卡下标文字投影（🟡-3·§4.6A / town demo:L30 `.card small` text-shadow 0 1px 5px rgba(20,26,44,.8)）。 */
@Composable
private fun cardLabelShadow(): Shadow = with(LocalDensity.current) {
    Shadow(color = Color(0xCC141A2C), offset = Offset(0f, 1.dp.toPx()), blurRadius = 5.dp.toPx())
}

/** 圆脸卡（角色/原住民名·34dp + 描边 2.5dp + 投影·名字 10.5sp·呼吸 3.4s·睡眠 [moon] 月牙点）。 */
@Composable
private fun AvatarCard(name: String, avatarPath: String?, sub: String?, breathe: Boolean, moon: Boolean, phaseIndex: Int, a11y: String, onClick: () -> Unit, modifier: Modifier) {
    val scale = if (!breathe) 1f else {
        val t = rememberInfiniteTransition(label = "castBreathe")
        val f by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3400, easing = AppMotion.EaseInOut), RepeatMode.Reverse, StartOffset(600 * phaseIndex)), label = "b$phaseIndex")
        f
    }
    Box(modifier.clickableScale(role = Role.Button, onClick = onClick).sizeIn(minWidth = 48.dp, minHeight = 48.dp).clearAndSetSemantics { contentDescription = a11y }, contentAlignment = Alignment.BottomCenter) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.TopEnd) {
                Box(
                    Modifier
                        .graphicsLayer { val s = if (breathe) 1f + 0.05f * scale else 1f; scaleX = s; scaleY = s; translationY = if (breathe) -2.dp.toPx() * scale else 0f }
                        .shadow(12.dp, CircleShape, spotColor = Color(0x66141A2C), ambientColor = Color(0x66141A2C))
                        .clip(CircleShape)
                        .border(2.5.dp, WorldSceneColors.cardStroke, CircleShape),
                ) { CharacterAvatar(name = name, avatarPath = avatarPath, size = 34.dp) }
                if (moon) Box(Modifier.size(8.dp).clip(CircleShape).background(WorldSceneColors.gold))
            }
            val shadow = cardLabelShadow()
            Text(name, color = WorldSceneColors.onGlass, fontSize = 10.5.sp, style = TextStyle(shadow = shadow), modifier = Modifier.padding(top = 3.dp))
            if (sub != null) Text(sub, color = WorldSceneColors.pcardStatus, fontSize = 10.sp, style = TextStyle(shadow = shadow))
        }
    }
}

/** 神秘人卡（34dp 圆 #4A4E5E 虚线描边·中置「?」·无呼吸·下标「还没认识的人」·town demo）。 */
@Composable
private fun MysteryCard(onClick: () -> Unit, modifier: Modifier) {
    val a11y = stringResource(R.string.world_card_mystery_a11y)
    val sub = stringResource(R.string.world_card_unknown)
    val stroke = WorldSceneColors.cardStroke
    Box(modifier.clickableScale(role = Role.Button, onClick = onClick).sizeIn(minWidth = 48.dp, minHeight = 48.dp).clearAndSetSemantics { contentDescription = a11y }, contentAlignment = Alignment.BottomCenter) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(WorldSceneColors.mystery)
                    .drawBehind {
                        drawCircle(color = stroke, radius = size.minDimension / 2f - 1.25.dp.toPx(), style = Stroke(width = 2.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))))
                    },
                contentAlignment = Alignment.Center,
            ) { Text("?", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            Text(sub, color = WorldSceneColors.pcardStatus, fontSize = 10.sp, style = TextStyle(shadow = cardLabelShadow()), modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/** 宠物卡（圆底 #C9A06B「🐾」·下标宠物名·town demo）。 */
@Composable
private fun PetCard(name: String, onClick: () -> Unit, modifier: Modifier) {
    val a11y = stringResource(R.string.world_card_pet_a11y, name)
    Box(modifier.clickableScale(role = Role.Button, onClick = onClick).sizeIn(minWidth = 48.dp, minHeight = 48.dp).clearAndSetSemantics { contentDescription = a11y }, contentAlignment = Alignment.BottomCenter) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(WorldSceneColors.petBadge).border(2.5.dp, WorldSceneColors.cardStroke, CircleShape), contentAlignment = Alignment.Center) {
                Text("🐾", fontSize = 16.sp)
            }
            Text(name, color = WorldSceneColors.onGlass, fontSize = 10.5.sp, style = TextStyle(shadow = cardLabelShadow()), modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/** 溢出「+N」chip（不可点·§4.6A）。 */
@Composable
internal fun TownOverflowCard(count: Int, modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(WorldSceneColors.glassChip).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(stringResource(R.string.world_card_overflow, count), color = WorldSceneColors.onGlass, fontSize = 12.sp)
    }
}

/**
 * 小镇人物/宠物站点卡内容装配（§4.6C·复用 WorldSiteSheet）。原住民在场无按钮·不在场旅行提示行 + 出发；
 * 角色去聊天；宠物去看看。首遇「打了照面」由 [metNativeId] 标记。
 */
@Composable
internal fun BoxScope.TownCastSheet(
    selection: CastCardKind?,
    cityName: String,
    userPresent: Boolean,
    reduceMotion: Boolean,
    metNativeId: String?,
    onClose: () -> Unit,
    onOpenChat: (String, String) -> Unit,
    onOpenPet: (String) -> Unit,
    onTravelToCity: () -> Unit,
    onDismissResident: (String, String) -> Unit, // 战役 B（O6·§4.3）：自建未招募居民「送 TA 离开」→ 暖纸确认
) {
    val chatLabel = stringResource(R.string.world_person_chat)
    val dismissLabel = stringResource(R.string.world_resident_dismiss_action)
    val petVisit = stringResource(R.string.world_pet_visit)
    val travelLabel = stringResource(R.string.world_travel_to_city, cityName)
    val metFirst = stringResource(R.string.world_native_met_first_line)
    val sleepingBody = stringResource(R.string.world_person_sleeping_body)
    val atHomeBody = stringResource(R.string.world_person_at_home_body)
    val inTownBody = stringResource(R.string.world_person_in_town_body)
    val mysteryBody = stringResource(R.string.world_mystery_body)
    val unknownTitle = stringResource(R.string.world_card_unknown) // 🔵-1：神秘人卡标题（与卡片下标一致·非字面「?」）

    // Triple 第三项 = firstActionGhost（🟡-2）：仅送 TA 离开一个动作的居民卡令 slot0 也走幽灵；其余卡恒 false。
    val model: Triple<ContinentSite, List<Pair<String, () -> Unit>>, Boolean>? = when (selection) {
        is CastCardKind.Character -> {
            val c = selection.staged
            val body = when {
                c.statusLine.isNotEmpty() -> c.statusLine
                c.visiting -> stringResource(R.string.world_person_visiting_body, cityName)
                selection.sleeping -> sleepingBody
                selection.atHome -> atHomeBody // R1 🟡-2：白天在家无活动行 → 「在家待着」body
                else -> inTownBody
            }
            Triple(site(c.uuid, c.name, body), listOf(chatLabel to { onOpenChat(c.uuid, c.name) }), false)
        }
        is CastCardKind.Native -> {
            val n = selection.staged
            if (n.discovered) {
                val base = "${n.oneLiner}\n${n.stage.phrase}"
                val body = if (metNativeId == n.nativeId) "$metFirst\n$base" else base
                val fullBody = if (userPresent) body else "$body\n${stringResource(R.string.world_native_travel_hint, cityName)}"
                // 不在场 → 主「去这座城」；自建未招募居民 → 幽灵「送 TA 离开」占次位（≤2·E7）。
                val actions = buildList {
                    if (!userPresent) add(travelLabel to onTravelToCity)
                    if (isUserResidentNative(n.nativeId)) add(dismissLabel to { onDismissResident(n.nativeId, n.name) })
                }
                // 🟡-2：在场时居民卡只剩「送 TA 离开」一个动作 → slot0 也走幽灵（破坏性动作不冒充主 CTA）。
                val firstActionGhost = isUserResidentNative(n.nativeId) && actions.size == 1
                Triple(site(n.nativeId, n.name, fullBody), actions, firstActionGhost)
            } else {
                // 未发现（朦胧）→ 不显示送离：卡片/弹窗都不露真名，露则破坏朦胧显示（决策 24·§11 记）。
                if (userPresent) Triple(site(n.nativeId, unknownTitle, mysteryBody), emptyList(), false)
                else Triple(site(n.nativeId, unknownTitle, "$mysteryBody\n${stringResource(R.string.world_native_travel_hint, cityName)}"), listOf(travelLabel to onTravelToCity), false)
            }
        }
        is CastCardKind.Pet -> {
            val body = stringResource(R.string.world_pet_body, selection.staged.name)
            Triple(site(selection.staged.petUuid, selection.staged.name, body), listOf(petVisit to { onOpenPet(selection.staged.ownerUuid) }), false)
        }
        null -> null
    }
    WorldSiteSheet(model?.first, reduceMotion, onClose, Modifier.align(Alignment.BottomCenter), model?.second ?: emptyList(), model?.third ?: false)
}

private fun site(id: String, name: String, body: String): ContinentSite =
    ContinentSite(id, name, isWonder = false, isHome = false, curated = true, x = 0f, z = 0f, markerTop = 0f, buildingCount = 0, body = body)
