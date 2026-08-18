package com.situ.aichat.ui.voicecall

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.diagnostics.perf.FrameSceneObserver
import com.situ.aichat.diagnostics.perf.PerfScenes
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.components.rememberTouchExploration
import com.situ.aichat.voice.CallState
import com.situ.aichat.voice.VoiceCallError
import kotlinx.coroutines.delay

/**
 * The full-screen voice-call UI — 「暖夜通话」重设计（FABLE5_VOICE_CALL_REDESIGN_PROPOSAL.md·已过审）。
 * It only *observes + controls* a call that the foreground `VoiceCallService` already started (locked
 * decision #3); it does not own the call lifecycle, so navigating away leaves the call running (the
 * CallStyle notification brings the user back). Keeps the screen awake, forwards app background/foreground
 * to the controller, and dismisses 0.8 s after the call ends.
 *
 * 布局 = 单一自适应列（D-6）：SpaceBetween 三块（头像信息 / 字幕波形 / 控制排）+ `heightIn(min=可用高)`
 * 的可滚动列——高屏三块自然铺开、矮屏（横屏/分屏）自动可滚，不再维护两套分支。
 */
@Composable
fun VoiceCallScreen(
    onCallFinished: () -> Unit,
    viewModel: VoiceCallViewModel = hiltViewModel(),
) {
    // 性能采集·尺 3（卷 0）：本屏在被观测名单里（M23 同时长通话对照）。采集关时零成本。
    FrameSceneObserver(PerfScenes.VOICE_CALL)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val audioLevel by viewModel.audioLevel.collectAsStateWithLifecycle()
    val isSpeaker by viewModel.isSpeakerEnabled.collectAsStateWithLifecycle()
    val durationSeconds by viewModel.callDurationSeconds.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val characterName by viewModel.characterName.collectAsStateWithLifecycle()
    val avatar by viewModel.avatar.collectAsStateWithLifecycle()
    val wallpaper by viewModel.wallpaper.collectAsStateWithLifecycle()
    val subtitles by viewModel.subtitles.collectAsStateWithLifecycle()
    val ttsTurnFailures by viewModel.ttsTurnFailures.collectAsStateWithLifecycle()
    val subtitleFallbackActive by viewModel.subtitleFallbackActive.collectAsStateWithLifecycle()

    // O-2 拍板：字幕默认开。
    var subtitleVisible by rememberSaveable { mutableStateOf(true) }

    // VU2 失声两级表达（2026-07-12·屏级派生·J4 rememberSaveable 抗转屏/进程死亡恢复不丢闩锁与计数）：
    // 失声那一轮结束的瞬间出瞬时琥珀短句（约 4s 淡出·每通最多 2 次），首次失声若字幕关着则自动展开一次。
    var lastSeenFailures by rememberSaveable { mutableIntStateOf(0) }
    var hintShownCount by rememberSaveable { mutableIntStateOf(0) }
    var autoExpandDone by rememberSaveable { mutableStateOf(false) }
    var hintVisible by remember { mutableStateOf(false) }
    LaunchedEffect(ttsTurnFailures) {
        if (ttsTurnFailures > lastSeenFailures) {
            // ② 字幕当前关 且 从未自动展开过 → 自动展开一次（O-B：此后用户手动关即用户赢，不再自动弹）。
            if (!subtitleVisible && !autoExpandDone) subtitleVisible = true
            // ③ 首次失声即置闩锁。
            autoExpandDone = true
            // ④ 记住已处理到的失声计数（转屏/重建不重复弹）。
            lastSeenFailures = ttsTurnFailures
            // ① 瞬时琥珀短句每通最多 2 次；delay 置于末尾使 ②③④ 立即生效不被阻塞（新失声重启本 effect 自然重置计时）。
            if (hintShownCount < 2) {
                hintVisible = true
                hintShownCount++
                delay(HINT_VISIBLE_MS)
                hintVisible = false
            } else {
                // R1 🟡-1：次数用尽后的新失声会取消上一条 hint 尚未走完的 delay——不补这行则 hintVisible 永卡 true。
                hintVisible = false
            }
        }
    }

    // T5 复核 Y-1：正常拨号入场存在「屏已组合、服务尚未 startCall」的短暂 IDLE 窗口——该窗口显示
    // 「通话结束」会闪现误导，且挂断键若直接退屏会孤儿化随后拉起的通话。IDLE 持续超 [IDLE_SETTLE_MS]
    // 才视为真·僵尸态（显示已结束 + 挂断可退）；窗口内状态行留空、挂断 no-op（均=旧行为）。
    var idleSettled by remember { mutableStateOf(false) }

    // Keep the screen awake for the duration of the call (= iOS IdleTimerManager).
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // App background/foreground → controller pause/resume (= iOS handleAppDidEnterBackground / WillEnterForeground).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
                Lifecycle.Event.ON_START -> viewModel.onAppForegrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Dismiss 0.8 s after the call ends (= iOS onChange ending → 0.8 s dismiss).
    // E1#1：进程死亡后从最近任务回到已恢复的通话屏 = 「僵尸通话屏」——VM 不在此拉起通话（locked decision #3：
    // VoiceCallScreen 只观察、不 startCall），state 恒 IDLE。D-6：IDLE 即时显示「已结束」+ 挂断/返回立即可退，
    // 兜底 [IDLE_TIMEOUT_MS] 自动收场。豁免正常入场竞态：真实通话由服务在导航前/后即刻拉起，~1.5s 内必转出
    // IDLE→DIALING，本 effect 随 state 变化被取消，绝不误退正在拨号的通话。
    LaunchedEffect(state) {
        when (state) {
            CallState.ENDING -> {
                delay(ENDING_DISMISS_MS)
                onCallFinished()
            }
            CallState.IDLE -> {
                delay(IDLE_SETTLE_MS)
                idleSettled = true
                delay(IDLE_TIMEOUT_MS - IDLE_SETTLE_MS)
                onCallFinished()
            }
            else -> idleSettled = false
        }
    }

    // Back = hang up the call (= iOS dismissing the call view → endCall), so leaving never orphans a call.
    BackHandler {
        if (state == CallState.IDLE || state == CallState.ENDING) onCallFinished() else viewModel.hangUp()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        VoiceCallBackground(wallpaper = wallpaper, avatar = avatar)

        BoxWithConstraints(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            val minHeight = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = minHeight)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                CallHeaderBlock(
                    avatar = avatar,
                    state = state,
                    idleSettled = idleSettled,
                    audioLevel = audioLevel,
                    characterName = characterName,
                    durationSeconds = durationSeconds,
                    error = error,
                    hintVisible = hintVisible,
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (subtitleVisible) {
                        VoiceCallSubtitlePanel(
                            lines = subtitles,
                            modifier = Modifier.fillMaxWidth(),
                            fallbackMode = subtitleFallbackActive,
                        )
                        Spacer(Modifier.height(22.dp))
                    }
                    VoiceCallWaveform(
                        audioLevel = audioLevel,
                        state = state,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(88.dp)
                            .padding(horizontal = 36.dp),
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(20.dp))
                    VoiceCallControls(
                        isSpeakerEnabled = isSpeaker,
                        isSubtitleVisible = subtitleVisible,
                        onToggleSubtitle = { subtitleVisible = !subtitleVisible },
                        onHangUp = {
                            // D-6：僵尸/已结束态下挂断键 = 直接返回；入场 IDLE 窗口（未 settle）保持
                            // no-op（Y-1：此时服务正在拉起通话，退屏会孤儿化它）。
                            when {
                                state == CallState.ENDING || (state == CallState.IDLE && idleSettled) ->
                                    onCallFinished()
                                state == CallState.IDLE -> Unit
                                else -> viewModel.hangUp()
                            }
                        },
                        onToggleSpeaker = viewModel::toggleSpeaker,
                        subtitleBadge = subtitleFallbackActive,
                    )
                    Spacer(Modifier.height(36.dp))
                }
            }
        }
    }
}

/** 顶块：头像 + 名字 + 状态行（一点一淡入）+ 计时 + 错误短句。 */
@Composable
private fun CallHeaderBlock(
    avatar: android.graphics.Bitmap?,
    state: CallState,
    idleSettled: Boolean,
    audioLevel: Float,
    characterName: String,
    durationSeconds: Long,
    error: VoiceCallError?,
    hintVisible: Boolean,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(28.dp))
        VoiceCallAvatar(avatar = avatar, state = state, audioLevel = audioLevel)
        Spacer(Modifier.height(18.dp))

        Text(
            text = characterName,
            color = VoiceCallPalette.textHi,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleLarge,
        )

        Spacer(Modifier.height(8.dp))
        CallStatusLine(state = state, idleSettled = idleSettled)

        Spacer(Modifier.height(7.dp))
        // P1-17：计时器绝不加 liveRegion（防每秒播报）；cd=分秒读法（仅焦点驻留时读到·超越）。
        // 批6 复核修 #4：cd 用 10 秒粒度与显示解耦——10s 桶内串不变即不发事件（TalkBack 官方节流幅度）。
        val cdSeconds = durationSeconds - durationSeconds % 10
        val durationA11y = stringResource(R.string.a11y_call_duration, cdSeconds / 60, cdSeconds % 60)
        Text(
            text = voiceCallDurationText(durationSeconds),
            color = VoiceCallPalette.textMid,
            // 数字等宽 tnum（设计语言 §2·通话计时点名项），替换旧 Monospace 整族换字。
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            modifier = Modifier.semantics { contentDescription = durationA11y },
        )

        // VU2 §4.2.1：真错误恒压过失声短句（B6·优先级不变）；无错误时若 hintVisible 则同槽同款式显示
        // 瞬时琥珀短句「先看字幕吧」。出现/消失由状态切换驱动，沿用该槽既有的条件渲染（不加额外淡入淡出）。
        val statusMessage = errorText(error) ?: if (hintVisible) stringResource(R.string.voice_call_tts_hint) else null
        statusMessage?.let { message ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = message,
                color = VoiceCallPalette.amber,
                style = MaterialTheme.typography.bodySmall,
                // P1-17：错误出现即播一次（= iOS alert 被 VoiceOver 自动播报的等价）。
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * 状态行（D-2「一点」）：脉冲小圆点 + 状态文案交叉淡入淡出（原先硬跳）。
 * P1-17 语义/视觉拆轨保留：DIALING 视觉文本每 360ms 变点点，liveRegion 用不带点的稳定文案盖住，
 * 播报只随 CallState 切换触发一次。crossfade 按稳定文案 key（点点变化不重放动画）；RM=snap 瞬切。
 * T5 复核 Y-2：语义挂在 Crossfade **外**的包裹 Box 上（单一停靠点·节点身份稳定），淡入淡出的
 * 视觉内容整体 clearAndSetSemantics 压停——避免屏读出现两个近同内容停靠点/交叉期双播报。
 */
@Composable
private fun CallStatusLine(state: CallState, idleSettled: Boolean) {
    val reduceMotion = rememberReduceMotion()
    val statusBase = callStatusBase(state, idleSettled)

    Box(
        modifier = Modifier.semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
            contentDescription = statusBase
        },
    ) {
        Crossfade(
            targetState = statusBase,
            animationSpec = if (reduceMotion) snap() else tween(durationMillis = 220),
            label = "callStatusCrossfade",
            modifier = Modifier.clearAndSetSemantics {},
        ) { base ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
            if (base.isNotEmpty() && state != CallState.ENDING && state != CallState.IDLE) {
                val dotAlpha = if (reduceMotion) {
                    1f
                } else {
                    rememberInfiniteTransition(label = "statusDot").animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 700),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "statusDot-alpha",
                    ).value
                }
                val dotColor =
                    if (state == CallState.PROCESSING) VoiceCallPalette.textMid else VoiceCallPalette.glow
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(dotColor.copy(alpha = dotAlpha), CircleShape),
                )
            }
            val visual = if (state == CallState.DIALING && base == callStatusBase(CallState.DIALING, idleSettled)) {
                base + dialingDots()
            } else {
                base
            }
            Text(
                text = visual,
                color = VoiceCallPalette.textMid,
                style = MaterialTheme.typography.bodyMedium,
            )
            }
        }
    }
}

/**
 * Status line per call state (= iOS `statusText`)；稳定文案不带拨号点点（语义层直用，视觉层 DIALING 再拼
 * [dialingDots]）。D-6：僵尸态（IDLE 持续超 [IDLE_SETTLE_MS]）显示「已结束」；入场 IDLE 窗口留空（Y-1）。
 */
@Composable
private fun callStatusBase(state: CallState, idleSettled: Boolean): String = when (state) {
    CallState.IDLE -> if (idleSettled) stringResource(R.string.voice_call_status_ended) else ""
    CallState.DIALING -> stringResource(R.string.voice_call_status_connecting)
    CallState.LISTENING -> stringResource(R.string.voice_call_status_listening)
    CallState.USER_SPEAKING -> stringResource(R.string.voice_call_status_user_speaking)
    CallState.PROCESSING -> stringResource(R.string.voice_call_status_thinking)
    CallState.AI_SPEAKING -> stringResource(R.string.voice_call_status_ai_speaking)
    CallState.ENDING -> stringResource(R.string.voice_call_status_ended)
}

/** 1→3 trailing dots cycling every 360 ms (= iOS DialingDotsText). */
@Composable
private fun dialingDots(): String {
    // P1-23：RM 静止=三点全显；批6 复核修 #3[MED]：屏读激活时同样冻结（点点 tick 会让 TalkBack 反复重读）。
    if (rememberReduceMotion() || rememberTouchExploration()) return "..."
    var phase by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(360)
            phase = (phase + 1) % 3
        }
    }
    return ".".repeat(phase + 1)
}

@Composable
private fun errorText(error: VoiceCallError?): String? = when (error) {
    VoiceCallError.MIC_UNAVAILABLE -> stringResource(R.string.voice_call_permission_needed)
    VoiceCallError.STT_UNAVAILABLE -> stringResource(R.string.voice_call_stt_unavailable) // U6：端侧识别模型加载失败
    // INTERRUPTED / RESUME_FAILED are transient (the controller recovers or ends the call) → no banner.
    else -> null
}

private const val ENDING_DISMISS_MS = 800L

/** VU2 §4.2.1：失声瞬时琥珀短句停留时长（§9 锁定 4000ms），到时淡出（新失声重启计时）。 */
private const val HINT_VISIBLE_MS = 4000L

/** E1#1：僵尸通话屏自动收场阈值——组合后持续 IDLE 超此即退场。1.5s 覆盖正常入场的服务启动→DIALING 竞态。 */
private const val IDLE_TIMEOUT_MS = 1500L

/** Y-1：IDLE 持续超此才认定僵尸态（显示「通话结束」+挂断可退）；之前视为入场竞态窗口（留空+no-op）。 */
private const val IDLE_SETTLE_MS = 400L
