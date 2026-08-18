package com.situ.aichat.ui.components

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI

/**
 * 全局动效常量（P15.2-P1 批0 基建）：1:1 取自 iOS AppTheme.swift 的 `MessageSpring` 弹簧族与
 * `.smooth` 过渡时长，供消息入场（P1-13）、点赞弹跳（P1-10）、礼物揭晓（P1-11）、
 * 余额滚动（P1-12）等动效项共用，避免各处散落魔数。
 *
 * iOS `spring(duration:bounce:)` → Compose `spring(dampingRatio, stiffness)` 换算见
 * [iosSpringDampingRatio] / [iosSpringStiffness]：dampingRatio = 1 − bounce；
 * stiffness = (2π ÷ duration)²（质量 1 时角频率 ω = 2π/T 与刚度 k = ω² 的关系）。
 */
object AppMotion {

    /** iOS `MessageSpring.send` = spring(0.35s, bounce 0.12)：用户消息发送/滚动，快速到位、极轻微回弹。 */
    val messageSendDamping: Float = iosSpringDampingRatio(bounce = 0.12f)

    /** 同上（send 的刚度）。 */
    val messageSendStiffness: Float = iosSpringStiffness(durationSeconds = 0.35f)

    /** iOS `MessageSpring.receive` = spring(0.35s, bounce 0.10)：AI 消息到达，柔和弹簧。 */
    val messageReceiveDamping: Float = iosSpringDampingRatio(bounce = 0.10f)

    /** 同上（receive 的刚度）。 */
    val messageReceiveStiffness: Float = iosSpringStiffness(durationSeconds = 0.35f)

    /**
     * 点赞爱心弹跳 = iOS MomentPostCard.swift:191-205 spring(duration 0.3, bounce 0.5)。
     * bounce 0.5 → dampingRatio 0.5（恰为 Compose DampingRatioMediumBouncy）。
     */
    val likeBounceDamping: Float = iosSpringDampingRatio(bounce = 0.5f)

    /** 同上（点赞弹跳的刚度）。 */
    val likeBounceStiffness: Float = iosSpringStiffness(durationSeconds = 0.3f)

    /** iOS `.smooth(duration: 0.3)` 等价 tween 时长（礼物揭晓 GiftReactionView 等柔和过渡）。 */
    const val SMOOTH_MS = 300

    /** iOS `.smooth(duration: 0.35)` 等价 tween 时长（余额数字滚动 InChatGiftSheetView 等）。 */
    const val SMOOTH_LONG_MS = 350

    /** 用户消息入场/移动弹簧（iOS MessageSpring.send）。 */
    fun <T> messageSendSpring(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(messageSendDamping, messageSendStiffness, visibilityThreshold)

    /** AI 消息入场/移动弹簧（iOS MessageSpring.receive）。 */
    fun <T> messageReceiveSpring(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(messageReceiveDamping, messageReceiveStiffness, visibilityThreshold)

    /** 点赞爱心弹跳弹簧（iOS MomentPostCard）。 */
    fun <T> likeBounceSpring(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(likeBounceDamping, likeBounceStiffness, visibilityThreshold)

    /**
     * iOS `.smooth(duration: 0.3)` 精确弹簧（= spring(duration: 0.3, bounce: 0) 临界阻尼，ζ=1、k≈438.65）：
     * 礼物揭晓（GiftReactionView P1-11）等柔和过渡的属性动画。fade/转场 API 上的近似仍用 [SMOOTH_MS] tween。
     */
    fun <T> smoothSpring(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(iosSpringDampingRatio(bounce = 0f), iosSpringStiffness(durationSeconds = 0.3f), visibilityThreshold)

    // ── Fable-5 两轴四档语义别名（FABLE5_DESIGN_LANGUAGE.md §4）·复用上面 iOS 派生换算常量不推翻 ──
    // 空间轴 calm/gentle/lively/celebrate（ζ1.0/0.88/0.78/0.5）+ 效果轴恒 ζ1.0（颜色/透明度永不过冲）。
    // reduceMotion 降级仍由 [rememberReduceMotion] 门控（spatial→瞬时·effects 保留·循环→静态首帧·celebrate→单帧）。

    /** calm ζ1.0：点按/档案入口按压/菜单入场/语音转写展开（无可感过冲·= [smoothSpring]）。 */
    fun <T> calmSpring(visibilityThreshold: T? = null): SpringSpec<T> = smoothSpring(visibilityThreshold)

    /** gentle 阻尼（ζ≈0.88·品牌默认·= MessageSpring.send bounce 0.12）。 */
    val gentleDamping: Float = iosSpringDampingRatio(bounce = 0.12f)

    /** gentle 刚度（= MessageSpring.send 0.35s）。 */
    val gentleStiffness: Float = iosSpringStiffness(durationSeconds = 0.35f)

    /** gentle ζ0.88（品牌默认）：消息入场/卡片入场/副标题切换/状态条进出/回弹吸附。 */
    fun <T> gentleSpring(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(gentleDamping, gentleStiffness, visibilityThreshold)

    /** lively 阻尼（ζ≈0.78·bounce 0.22）。 */
    val livelyDamping: Float = iosSpringDampingRatio(bounce = 0.22f)

    /** lively 刚度（0.3s）。 */
    val livelyStiffness: Float = iosSpringStiffness(durationSeconds = 0.3f)

    /** lively ζ0.78：微反馈·主钮 icon morph/FAB 出现/箭头越阈弹大/负向高唤起情绪入场（轻微过冲）。 */
    fun <T> livelySpring(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(livelyDamping, livelyStiffness, visibilityThreshold)

    /** celebrate ζ0.5：红包/里程碑揭晓（**每屏限一处**·= [likeBounceSpring]）。 */
    fun <T> celebrateSpring(visibilityThreshold: T? = null): SpringSpec<T> = likeBounceSpring(visibilityThreshold)

    /** 效果轴（颜色/透明度·恒 ζ1.0 不过冲）·快档 k=3800。 */
    fun <T> effectFastSpring(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(1f, 3800f, visibilityThreshold)

    /** 效果轴·中档 k=1600。 */
    fun <T> effectMediumSpring(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(1f, 1600f, visibilityThreshold)

    /** 效果轴·慢档 k=800。 */
    fun <T> effectSlowSpring(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(1f, 800f, visibilityThreshold)

    // ── Telegram 缓动曲线调色板（Chunk 0·参照 DrKLO/Telegram CubicBezierInterpolator.java:11-22 控制点）──
    // 控制点为数学事实（非版权）；用 Compose CubicBezierEasing 重写，与 Telegram 同解同一参数曲线 → 取值肉眼无差。
    // 用途：给「定时类」动画（tween/AnimatedContent/Crossfade 等非弹簧场景）一套统一、有质感的缓动；弹簧底座不变。
    // 这些是不可见基础设施常量，wire 进具体可见动画须随各自 chunk 过审（FABLE5_CHAT_ANIMATION_TELEGRAM_PROPOSAL.md §6）。

    /** 强减速·先快后极缓地停（Telegram EASE_OUT_QUINT）：高级感「落位」；弧线飞入 X 轴用它。 */
    val EaseOutQuint: Easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

    /** 过冲回弹·冲过头一点再回（Telegram EASE_OUT_BACK·y 控制点 1.56>1 → 越过 1 再落）。 */
    val EaseOutBack: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

    /** 标准减速·单纯缓停（Telegram EASE_OUT）。 */
    val EaseOut: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

    /** 通用缓入缓出（Telegram DEFAULT = CSS ease）：弧线飞入 Y 轴 / 按压「按下」相用它。 */
    val EaseInOut: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    /** Material 强调减速（Telegram EmphasizedDecelerate）：大位移入场。 */
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /**
     * ④发送飞入·纵轴曲线（Telegram ChatListItemAnimator.DEFAULT_INTERPOLATOR 控制点·契约
     * FABLE5_CHAT_TELEGRAM_MOTION §4）：与列表推入同源的缓入缓出偏出——气泡纵向随列表浮起的节奏。
     */
    val TgMessageY: Easing = CubicBezierEasing(0.1992f, 0.0106f, 0.2792f, 0.9103f)

    /**
     * ④发送飞入·横轴曲线（Telegram TextMessageEnterTransition:473 `EASE_OUT(EASE_OUT_QUINT(t))` 双重嵌套）：
     * 极快到位——与慢纵轴分工形成弧线感（这是该动效的灵魂，勿合并成单曲线）。
     */
    val TgFlightX: Easing = Easing { f -> EaseOut.transform(EaseOutQuint.transform(f)) }

    // ── 按压回弹（Chunk 1·参照 DrKLO/Telegram ButtonBounce.java:82-89·D1 用户定 overshoot=5.0）──
    // 按下快缩（60ms·EaseInOut=Telegram DEFAULT 曲线）；松手过冲回弹（350ms·OvershootInterpolator(5.0)）。
    // overshootEasing 直接实现 Android OvershootInterpolator 公式（纯函数·可 JVM 单测·与系统类同解）。

    /** Android `OvershootInterpolator(tension)` 公式：松手冲过目标再回。f→t=f-1; t²((s+1)t+s)+1。 */
    fun overshootEasing(tension: Float): Easing = Easing { f ->
        val t = f - 1f
        t * t * ((tension + 1f) * t + tension) + 1f
    }

    /** 按压「按下」相：60ms 快缩（Telegram press 时长 + DEFAULT 曲线）。 */
    val pressDownSpec: AnimationSpec<Float> = tween(durationMillis = 60, easing = EaseInOut)

    /** 按压「松手」相：350ms 过冲回弹（Telegram release 时长 + OvershootInterpolator(5.0)）。 */
    val pressReleaseSpec: AnimationSpec<Float> = tween(durationMillis = 350, easing = overshootEasing(5f))
}

/** iOS `spring(duration:bounce:)` 的 bounce → Compose dampingRatio：bounce 0=临界阻尼、1=不衰减，ζ = 1 − bounce。 */
internal fun iosSpringDampingRatio(bounce: Float): Float = 1f - bounce

/** iOS `spring(duration:bounce:)` 的 duration → Compose stiffness：质量 1 时 ω = 2π/T，k = ω²。 */
internal fun iosSpringStiffness(durationSeconds: Float): Float {
    // 安全阀（P5）：duration=0 → ω=2π/0=Infinity → k=Infinity，下游 Compose 动画核会喷 NaN/卡死（非干净瞬时）。
    // 现有调用方全是正字面量（0.25/0.3/0.35），此 require 当下绝不触发；只为挡住将来误传 0/负值的回归——
    // 启动期(AppMotion 常量初始化)即响、开发期立刻暴露，生产永不触发。
    require(durationSeconds > 0f) { "spring duration must be > 0s, got $durationSeconds" }
    val omega = (2.0 * PI / durationSeconds).toFloat()
    return omega * omega
}

/**
 * 系统「移除动画」等价（对应 iOS `accessibilityReduceMotion`）：动画时长缩放为 0 时关闭装饰动画。
 * 安卓无统一「减弱动态效果」开关，用 `ANIMATOR_DURATION_SCALE==0` 作等价信号。
 *
 * 批0（2026-06-10）自 ui/offline/OfflineBlockAnimations.kt 提升至此：消费方已遍布
 * 红包/故事/线下模式，并将随 P1 动效批扩展到聊天/朋友圈/礼物/钱包，不再属于线下模式专用。
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}
