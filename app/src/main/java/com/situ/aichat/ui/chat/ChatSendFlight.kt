package com.situ.aichat.ui.chat

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind

/**
 * ④ 发送飞入·握手与降级链（契约 FABLE5_CHAT_TELEGRAM_MOTION_PROPOSAL.md §4·M3a·拍板 D6/D7/D8）。
 *
 * **押后清空握手（无缝感灵魂·Telegram ChatActivityEnterView:7132-7143）**：点发送后输入框文字**不立即清**，
 * 记入 [pending]；新用户气泡在列表首次布局就位的那一帧（[resolveByTarget]）「清空+起飞」同帧发生；
 * [HANDSHAKE_TIMEOUT_MS] 内没就位（卡顿）→ [resolveByTimeout] 兜底照常清空、动画降级——语义与现状一致。
 * 发送失败（send 返回 false）根本不进握手，输入不清空的现有行为零变化。
 *
 * **降级链（任一闸不过=静默走现有入场动画）**：总闸 [SEND_FLIGHT_ENABLED]（M3a 恒关=装机与现状逐帧
 * 无差、可独立验证；M3b 开闸）/ reduceMotion / 列表不在底部（复用回底 FAB 口径）/ 引用回复面板开着
 * （D8 首版降级）/ 握手或飞行进行中再发（D6 连发第二条降级）。>10 行闸需目标排版、在 M3b 起飞时判。
 */

/** 握手超时（Telegram moveToSendStateRunnable 押后 200ms·CAEV:7132）。 */
internal const val HANDSHAKE_TIMEOUT_MS = 200L

/** 飞行总闸（M3a 关闸落地基；**M3b 开闸**·关回它即整体退回现状=最后一道降级阀）。 */
internal const val SEND_FLIGHT_ENABLED = true

/** 超行数降级阈值（Telegram TextMessageEnterTransition:128·M3b 在目标排版就绪时判）。 */
internal const val FLIGHT_MAX_LINES = 10

/** 飞行总时长（Telegram ChatListItemAnimator.DEFAULT_DURATION=250ms·单一线性进度派生三曲线）。 */
internal const val FLIGHT_MS = 250

/** 淡入淡出统一斜坡（Telegram alphaProgress=min(t/0.4,1)·前 40%≈100ms 完成颜色/圆角/时间戳过渡）。 */
internal fun flightAlphaRamp(t: Float): Float = (t / 0.4f).coerceIn(0f, 1f)

/**
 * 飞行帧（纯函数·T1）：左右边挂快曲线进度 [xFraction]、上下边挂慢曲线进度 [yFraction]
 * （Telegram TMET:489-494 横竖分工——含尺寸的宽随横轴、高随纵轴各自变形）。
 */
internal fun flightFrame(start: Rect, target: Rect, xFraction: Float, yFraction: Float): Rect = Rect(
    left = start.left + (target.left - start.left) * xFraction,
    top = start.top + (target.top - start.top) * yFraction,
    right = start.right + (target.right - start.right) * xFraction,
    bottom = start.bottom + (target.bottom - start.bottom) * yFraction,
)

/** 一次进行中的飞行（就位帧起跳·目标边界随列表位移逐帧活取=移动靶）。 */
internal class ActiveSendFlight(
    val messageUuid: String,
    val text: String,
    val timestampMs: Long,
    val startBounds: Rect,
    initialTarget: Rect,
) {
    var targetBounds by mutableStateOf(initialTarget)
}

/**
 * 发送时刻的降级闸链（纯函数·T1）。全过才允许进握手；[flightBusy]=握手/飞行进行中（D6 连发第二条降级·
 * 250ms 窗口极小肉眼难辨·有意简化不排队，见契约 §4.4）。
 */
internal fun sendFlightGatesOpen(
    enabled: Boolean,
    reduceMotion: Boolean,
    listAtBottom: Boolean,
    quoteReplyActive: Boolean,
    flightBusy: Boolean,
): Boolean = enabled && !reduceMotion && listAtBottom && !quoteReplyActive && !flightBusy

/** 一次待起飞的握手（发送已受理·等新气泡就位或超时）。[commit] = 押后的「清空输入框」动作。 */
internal class PendingSendFlight(
    val text: String,
    val requestedAtMs: Long,
    internal val commit: () -> Unit,
) {
    /** 一条落库消息是否就是本次发送（uuid 未知·按「其后落库的同文用户纯文本」匹配）。 */
    fun matches(message: MessageEntity): Boolean =
        message.roleRaw == "user" &&
            MessageKind.fromRaw(message.messageKindRaw) == MessageKind.PLAIN_TEXT &&
            message.timestamp >= requestedAtMs &&
            message.content == text
}

/**
 * 握手状态机（可测纯逻辑·T2）：[tryBegin] 三种走向——闸开且空闲=进握手（清空押后）；否则立即 commit
 * （=现状乐观清空，含发送闸拒后重试路径）。决议幂等：目标就位 / 超时二者先到先得，commit 恰好一次。
 * M3b 在 [resolveByTarget] 上接飞行起跳（目标气泡窗口边界经参数透传）。
 */
@Stable
internal class ChatSendFlightState(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    var pending by mutableStateOf<PendingSendFlight?>(null)
        private set

    /** 进行中的飞行（M3b·覆盖层渲染·目标行同期抑制自绘）。 */
    var flight by mutableStateOf<ActiveSendFlight?>(null)
        private set

    /** 起飞回调（覆盖层经 DisposableEffect 赋值——超行数闸在其内判后调 [beginFlight]）。 */
    var onLaunch: ((message: MessageEntity, boundsInWindow: Rect, pending: PendingSendFlight) -> Unit)? = null

    /** 输入胶囊实时窗口边界（起点·ChatBottomBar 每布局帧写入；普通字段=无订阅开销）。 */
    var inputBounds: Rect = Rect.Zero

    val busy: Boolean get() = pending != null || flight != null

    val flightUuid: String? get() = flight?.messageUuid

    /** 目标气泡布局回调统一入口：握手中=就位决议（清空+起飞同帧）；飞行中=移动靶逐帧更新。 */
    fun onBubblePositioned(message: MessageEntity, boundsInWindow: Rect) {
        val p = pending
        if (p != null && p.matches(message)) {
            resolveByTarget(message, boundsInWindow)
            return
        }
        val f = flight
        if (f != null && f.messageUuid == message.messageUUID) f.targetBounds = boundsInWindow
    }

    /** 起飞（覆盖层过完超行数闸后调）：起点=当前输入胶囊边界。 */
    fun beginFlight(message: MessageEntity, targetBounds: Rect) {
        if (inputBounds == Rect.Zero) return // 起点未知（异常态）→ 降级不飞
        flight = ActiveSendFlight(message.messageUUID, message.content, message.timestamp, inputBounds, targetBounds)
    }

    fun endFlight() {
        flight = null
    }

    /**
     * 发送被 VM 受理后调用。[gatesOpen]=闸链判定结果；闸开且无进行中握手 → 押后 [commit] 等就位；
     * 否则立即 commit（降级=现状）。返回 true=已进握手。
     */
    fun tryBegin(text: String, gatesOpen: Boolean, commit: () -> Unit): Boolean {
        if (!gatesOpen || pending != null) {
            commit()
            return false
        }
        pending = PendingSendFlight(text, nowMs(), commit)
        return true
    }

    /** 目标气泡（该次发送落库的用户消息行）首次布局就位——「清空+起飞」同帧。 */
    fun resolveByTarget(message: MessageEntity, boundsInWindow: Rect) {
        val p = pending ?: return
        pending = null
        p.commit()
        onLaunch?.invoke(message, boundsInWindow, p)
    }

    /** 超时兜底（卡顿/列表没来得及插入）：照常清空、无动画（语义与现状一致）。 */
    fun resolveByTimeout() {
        val p = pending ?: return
        pending = null
        p.commit()
    }
}
