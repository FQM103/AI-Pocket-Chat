package com.situ.aichat.ui.story

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion

/**
 * 「上回说到」回访前情条（卷三 C3·图纸 §4.3 画面③④）。
 *
 * 隔了一阵（≥ [com.situ.aichat.story.StoryRecapLogic.RECAP_THRESHOLD_MS]）回到阅读器时，挂在封面之后、正文之前，
 * 把**上一章既有的** `chapterSummary` 摊开给读者接回剧情——零 LLM 调用、零新生成。
 * 出不出由 [com.situ.aichat.story.StoryRecapLogic.showRecap] 在进屏那一刻定（不追弹）。
 *
 * 视觉全部走「回顾/收尾」金族：底/边/金字**逐值引用**建议完结卡现用取值
 * （[StoryReaderLayout.suggestGoldColor] + [SUGGEST_GOLD_FILL_ALPHA] / [SUGGEST_GOLD_LINE_ALPHA]），不新造色值；
 * 正文沿建议完结卡的正文口径（纸面自适应 [StoryReaderLayout.textColor] × [SUGGEST_BODY_ALPHA]）——
 * 阅读器内容压的是**阅读器纸面**不是 App 卡面，用主题级 text token 会在深色纸面上翻车（图纸 §11 D-2）。
 *
 * 展开↔收起只做尺寸过渡（[AppMotion.gentleSpring] 档），reduceMotion 时直切；无入场动画、不新增动效档。
 */
@Composable
fun StoryRecapStrip(
    summary: String,
    expanded: Boolean,
    isDark: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalAppHaptics.current
    val reduceMotion = rememberReduceMotion()
    val gold = StoryReaderLayout.suggestGoldColor(isDark)
    val paperText = StoryReaderLayout.textColor(isDark)
    // 展开↔收起的尺寸过渡：gentle 档（品牌默认 ζ0.88）；reduceMotion 直切（snap = 无过渡）。
    val expandFraction by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else AppMotion.gentleSpring(),
        label = "recapExpand",
    )

    if (expanded) {
        Surface(
            shape = RoundedCornerShape(RECAP_CORNER),
            color = gold.copy(alpha = SUGGEST_GOLD_FILL_ALPHA),
            border = BorderStroke(0.75.dp, gold.copy(alpha = SUGGEST_GOLD_LINE_ALPHA)),
            modifier = modifier
                .fillMaxWidth()
                // 收起过程中高度按 gentle 收拢（reduceMotion 时 fraction 直接落 0/1 = 无过渡）。
                .heightIn(min = RECAP_MIN_HEIGHT * expandFraction),
        ) {
            Column(modifier = Modifier.padding(RECAP_PADDING), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.story_recap_title),
                        color = gold,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.W700,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.story_recap_collapse),
                        color = paperText.copy(alpha = RECAP_COLLAPSE_ALPHA),
                        fontSize = 12.sp,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clickable(role = Role.Button) { haptics.light(); onToggle(false) }
                            .padding(start = 12.dp),
                    )
                }
                Text(
                    summary,
                    color = paperText.copy(alpha = SUGGEST_BODY_ALPHA),
                    fontSize = 12.sp,
                    lineHeight = 20.4.sp, // 12 × 1.7
                )
            }
        }
    } else {
        Surface(
            shape = CircleShape,
            color = gold.copy(alpha = SUGGEST_GOLD_FILL_ALPHA),
            border = BorderStroke(0.75.dp, gold.copy(alpha = SUGGEST_GOLD_LINE_ALPHA)),
            modifier = modifier
                .minimumInteractiveComponentSize()
                .clickable(role = Role.Button) { haptics.light(); onToggle(true) },
        ) {
            Text(
                stringResource(R.string.story_recap_chip),
                color = gold,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** 卡圆角（图纸 §4.3 锁定值）。 */
private val RECAP_CORNER = 16.dp

/** 卡内边距（图纸 §4.3 锁定值）。 */
private val RECAP_PADDING = 14.dp

/** 展开态最小高度（收起过渡的收拢终点；实际高度由内容撑开）。 */
private val RECAP_MIN_HEIGHT = 72.dp

/** 「收起」文字钮的内容不透明度（图纸 §4.3：content @0.7）。 */
private const val RECAP_COLLAPSE_ALPHA = 0.7f
