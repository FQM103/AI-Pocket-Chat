package com.situ.aichat.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.GlassBackdrop
import com.situ.aichat.ui.designsystem.OnGlass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * ② 滚动浮动日期胶囊（契约 FABLE5_CHAT_TELEGRAM_MOTION_PROPOSAL.md §2·M1）：用户拖动/甩动浏览消息时，
 * 列表顶部浮出「当前所在日期」小药丸；停止滚动驻留 [HIDE_DELAY_MS] 后淡出。节奏与触发照 Telegram 考古
 * （淡入/出 150ms·停滚 500ms·只认用户手势——程序化滚动如回底钮/贴底锚定不触发）；**有意分叉**：不做
 * sticky 接管/推走过渡（见契约 §2.3；V8 起列表内分隔行已整体退役，本胶囊为翻史唯一日期定位件）。
 * 纯显示：不消费指针、对读屏隐形（时间信息由气泡内嵌时间戳承担·照 Telegram IMPORTANT_FOR_ACCESSIBILITY_NO）。
 */

/** 停止滚动后的驻留时长（Telegram hideDateDelay=500ms·ChatActivity.java:600）。 */
private const val HIDE_DELAY_MS = 500L

/** 淡入/淡出时长（Telegram 150ms·ChatActivity.java:13455-13490）。 */
private const val FADE_MS = 150

/** 胶囊上缘距消息区顶线（Telegram 4dp·ChatActivity.java:7039）。 */
private val CapsuleTopGap = 4.dp

/**
 * 显隐状态机（可测纯逻辑·T2）：拖动开启「用户滚动会话」、贯穿 fling、滚动归零收束；会话结束驻留
 * [hideDelayMs] 后隐藏；抑制（最早项可见/顶部横幅在场）→ **立即**隐藏不驻留；会话中解除抑制 → 立即复显。
 * 淡入淡出动画不在本类（由组合层 animateFloatAsState 承担·打断自然从当前 alpha 续动=Telegram 13450-13453）。
 */
internal class FloatingDateVisibility(
    private val scope: CoroutineScope,
    private val hideDelayMs: Long = HIDE_DELAY_MS,
) {
    var visible by mutableStateOf(false)
        private set

    private var dragging = false
    private var scrolling = false
    private var sessionActive = false
    private var suppressed = false
    private var hideJob: Job? = null

    fun onDragStart() {
        dragging = true
        sessionActive = true
        refresh()
    }

    fun onDragEnd() {
        dragging = false
        endSessionIfSettled()
    }

    fun onScrollingChanged(inProgress: Boolean) {
        scrolling = inProgress
        endSessionIfSettled()
    }

    fun onSuppressedChanged(value: Boolean) {
        if (suppressed == value) return
        suppressed = value
        refresh()
    }

    /** 手指已离、滚动已停（含 fling 收尾）→ 会话收束。松手静止（无 fling）时滚动可能先于抬指归零，两入口都收口。 */
    private fun endSessionIfSettled() {
        if (sessionActive && !dragging && !scrolling) {
            sessionActive = false
            refresh()
        }
    }

    private fun refresh() {
        when {
            suppressed -> {
                // 立即熄灭（Telegram「最早日期行可见」特例=瞬时 alpha 0·16166-16177）——不走 500ms 驻留。
                hideJob?.cancel()
                hideJob = null
                visible = false
            }
            sessionActive -> {
                hideJob?.cancel()
                hideJob = null
                visible = true
            }
            visible && hideJob == null -> hideJob = scope.launch {
                delay(hideDelayMs)
                visible = false
                hideJob = null
            }
        }
    }
}

/** 天粒度标签口径（拍板 D1）：今天 / 昨天 / 同年「M月d日」/ 跨年「yyyy年M月d日」。 */
internal enum class FloatingDateLabel { TODAY, YESTERDAY, SAME_YEAR, OTHER_YEAR }

internal fun floatingDateLabel(date: LocalDate, today: LocalDate): FloatingDateLabel = when {
    date == today -> FloatingDateLabel.TODAY
    date == today.minusDays(1) -> FloatingDateLabel.YESTERDAY
    date.year == today.year -> FloatingDateLabel.SAME_YEAR
    else -> FloatingDateLabel.OTHER_YEAR
}

/**
 * 抑制条件：列表空，或最早已加载项可见。一并覆盖 Telegram 两条兜底——「历史最早日期行可见永不浮动」
 * （短聊天/翻到开头）与「顶部露出非消息行抑制」（我方上翻加载态同样发生在最早项区）。
 * 列表反转（契约 REVERSE_LIST §2.2）后最早项 = **最大 index**：以「视觉顶=最大可见 index」判定。
 */
internal fun floatingDateSuppressed(lastVisibleItemIndex: Int, totalItemsCount: Int): Boolean =
    totalItemsCount == 0 || lastVisibleItemIndex >= totalItemsCount - 1

/** 中文日期格式（与气泡内嵌时间戳同族·胶囊取天粒度）。 */
private val MonthDayCn = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
private val YearMonthDayCn = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)

/**
 * 浮动日期胶囊本体。挂在消息区顶部中央（[BoxScope]·横幅声明序之前=横幅压其上）；[topBannerVisible]
 * 在场时并入抑制（防双 TopCenter 重叠·此时横幅本身已是顶部信息主角）。皮肤双态（拍板 D3）：
 * 有壁纸=毛玻璃药丸（[GlassBackdrop] 五要素同配方·与顶栏同族），无壁纸=surface.raised 半透+发丝边。
 */
@Composable
internal fun BoxScope.FloatingDateCapsule(
    listState: LazyListState,
    listItems: List<ChatRenderItem>,
    topBannerVisible: Boolean,
    wallpaperFrosted: ImageBitmap?,
    wallpaperDark: Boolean,
    hasWallpaper: Boolean,
) {
    val scope = rememberCoroutineScope()
    val machine = remember(scope) { FloatingDateVisibility(scope) }
    LaunchedEffect(listState) {
        // 只认用户手势：DragInteraction 由真实拖拽发出，程序化 animateScrollToItem 不发（同 userHasScrolled 口径）。
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> machine.onDragStart()
                is DragInteraction.Stop, is DragInteraction.Cancel -> machine.onDragEnd()
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { machine.onScrollingChanged(it) }
    }
    val suppressed by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            // 可见集为空的首帧瞬间按「抑制」兜底（=旧顶锚口径·T5 复核 🔵3）：total-1 恒满足抑制谓词。
            val topIndex = layout.visibleItemsInfo.lastOrNull()?.index ?: (layout.totalItemsCount - 1)
            floatingDateSuppressed(topIndex, layout.totalItemsCount)
        }
    }
    LaunchedEffect(suppressed, topBannerVisible) { machine.onSuppressedChanged(suppressed || topBannerVisible) }

    // 淡入淡出 150ms（效果轴恒无过冲）。reduceMotion 下**有意保留**：纯透明度属设计语言降级表「effects 保留」列。
    val alpha by animateFloatAsState(
        targetValue = if (machine.visible) 1f else 0f,
        animationSpec = tween(FADE_MS, easing = AppMotion.EaseInOut),
        label = "floatingDateAlpha",
    )
    if (alpha <= 0.01f) return

    // 锚点=视觉顶部第一条可见消息的日期；反转列表下视觉顶=最大可见 index（契约 REVERSE_LIST §2.2）。
    // （V8 起列表只余消息项·分隔行已退役——本胶囊成为翻史时唯一的日期定位件。）
    // LocalDate 粒度去重 → 跨天才换字（拍板 D2 瞬切）。
    val zone = remember { ZoneId.systemDefault() }
    val anchorDate by remember(listItems, listState) {
        derivedStateOf {
            val topIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            val ts = when (val item = topIndex?.let(listItems::getOrNull)) {
                is ChatRenderItem.Message -> item.entity.timestamp
                null -> null
            }
            ts?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        }
    }
    val date = anchorDate ?: return
    // 「今天」基准随日期锚点一起冻结（remember(date)）——跨午夜不即时刷新与分隔行同口径（账本 C 类已接受）。
    val text = when (remember(date) { floatingDateLabel(date, LocalDate.now(zone)) }) {
        FloatingDateLabel.TODAY -> stringResource(R.string.schedule_day_today)
        FloatingDateLabel.YESTERDAY -> stringResource(R.string.schedule_day_yesterday)
        FloatingDateLabel.SAME_YEAR -> remember(date) { date.format(MonthDayCn) }
        FloatingDateLabel.OTHER_YEAR -> remember(date) { date.format(YearMonthDayCn) }
    }

    val label = @Composable { color: androidx.compose.ui.graphics.Color ->
        Text(
            text = text,
            style = AppTypography.captionNumeric,
            fontWeight = FontWeight.Medium,
            color = color,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .padding(top = CapsuleTopGap)
            .graphicsLayer { this.alpha = alpha }
            // 纯显示：读屏隐形（照 Telegram）；无 clickable=不消费指针，列表手势穿透。
            .clearAndSetSemantics {},
    ) {
        if (hasWallpaper) {
            GlassBackdrop(
                blurred = wallpaperFrosted,
                dark = wallpaperDark,
                shape = AppShapes.full,
                modifier = Modifier.clip(AppShapes.full),
            ) {
                label(if (wallpaperDark) OnGlass.PrimaryOnDark else OnGlass.PrimaryOnLight)
            }
        } else {
            Box(
                Modifier
                    .clip(AppShapes.full)
                    .background(AppTheme.colors.surface.raised.copy(alpha = 0.92f))
                    .border(CapsuleHairline, AppTheme.colors.surface.stroke, AppShapes.full),
            ) {
                label(AppTheme.colors.text.secondary)
            }
        }
    }
}

/** 无壁纸档的发丝描边宽（与玻璃档 GLASS_HAIRLINE 同粗细口径）。 */
private val CapsuleHairline = 0.75.dp
