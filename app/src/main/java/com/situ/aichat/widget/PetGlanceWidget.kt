package com.situ.aichat.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
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
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.situ.aichat.R
import com.situ.aichat.data.repository.PetRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.pet.PetCareService
import com.situ.aichat.pet.PetWidgetData
import com.situ.aichat.pet.PetWidgetMood
import com.situ.aichat.pet.toPetWidgetData
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 宠物状态小组件（P11.3 · M19；1:1 iOS `PetWidget` 小号 + 中号）。主屏显示宠物精灵图 + 名字 + 心情，
 * 心情底色随状态变化。小号点击打开宠物详情；中号加迷你状态条 + feed/pet 快捷操作（1:1 iOS `PetWidgetMediumView`）。
 *
 * **安卓地道适配**：同进程直接读 Room（[PetRepository]，经 Hilt EntryPoint），不照搬 iOS App Group 桥；
 * 装扮 overlay 暂不在小组件渲染（Glance/RemoteViews 无法叠 Compose Canvas 矢量装扮，App 内仍展示）；
 * iOS systemSmall/systemMedium 两档 → 安卓单 provider + 响应式尺寸（用户缩放，地道做法）。
 */
class PetGlanceWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL_SIZE, MEDIUM_SIZE))

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PetWidgetDeps {
        fun petRepository(): PetRepository
        fun settingsRepository(): SettingsRepository
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = EntryPointAccessors.fromApplication(context, PetWidgetDeps::class.java)
        val data = pickWidgetPet(deps.petRepository(), deps.settingsRepository())
        val bitmap = data?.let { loadPetSpriteBitmap(context, it.speciesRaw, it.growthStageRaw) }
        provideContent {
            GlanceTheme {
                when {
                    data == null -> NoPetContent(context)
                    LocalSize.current.width >= MEDIUM_THRESHOLD -> PetMediumContent(context, data, bitmap)
                    else -> PetSmallContent(context, data, bitmap)
                }
            }
        }
    }

    /**
     * 选最近互动的宠物（1:1 iOS「最后同步的那只」语义；lastInteractionDate 缺则回退 adoptedDate）。
     * C1#3：转小组件快照前先**现算时间衰减**（[PetCareService.applyDecay] 纯变换·不写库），让小组件即使在 App 被
     * HyperOS 杀后、靠 30 分周期 worker 重渲染时也按当前时间显示新鲜的饥饿/清洁/心情，而非 DB 里的陈旧静态值。
     * 真正的衰减写库仍由回前台维护负责（此处变换的实体丢弃，不双扣）。
     */
    private suspend fun pickWidgetPet(repo: PetRepository, settingsRepo: SettingsRepository): PetWidgetData? {
        val pets = repo.getAll()
        if (pets.isEmpty()) return null
        val pet = pets.maxByOrNull { it.lastInteractionDate ?: it.adoptedDate } ?: return null
        val settings = settingsRepo.getAppSettings()
        return PetCareService.applyDecay(pet, settings).toPetWidgetData()
    }

    private companion object {
        val SMALL_SIZE = DpSize(110.dp, 110.dp)
        val MEDIUM_SIZE = DpSize(250.dp, 110.dp)
        val MEDIUM_THRESHOLD = 200.dp
    }
}

// MARK: - 小号布局（精灵图 + 名字 + 心情）

@Composable
private fun PetSmallContent(context: Context, data: PetWidgetData, bitmap: Bitmap?) {
    WidgetSurface(onBodyClick = actionStartActivity(PetWidgetIntents.openPetDetail(context, data.characterUuid)), tint = data.mood.tint()) {
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PetSprite(bitmap, 72.dp)
            Spacer(GlanceModifier.height(6.dp))
            Text(
                text = data.petName,
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
            )
            Text(
                text = context.getString(data.mood.labelRes()),
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp, textAlign = TextAlign.Center),
            )
        }
    }
}

// MARK: - 中号布局（精灵图 + 名字/心情 + 操作 + 迷你状态条）

@Composable
private fun PetMediumContent(context: Context, data: PetWidgetData, bitmap: Bitmap?) {
    WidgetSurface(onBodyClick = actionStartActivity(PetWidgetIntents.openPetDetail(context, data.characterUuid)), tint = data.mood.tint()) {
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PetSprite(bitmap, 56.dp)
                Spacer(GlanceModifier.width(10.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = data.petName,
                        maxLines = 1,
                        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    )
                    Text(
                        text = context.getString(data.mood.labelRes()),
                        maxLines = 1,
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                    )
                }
                Spacer(GlanceModifier.width(8.dp))
                PetActionArea(context, data)
            }
            Spacer(GlanceModifier.height(12.dp))
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // 饥饿条显示饱食度（100 - hunger），1:1 iOS。
                MiniStatBar("🍖", 100 - data.hunger, COLOR_ORANGE, GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(10.dp))
                MiniStatBar("❤️", data.happiness, COLOR_PINK, GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(10.dp))
                MiniStatBar("💧", data.cleanliness, COLOR_CYAN, GlanceModifier.defaultWeight())
            }
        }
    }
}

@Composable
private fun PetActionArea(context: Context, data: PetWidgetData) {
    if (data.isWalking) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🚶", style = TextStyle(fontSize = 16.sp))
            Text(
                text = context.getString(R.string.pet_widget_walking_now),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 9.sp),
            )
        }
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ActionButton("🍖", actionStartActivity(PetWidgetIntents.openPetAction(context, data.characterUuid, PetWidgetIntents.ACTION_FEED)))
            Spacer(GlanceModifier.height(6.dp))
            ActionButton("🤚", actionStartActivity(PetWidgetIntents.openPetAction(context, data.characterUuid, PetWidgetIntents.ACTION_PET)))
        }
    }
}

// MARK: - 无宠物

@Composable
private fun NoPetContent(context: Context) {
    Box(
        modifier = GlanceModifier.fillMaxSize().appWidgetBackground().background(GlanceTheme.colors.widgetBackground).cornerRadius(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🐾", style = TextStyle(fontSize = 26.sp, textAlign = TextAlign.Center))
            Spacer(GlanceModifier.height(4.dp))
            Text(
                text = context.getString(R.string.pet_widget_no_pet),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
            )
        }
    }
}

// MARK: - 复用片段

/** 主题底色 + 半透明心情色调层 + 整块点击区（深浅皆可读）。 */
@Composable
private fun WidgetSurface(onBodyClick: Action, tint: Color, content: @Composable () -> Unit) {
    Box(
        modifier = GlanceModifier.fillMaxSize().appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground).cornerRadius(16.dp).clickable(onBodyClick),
    ) {
        Box(GlanceModifier.fillMaxSize().background(tint)) {}
        content()
    }
}

@Composable
private fun PetSprite(bitmap: Bitmap?, size: Dp) {
    if (bitmap != null) {
        Image(provider = ImageProvider(bitmap), contentDescription = null, modifier = GlanceModifier.size(size))
    } else {
        Text("🐾", style = TextStyle(fontSize = 30.sp, textAlign = TextAlign.Center))
    }
}

@Composable
private fun ActionButton(emoji: String, onClick: Action) {
    Box(
        modifier = GlanceModifier.size(32.dp).cornerRadius(16.dp).background(GlanceTheme.colors.secondaryContainer).clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, style = TextStyle(fontSize = 15.sp))
    }
}

@Composable
private fun MiniStatBar(emoji: String, value: Int, color: Color, modifier: GlanceModifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, style = TextStyle(fontSize = 9.sp))
        Spacer(GlanceModifier.width(3.dp))
        LinearProgressIndicator(
            progress = value.coerceIn(0, 100) / 100f,
            modifier = GlanceModifier.defaultWeight().height(5.dp),
            color = ColorProvider(color),
            backgroundColor = ColorProvider(color.copy(alpha = 0.2f)),
        )
    }
}

private val COLOR_ORANGE = Color(0xFFFF9500)
private val COLOR_PINK = Color(0xFFFF2D55)
private val COLOR_CYAN = Color(0xFF00C7BE)

/** 心情半透明色调（叠主题底色上）。色相对齐 iOS moodGradientColors：散步/开心暖桃、饿/脏琥珀、不开心灰蓝、安静蓝。 */
private fun PetWidgetMood.tint(): Color = when (this) {
    PetWidgetMood.WALKING, PetWidgetMood.HAPPY -> Color(0x33FF8A65)
    PetWidgetMood.HUNGRY, PetWidgetMood.DIRTY -> Color(0x33FFB300)
    PetWidgetMood.SAD -> Color(0x335C6BC0)
    PetWidgetMood.CALM -> Color(0x334F9BFF)
}

/** 心情文案资源（1:1 iOS moodText 六态）。 */
private fun PetWidgetMood.labelRes(): Int = when (this) {
    PetWidgetMood.WALKING -> R.string.pet_widget_mood_walking
    PetWidgetMood.HUNGRY -> R.string.pet_widget_mood_hungry
    PetWidgetMood.DIRTY -> R.string.pet_widget_mood_dirty
    PetWidgetMood.HAPPY -> R.string.pet_widget_mood_happy
    PetWidgetMood.SAD -> R.string.pet_widget_mood_sad
    PetWidgetMood.CALM -> R.string.pet_widget_mood_calm
}
