package com.situ.aichat.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.situ.aichat.R
import com.situ.aichat.data.model.imagePaths
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.MomentRepository
import com.situ.aichat.notification.Notifier
import com.situ.aichat.util.ContentImageStore
import com.situ.aichat.util.DateFormatters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 最新动态（朋友圈）桌面小组件（13.9b · C1，安卓超越 iOS——iOS 无朋友圈小组件）。
 *
 * 展示最新一条**角色**朋友圈动态：头像 + 名字 + 内容摘要 + 缩略图（中号）+ 相对时间。点整块 → 该帖详情。
 *
 * **安卓地道做法 / 数据流**：与 App 同进程经 Hilt EntryPoint 直读 Room（仿 [PetGlanceWidget]）；新帖由后台
 * 发帖 worker 写库 → [MomentWidgetSync] 观察 feed 流即时刷新；相对时间随 [com.situ.aichat.work.WidgetRefreshWorker]
 * 每 30 分定期翻新。跳转复用既有朋友圈帖深链（[Notifier.momentClickIntent] → MainActivity → `moment/{uuid}`），无需新增路由。
 */
class MomentFeedGlanceWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL_SIZE, MEDIUM_SIZE))

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun momentRepository(): MomentRepository
        fun characterRepository(): CharacterRepository
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = EntryPointAccessors.fromApplication(context, Deps::class.java)
        val state = buildState(deps, System.currentTimeMillis())
        // P1-32：按显示尺寸密度感知降采样（原 load(path) 走默认 1024px 全尺寸=真 bug；头像按两布局最大 26dp 取）。
        val density = context.resources.displayMetrics.density
        val avatar = state?.avatarPath?.let {
            decodeAvatarForWidget(it, widgetTargetPx(FEED_AVATAR_MEDIUM.value, density))
        }
        val thumbnail = state?.imagePath?.let {
            ContentImageStore.load(it, widgetTargetPx(FEED_THUMBNAIL.value, density))
        }
        provideContent {
            GlanceTheme {
                when {
                    state == null -> EmptyContent(context)
                    LocalSize.current.width >= MEDIUM_THRESHOLD -> MediumContent(context, state, avatar, thumbnail)
                    else -> SmallContent(context, state, avatar)
                }
            }
        }
    }

    /** 组装快照：选最新角色帖 → 取作者名/头像 + 首图 + 相对时间。无角色帖 → null。 */
    private suspend fun buildState(deps: Deps, now: Long): MomentWidgetState? {
        val post = MomentWidgetData.pickLatestCharacterPost(deps.momentRepository().latestCharacterPosts(1))
            ?: return null
        val character = post.characterUuid?.let { deps.characterRepository().get(it) }
        return MomentWidgetState(
            postUuid = post.uuid,
            authorName = character?.name?.takeIf { it.isNotBlank() }.orEmpty(),
            avatarPath = character?.avatarPath,
            content = post.content,
            timeText = DateFormatters.momentTimeDescription(post.timestamp, now),
            imagePath = post.imagePaths.firstOrNull(),
        )
    }

    private companion object {
        val SMALL_SIZE = DpSize(110.dp, 110.dp)
        val MEDIUM_SIZE = DpSize(250.dp, 110.dp)
        val MEDIUM_THRESHOLD = 200.dp
    }
}

// P1-32：显示尺寸=解码目标（强耦合防未来改版式时脱钩重新发糊）。布局是顶级函数，常量随之放顶级。
private val FEED_AVATAR_SMALL = 22.dp
private val FEED_AVATAR_MEDIUM = 26.dp
private val FEED_THUMBNAIL = 72.dp

// MARK: - 小号布局（头像/名字 + 内容摘要 + 时间；空间小不放缩略图）

@Composable
private fun SmallContent(context: Context, state: MomentWidgetState, avatar: Bitmap?) {
    WidgetSurface(onBodyClick = actionStartActivity(openMomentIntent(context, state.postUuid))) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(authorName(context, state), avatar, FEED_AVATAR_SMALL)
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    text = authorName(context, state),
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                )
            }
            Spacer(GlanceModifier.height(5.dp))
            Text(
                text = contentText(context, state),
                maxLines = 2,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 11.sp),
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                text = state.timeText,
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 9.sp),
            )
        }
    }
}

// MARK: - 中号布局（左 头像/名字 + 内容 + 时间；右 缩略图）

@Composable
private fun MediumContent(context: Context, state: MomentWidgetState, avatar: Bitmap?, thumbnail: Bitmap?) {
    WidgetSurface(onBodyClick = actionStartActivity(openMomentIntent(context, state.postUuid))) {
        Row(modifier = GlanceModifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(authorName(context, state), avatar, FEED_AVATAR_MEDIUM)
                    Spacer(GlanceModifier.width(8.dp))
                    Text(
                        text = authorName(context, state),
                        maxLines = 1,
                        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    )
                }
                Spacer(GlanceModifier.height(6.dp))
                Text(
                    text = contentText(context, state),
                    maxLines = 2,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp),
                )
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    text = state.timeText,
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp),
                )
            }
            if (thumbnail != null) {
                Spacer(GlanceModifier.width(10.dp))
                Image(
                    provider = ImageProvider(thumbnail),
                    contentDescription = null,
                    modifier = GlanceModifier.size(FEED_THUMBNAIL).cornerRadius(10.dp),
                )
            }
        }
    }
}

// MARK: - 无动态

@Composable
private fun EmptyContent(context: Context) {
    Box(
        modifier = GlanceModifier.fillMaxSize().appWidgetBackground().background(GlanceTheme.colors.widgetBackground).cornerRadius(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("📷", style = TextStyle(fontSize = 26.sp, textAlign = TextAlign.Center))
            Spacer(GlanceModifier.height(4.dp))
            Text(
                text = context.getString(R.string.moment_widget_empty),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center),
            )
        }
    }
}

// MARK: - 复用片段

/** 作者名；缺名（角色已删/无名）回退「好友」占位。 */
private fun authorName(context: Context, state: MomentWidgetState): String =
    state.authorName.ifBlank { context.getString(R.string.moment_widget_unknown_author) }

/** 内容摘要；纯图片帖（content 空）回退「分享了图片」说明，不留空白。 */
private fun contentText(context: Context, state: MomentWidgetState): String =
    state.content.trim().ifBlank { context.getString(R.string.moment_widget_image_only) }

/**
 * 点击 → 打开该帖详情。复用既有朋友圈帖深链 [Notifier.momentClickIntent]（MainActivity 经 momentClickUuidFrom
 * 路由到 `moment/{uuid}`），追加唯一 data Uri 保证 PendingIntent 隔离（对齐 [PetWidgetIntents] 做法）。
 */
private fun openMomentIntent(context: Context, postUuid: String): Intent =
    Notifier.momentClickIntent(context, postUuid).apply {
        data = Uri.parse("aichat://momentwidget/$postUuid")
    }
