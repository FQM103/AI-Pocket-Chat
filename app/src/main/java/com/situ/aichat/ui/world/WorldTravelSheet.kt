package com.situ.aichat.ui.world

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.world.WorldClock
import com.situ.aichat.world.travel.TravelOption
import java.time.Instant

/** 旅行单结果态（余额不足红字 / 通用失败·§4.8·Departed 由调用方关单）。 */
sealed interface TravelSheetResult {
    data class InsufficientGold(val need: Int, val have: Int) : TravelSheetResult
    data object Failed : TravelSheetResult
}

private val SheetEasing = CubicBezierEasing(0.3f, 1.2f, 0.4f, 1f)

/**
 * 旅行单（W9d 图纸 §4.8·暖纸面同 [WorldSiteSheet] 族）：标题「去{城名}」+ 副行「{里} · 从{城}出发」+ 选项行
 * （2–3 档·选中态）+ ETA + 主按钮（付费二次确认含扑空行）+ 结果红字。[onDepart] 由调用方在协程里转调 depart·
 * 结果经 [result] 回灌。reduce 仅淡入。
 */
@Composable
internal fun WorldTravelSheet(
    quote: TravelQuote?,
    result: TravelSheetResult?,
    reduceMotion: Boolean,
    nowMs: Long,
    zoneId: String?,
    onDepart: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var lastQuote by remember { mutableStateOf<TravelQuote?>(null) }
    if (quote != null) lastQuote = quote
    AnimatedVisibility(
        visible = quote != null,
        modifier = modifier,
        enter = if (reduceMotion) fadeIn(tween(150, easing = AppMotion.EaseInOut)) else slideInVertically(tween(320, easing = SheetEasing), initialOffsetY = { (it * 1.3f).toInt() }),
        exit = if (reduceMotion) fadeOut(tween(150, easing = AppMotion.EaseInOut)) else slideOutVertically(tween(320, easing = SheetEasing), targetOffsetY = { (it * 1.3f).toInt() }),
    ) {
        lastQuote?.let { TravelCard(it, result, nowMs, zoneId, onDepart, onClose) }
    }
}

@Composable
private fun TravelCard(quote: TravelQuote, result: TravelSheetResult?, nowMs: Long, zoneId: String?, onDepart: (String) -> Unit, onClose: () -> Unit) {
    var selected by remember(quote.destCityId) { mutableStateOf(0) }
    var confirmMode by remember { mutableStateOf<String?>(null) }
    val opt = quote.options.getOrElse(selected) { quote.options.first() }
    val zone = WorldClock.resolveZone(zoneId)

    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(WorldSceneColors.sheetSurface),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(stringResource(R.string.world_travel_sheet_title, quote.destName), color = WorldSceneColors.sheetTitle, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.world_travel_sheet_sub, quote.distanceLi, quote.fromName), color = WorldSceneColors.sheetBody, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            quote.options.forEachIndexed { i, o ->
                OptionRow(o, selected = i == selected, onClick = { selected = i }, modifier = Modifier.padding(top = 8.dp))
            }
            Text(stringResource(R.string.world_travel_eta, hhmm(nowMs + opt.durationMs, zone)), color = WorldSceneColors.sheetClose, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            when (result) {
                is TravelSheetResult.InsufficientGold -> Text(stringResource(R.string.world_travel_insufficient, result.need, result.have), color = Color(0xFFB3574A), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                TravelSheetResult.Failed -> Text(stringResource(R.string.world_travel_failed), color = Color(0xFFB3574A), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                null -> Unit
            }
            DepartButton(opt.costGold, onClick = { if (opt.costGold > 0) confirmMode = opt.mode else onDepart(opt.mode) }, modifier = Modifier.padding(top = 10.dp))
        }
        Box(Modifier.align(Alignment.TopEnd).sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickableScale(role = Role.Button, onClick = onClose)) {
            Text("✕", color = WorldSceneColors.sheetClose, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
        }
    }

    confirmMode?.let { mode ->
        val cost = quote.options.first { it.mode == mode }.costGold
        val dur = quote.options.first { it.mode == mode }.durationMs
        AppDialog(
            onDismissRequest = { confirmMode = null },
            title = stringResource(R.string.world_travel_confirm_title),
            confirmText = stringResource(R.string.world_travel_confirm_yes),
            onConfirm = { confirmMode = null; onDepart(mode) },
            dismissText = stringResource(R.string.world_travel_confirm_no),
            onDismiss = { confirmMode = null },
            content = {
                Column {
                    Text(stringResource(R.string.world_travel_confirm_body, cost, quote.destName, hhmm(nowMs + dur, zone)))
                    quote.visitorName?.let { Text(stringResource(R.string.world_travel_confirm_visitor, it, quote.fromName), modifier = Modifier.padding(top = 8.dp)) }
                }
            },
        )
    }
}

@Composable
private fun OptionRow(o: TravelOption, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val label = stringResource(R.string.world_travel_option, modeWord(o.mode), durationWord(o.durationMs), costWord(o.costGold))
    Box(
        modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 48.dp)
            .clip(AppShapes.full)
            .background(if (selected) Color(0x1FC99A86) else Color.Transparent)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) WorldSceneColors.ghostStroke else Color(0x406B6258), AppShapes.full)
            .clickableScale(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, color = WorldSceneColors.sheetTitle, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }
}

@Composable
private fun DepartButton(cost: Int, onClick: () -> Unit, modifier: Modifier) {
    val label = if (cost > 0) stringResource(R.string.world_travel_go_cost, cost) else stringResource(R.string.world_travel_go)
    Box(modifier.sizeIn(minHeight = 48.dp).clickableScale(role = Role.Button, onClick = onClick), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.clip(AppShapes.full).background(Brush.linearGradient(listOf(Color(0xFFC99A86), Color(0xFFBE8A76))))) {
            Box(Modifier.matchParentSize().background(Brush.linearGradient(0f to Color.White.copy(alpha = 0.28f), 0.42f to Color.Transparent)))
            Text(label, color = WorldSceneColors.sheetTitle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        }
    }
}

/** 在途 chip（§4.8·WorldScreen 顶部中央·出现/消失 300ms·常驻至到达）。 */
@Composable
internal fun WorldTravelChip(destName: String, arriveAtMs: Long, zoneId: String?, modifier: Modifier = Modifier) {
    val zone = WorldClock.resolveZone(zoneId)
    WorldGlassChip(modifier = modifier) {
        Text(stringResource(R.string.world_travel_chip, destName, hhmm(arriveAtMs, zone)), color = WorldSceneColors.onGlass, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

@Composable
private fun modeWord(mode: String): String = stringResource(
    when (mode) {
        "walk" -> R.string.world_travel_mode_walk
        "bike" -> R.string.world_travel_mode_bike
        "car" -> R.string.world_travel_mode_car
        "train" -> R.string.world_travel_mode_train
        else -> R.string.world_travel_mode_plane
    },
)

@Composable
private fun durationWord(durationMs: Long): String {
    val totalMin = (durationMs / 60_000L).toInt()
    return when {
        totalMin < 60 -> stringResource(R.string.world_travel_minutes, totalMin)
        totalMin % 60 == 0 -> stringResource(R.string.world_travel_hours, totalMin / 60)
        else -> stringResource(R.string.world_travel_hours_minutes, totalMin / 60, totalMin % 60)
    }
}

@Composable
private fun costWord(cost: Int): String =
    if (cost == 0) stringResource(R.string.world_travel_free) else stringResource(R.string.world_travel_cost, cost)

private fun hhmm(atMs: Long, zone: java.time.ZoneId): String {
    val t = Instant.ofEpochMilli(atMs).atZone(zone).toLocalTime()
    return "%02d:%02d".format(t.hour, t.minute)
}
