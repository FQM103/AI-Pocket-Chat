package com.situ.aichat.ui.world.onboarding

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.settings.WORLD_TIMEZONES
import com.situ.aichat.ui.settings.WorldTimezoneSheet
import com.situ.aichat.ui.settings.gmtOffsetShort
import com.situ.aichat.ui.world.WorldScene
import com.situ.aichat.ui.world.WorldSceneColors

// 底卡暖纸家族（9d 站点卡同族·图纸 §4.5 锁死值）。head=sheetTitle / body=sheetBody / skip=sheetClose 复用现有常量。
private val CardPaper = Color(0xF2FAF7F2)
private val CardHead = Color(0xFF2E2925)
private val CardBody = Color(0xFF6B6258)
private val CardSkip = Color(0xFF9C938A)
private val CardTzBar = Color(0xFFF1ECE4)
private val CardTzChange = Color(0xFF9A5B3E)
private val PillStart = Color(0xFFC99A86)
private val PillEnd = Color(0xFFBE8A76)

/**
 * 首启轻三步覆盖层（W13 图纸 §4.5）：世界屏最顶层·无额外遮罩（世界本体即背景）。步点 + 标题块 + 暖纸底卡
 * （步一含时区栏）；第 3 步 finish 后转为 session 态场景感知 hint chip（快聊开或离屏即消）。
 */
@Composable
fun WorldOnboardingOverlay(
    scene: WorldScene,
    quickChatOpen: Boolean,
    viewModel: WorldOnboardingViewModel = hiltViewModel(),
) {
    val visible by viewModel.visible.collectAsStateWithLifecycle()
    val step by viewModel.step.collectAsStateWithLifecycle()
    val pinnedZone by viewModel.pinnedZoneId.collectAsStateWithLifecycle()
    val reduceMotion = rememberReduceMotion()
    // 第 3 步「开始逛逛」后本 session 展示 hint chip（不持久化·离屏重组即复位·图纸 §3.5）。
    var showHint by remember { mutableStateOf(false) }
    var showTzSheet by remember { mutableStateOf(false) }
    // hint 闩锁（复核 R1 🔵-2）：首次坐下开快聊即永久收起——功成身退，关快聊后不再复显。
    var hintDismissed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(quickChatOpen) { if (quickChatOpen) hintDismissed = true }

    Box(Modifier.fillMaxSize()) {
        if (visible) {
            StepDots(
                step = step,
                reduceMotion = reduceMotion,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp),
            )
            Column(
                // top=120dp 让过返回圆钮与「云野镇」玻璃 chip·避免标题块压 chrome（复核 R1 🔵-1·§4.5 随修订）。
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 120.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(stepTitle(step)), fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = WorldSceneColors.onGlass)
                Text(stringResource(stepSub(step)), fontSize = 12.sp, color = WorldSceneColors.onGlass.copy(alpha = 0.72f))
            }
            OnboardingCard(
                step = step,
                deviceOffset = gmtOffsetShort(viewModel.deviceZoneId),
                deviceCity = deviceCityName(viewModel.deviceZoneId),
                reduceMotion = reduceMotion,
                onChangeTz = { showTzSheet = true },
                onPrimary = {
                    if (step == 3) {
                        showHint = true
                        viewModel.finish()
                    } else {
                        viewModel.primaryAction()
                    }
                },
                onSkip = { viewModel.skip() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
            )
        }
        // hint chip（三步走完·本 session·非快聊态·未闩锁）。
        if (showHint && !visible && !quickChatOpen && !hintDismissed) {
            HintChip(
                text = stringResource(sceneHint(scene)),
                // bottom=64dp 让位小镇层自带底部提示胶囊（同 18dp 位·避免叠字·复核 R1 🟡-3·§4.5 随修订）。
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 64.dp),
            )
        }
    }

    if (showTzSheet) {
        WorldTimezoneSheet(
            currentZoneId = pinnedZone,
            onPick = {
                viewModel.pickZone(it)
                showTzSheet = false
            },
            onDismiss = { showTzSheet = false },
        )
    }
}

/** 三点步进条：当前=16×6dp 圆角 3dp gold（尺寸变化效果轴 tween 200ms）；其余=6dp onGlass 0.35。 */
@Composable
private fun StepDots(step: Int, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        for (i in 1..3) {
            val active = i == step
            val width by animateDpAsState(
                targetValue = if (active) 16.dp else 6.dp,
                animationSpec = if (reduceMotion) snap() else tween(200),
                label = "obDot$i",
            )
            Box(
                Modifier
                    .height(6.dp)
                    .width(width)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (active) WorldSceneColors.gold else WorldSceneColors.onGlass.copy(alpha = 0.35f)),
            )
        }
    }
}

/** 暖纸底卡：头 16.5sp + 正文 13sp/行高 1.85em + 步一时区栏 + 按钮排；内容随步 Crossfade。 */
@Composable
private fun OnboardingCard(
    step: Int,
    deviceOffset: String,
    deviceCity: String,
    reduceMotion: Boolean,
    onChangeTz: () -> Unit,
    onPrimary: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(CardPaper)
            .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 14.dp),
    ) {
        Crossfade(
            targetState = step,
            animationSpec = if (reduceMotion) snap() else tween(AppMotion.SMOOTH_MS),
            label = "obCard",
        ) { s ->
            Column {
                Text(stringResource(stepHead(s)), fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold, color = CardHead)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(stepBody(s)), fontSize = 13.sp, lineHeight = (13 * 1.85).sp, color = CardBody)
                if (s == 1) {
                    Spacer(Modifier.height(12.dp))
                    TzBar(offset = deviceOffset, city = deviceCity, onChange = onChangeTz)
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.clip(AppShapes.small).clickable(onClick = onSkip).heightIn(min = 48.dp).padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.world_ob_skip), fontSize = 12.sp, color = CardSkip)
                    }
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .clickableScale(pressedScale = 0.96f, onClick = onPrimary)
                            .clip(AppShapes.full)
                            .background(Brush.linearGradient(listOf(PillStart, PillEnd)))
                            .padding(horizontal = 22.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(stepGo(s)), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = CardHead)
                    }
                }
            }
        }
    }
}

/** 步一时区栏（左「GMT+8 北京 · 来自设备」·右「改一下」48dp 触达）。 */
@Composable
private fun TzBar(offset: String, city: String, onChange: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardTzBar).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.world_ob1_field, offset, city), fontSize = 14.sp, color = CardHead, modifier = Modifier.weight(1f))
        Box(
            Modifier.clip(AppShapes.small).clickable(onClick = onChange).heightIn(min = 48.dp).padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.world_ob1_change), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = CardTzChange)
        }
    }
}

/** 场景感知 hint chip（静态玻璃胶囊·WorldChrome chip 家族同值）。 */
@Composable
private fun HintChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(AppShapes.full)
            .background(WorldSceneColors.glassChip)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = WorldSceneColors.onGlass, fontSize = 12.sp)
    }
}

/** 设备时区 → 展示名（命中 8 常用区表用其中文名·否则原 zoneId）。 */
@Composable
private fun deviceCityName(zoneId: String): String =
    WORLD_TIMEZONES.firstOrNull { it.zoneId == zoneId }?.let { stringResource(it.nameRes) } ?: zoneId

private fun stepTitle(s: Int) = when (s) {
    1 -> R.string.world_ob1_title
    2 -> R.string.world_ob2_title
    else -> R.string.world_ob3_title
}

private fun stepSub(s: Int) = when (s) {
    1 -> R.string.world_ob1_sub
    2 -> R.string.world_ob2_sub
    else -> R.string.world_ob3_sub
}

private fun stepHead(s: Int) = when (s) {
    1 -> R.string.world_ob1_head
    2 -> R.string.world_ob2_head
    else -> R.string.world_ob3_head
}

private fun stepBody(s: Int) = when (s) {
    1 -> R.string.world_ob1_body
    2 -> R.string.world_ob2_body
    else -> R.string.world_ob3_body
}

private fun stepGo(s: Int) = when (s) {
    1 -> R.string.world_ob1_go
    2 -> R.string.world_ob2_go
    else -> R.string.world_ob3_go
}

/** scene → hint（Planet/StarMap→星球·Continent→大陆·Town/Interior→小镇·图纸 §3.5）。 */
private fun sceneHint(scene: WorldScene) = when (scene) {
    is WorldScene.Continent -> R.string.world_ob_hint_continent
    is WorldScene.Town, is WorldScene.Interior -> R.string.world_ob_hint_town
    else -> R.string.world_ob_hint_planet
}
