package com.situ.aichat.ui.world.starmap

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.world.WorldSceneColors

/**
 * 列表模式全屏覆盖（W10 图纸 §4.7 / §18·屏读替代）：三节（在你身边·按亲疏 / TA 们之间·按簇序 / 待相识）+ 尾注。
 * 每行合并语义（contentDescription 自然连读）令 TalkBack 逐行可读——星图上的每颗星、每条线在此都读得到。
 */
@Composable
internal fun BoxScope.StarmapListMode(graph: StarmapGraph?, visible: Boolean, reduceMotion: Boolean) {
    AnimatedVisibility(
        visible = visible && graph != null,
        modifier = Modifier.align(Alignment.Center),
        enter = fadeIn(tween(if (reduceMotion) 0 else 180, easing = AppMotion.EaseInOut)),
        exit = fadeOut(tween(if (reduceMotion) 0 else 150, easing = AppMotion.EaseInOut)),
    ) {
        if (graph == null) return@AnimatedVisibility
        // K6（2026-07-12 性能线程专项）：排序与簇序索引只在 graph 变化时算一次，不随覆盖层重组白算。
        val sortedNodes = remember(graph) { graph.nodes.sortedByDescending { it.closeness } }
        val sortedEdges = remember(graph) {
            val nodeIndex = graph.nodes.mapIndexed { i, n -> n.characterUuid to i }.toMap()
            graph.edges.sortedWith(compareBy({ nodeIndex[it.aUuid] ?: 0 }, { nodeIndex[it.bUuid] ?: 0 }))
        }
        LazyColumn(
            Modifier.fillMaxSize().background(WorldSceneColors.smListOverlay).statusBarsPadding().padding(top = 72.dp, start = 20.dp, end = 20.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { SectionHeader(stringResource(R.string.world_starmap_sec_around)) }
            items(sortedNodes, key = { "n_${it.characterUuid}" }) { AroundRow(it) }
            item { SectionHeader(stringResource(R.string.world_starmap_sec_between)) }
            items(sortedEdges, key = { "e_${it.pairKey}" }) { BetweenRow(it) }
            item { SectionHeader(stringResource(R.string.world_starmap_sec_pending)) }
            items(graph.pendings, key = { "p_${it.nativeId}" }) { PendingRow(it) }
            item {
                Text(
                    stringResource(R.string.world_starmap_list_foot),
                    color = WorldSceneColors.onGlass.copy(alpha = 0.4f), fontSize = 11.sp, lineHeight = (11 * 1.8f).sp,
                    modifier = Modifier.widthIn(max = 430.dp).fillMaxWidth().padding(top = 26.dp),
                )
            }
        }
    }
}

private fun rowMod(): Modifier = Modifier.widthIn(max = 430.dp).fillMaxWidth()

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        color = WorldSceneColors.onGlass.copy(alpha = 0.55f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.14.em,
        modifier = rowMod().padding(top = 22.dp, bottom = 6.dp),
    )
}

/** 在你身边行（30dp 头像 + 名 + 副标 ↵ youTier·底分隔 onGlass .08）。 */
@Composable
private fun AroundRow(node: StarNode) {
    val youTier = stringResource(StarmapStrings.youTierResId(closenessTier(node.closeness)))
    Row(
        rowMod().semantics(mergeDescendants = true) {}
            .drawBehind {
                drawLine(WorldSceneColors.onGlass.copy(alpha = 0.08f), androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height), 1.dp.toPx())
            }
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).border(2.dp, WorldSceneColors.cardStroke, CircleShape)) { CharacterAvatar(node.name, node.avatarPath, 30.dp) }
        Column(Modifier.padding(start = 11.dp)) {
            Text(node.name, color = WorldSceneColors.onGlass, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(node.subtitle, color = WorldSceneColors.onGlass.copy(alpha = 0.62f), fontSize = 12.sp, lineHeight = (12 * 1.5f).sp)
            Text(youTier, color = WorldSceneColors.onGlass.copy(alpha = 0.62f), fontSize = 12.sp, lineHeight = (12 * 1.5f).sp)
        }
    }
}

/** TA 们之间行（双头像叠放 + A ✕ B + types/近事 + 右缘轨迹/心结）。 */
@Composable
private fun BetweenRow(edge: StarEdge) {
    val traj = stringResource(StarmapStrings.trajectoryResId(edge.trajectory)) + (if (edge.tension >= 40) " · " + stringResource(R.string.world_starmap_knot) else "")
    Row(rowMod().semantics(mergeDescendants = true) {}.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 46.dp, height = 20.dp)) {
            CharacterAvatar(edge.aName, edge.aAvatarPath, 20.dp)
            CharacterAvatar(edge.bName, edge.bAvatarPath, 20.dp, Modifier.offset(x = 14.dp))
        }
        Column(Modifier.padding(start = 11.dp).weight(1f)) {
            Text("${edge.aName} ✕ ${edge.bName}", color = WorldSceneColors.onGlass, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(edge.types.joinToString("·"), color = WorldSceneColors.onGlass.copy(alpha = 0.62f), fontSize = 12.sp)
            edge.recent?.let { Text("${stringResource(StarmapStrings.relativeDayResId(it.relativeDay))}：${it.summary}", color = WorldSceneColors.onGlass.copy(alpha = 0.62f), fontSize = 12.sp) }
        }
        Text(traj, color = WorldSceneColors.onGlass.copy(alpha = 0.45f), fontSize = 11.sp)
    }
}

/** 待相识行（28dp 虚圈首字 + 名 + 职业·城 ↵ 朦胧短语）。 */
@Composable
private fun PendingRow(pending: PendingStar) {
    Row(rowMod().semantics(mergeDescendants = true) {}.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(WorldSceneColors.mystery.copy(alpha = 0.85f))
                .drawBehind { drawCircle(WorldSceneColors.cardStroke, size.minDimension / 2f - 1.dp.toPx(), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))) },
            contentAlignment = Alignment.Center,
        ) {
            Text(pending.name.take(1), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.padding(start = 11.dp)) {
            Text(pending.name, color = WorldSceneColors.onGlass, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("${pending.occupation} · ${pending.cityName}", color = WorldSceneColors.onGlass.copy(alpha = 0.62f), fontSize = 12.sp, lineHeight = (12 * 1.5f).sp)
            Text(pending.stagePhrase, color = WorldSceneColors.onGlass.copy(alpha = 0.62f), fontSize = 12.sp, lineHeight = (12 * 1.5f).sp)
        }
    }
}
