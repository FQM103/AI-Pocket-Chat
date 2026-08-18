package com.situ.aichat.ui.story

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.ui.components.rememberReduceMotion

// 故事阅读器顶栏 = 三件浮岛（过审 mockup story_reader_chrome_restyle_options·方案 A）：
// 左圆钮「返回」+ 中央内容自适应标题胶囊 + 右圆钮「⋮」（菜单本体在 [StoryReaderMenu]）。
// 相比旧「通宽大胶囊」，chrome 只占内容所需的宽度，正文透出更多、沉浸感更强，
// 且与 App 底栏「悬浮胶囊」/本屏玻璃族（菜单/撤销条/选项卡）同一语言。

/** 浮岛入场错峰：标题先落、两翼对称跟进（相对延迟 ms）。 */
private const val ISLAND_SIDE_DELAY_MS = 60

@Composable
internal fun ReaderTopBar(
    chapter: StoryChapterEntity?,
    storyTitle: String?,
    isDark: Boolean,
    readingAnimationsEnabled: Boolean,
    fontSizeIndex: Int,
    onBack: () -> Unit,
    /** 卷三 §4.6：菜单唯一的导航行「书页」；null = storyId 还没解析出来，该行不显示。 */
    onOpenBookHub: (() -> Unit)?,
    onToggleAnimations: (Boolean) -> Unit,
    onSetFontSizeIndex: (Int) -> Unit,
    onMenuExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 控件颜色随当前纸面明暗自适应（深背景→浅色）。
    val chromeColor = StoryReaderLayout.textColor(isDark)
    val chromeSecondary = StoryReaderLayout.secondaryTextColor(isDark)
    val chromeScrim = StoryReaderLayout.islandScrimColor(isDark)
    val chromeBorder = StoryReaderLayout.chromeBorderColor(isDark)
    val reduceMotion = rememberReduceMotion()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        FloatingIsland(delayMillis = ISLAND_SIDE_DELAY_MS, reduceMotion = reduceMotion, modifier = Modifier.align(Alignment.CenterStart)) {
            GlassCircleButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                tint = chromeColor,
                container = chromeScrim,
                border = chromeBorder,
                onClick = onBack,
            )
        }
        FloatingIsland(delayMillis = 0, reduceMotion = reduceMotion, modifier = Modifier.align(Alignment.Center)) {
            TitleCapsule(
                storyTitle = storyTitle,
                chapter = chapter,
                container = chromeScrim,
                border = chromeBorder,
                titleColor = chromeColor,
                secondaryColor = chromeSecondary,
            )
        }
        FloatingIsland(delayMillis = ISLAND_SIDE_DELAY_MS, reduceMotion = reduceMotion, modifier = Modifier.align(Alignment.CenterEnd)) {
            StoryReaderMenu(
                readingAnimationsEnabled = readingAnimationsEnabled,
                fontSizeIndex = fontSizeIndex,
                isDark = isDark,
                contentColor = chromeColor,
                secondaryColor = chromeSecondary,
                borderColor = chromeBorder,
                triggerColor = chromeScrim,
                onOpenBookHub = onOpenBookHub,
                onToggleAnimations = onToggleAnimations,
                onSetFontSizeIndex = onSetFontSizeIndex,
                onExpandedChange = onMenuExpandedChange,
            )
        }
    }
}

/**
 * 浮岛入场（chrome 每次被唤出都重放）：从上方 6dp 轻飘落位 + 淡入，240ms ease-out，
 * 标题先落、两翼对称延迟跟进；reduceMotion 直接就位（外层 AnimatedVisibility 仍有整体淡入淡出）。
 */
@Composable
private fun FloatingIsland(
    delayMillis: Int,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var entered by remember { mutableStateOf(reduceMotion) }
    LaunchedEffect(Unit) { entered = true }
    val t by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(240, delayMillis = delayMillis, easing = EaseOut),
        label = "islandEnter",
    )
    Box(
        modifier.graphicsLayer {
            alpha = t
            translationY = (1f - t) * (-6).dp.toPx()
        },
    ) { content() }
}

/** 圆形玻璃小岛钮（44dp 统一岛高·与胶囊同配方：玻璃垫底 + 0.75dp 发丝迎光边）。 */
@Composable
private fun GlassCircleButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    container: Color,
    border: Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = container,
        border = BorderStroke(0.75.dp, border),
        modifier = Modifier.size(StoryReaderLayout.islandHeight),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

/** 中央标题胶囊（内容自适应宽·封顶防撞两翼）：书名 serif + 「· 第 N 话」。 */
@Composable
private fun TitleCapsule(
    storyTitle: String?,
    chapter: StoryChapterEntity?,
    container: Color,
    border: Color,
    titleColor: Color,
    secondaryColor: Color,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = container,
        border = BorderStroke(0.75.dp, border),
        modifier = Modifier.widthIn(max = 208.dp),
    ) {
        Row(
            modifier = Modifier.height(StoryReaderLayout.islandHeight).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                storyTitle?.takeIf { it.isNotBlank() } ?: stringResource(R.string.story_reader_untitled),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Serif,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = titleColor,
                modifier = Modifier.weight(1f, fill = false),
            )
            chapter?.let {
                Text(
                    "· " + stringResource(R.string.story_reader_chapter_n, it.chapterNumber),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = secondaryColor,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}
