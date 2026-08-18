package com.situ.aichat.shortcut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.situ.aichat.R
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.notification.Notifier
import com.situ.aichat.share.ShareRouting
import com.situ.aichat.ui.components.AvatarColor
import com.situ.aichat.util.AvatarStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 对话快捷方式发布器（C2 · 13.5 chat-ui-11 机会点，安卓超越 iOS 的系统级入口）：
 * 在 App 级一处观察会话+角色流，把「最近几个有消息的会话」推成**动态快捷方式**——长按桌面 App 图标即弹出
 * 最近角色、一点直达会话（iOS 无等价能力）。
 *
 * **零新依赖 / 零新导航**：`ShortcutManagerCompat` 在已依赖的 androidx.core 里；跳转复用现有会话深链通道
 * （[Notifier.conversationShortcutIntent] → [com.situ.aichat.MainActivity] → [com.situ.aichat.notification.NotificationNavigator]
 * → 导航 `chat/{uuid}`），但走独立 action **纯导航不物化**。
 *
 * **13.10a 起兼作 Direct Share 目标（C3·安卓超越 iOS）**：每个快捷方式额外挂 [ShareRouting.SHARE_CATEGORY]（与
 * `res/xml/shortcuts.xml` 的 `<share-target>` 对应）+ [Person]（角色名/头像，供系统分享面板排序与显示）+ `setLongLived(true)`
 * （Direct Share / 悬浮气泡要求）→ 从任意 App 分享 text/plain 时这些角色出现在分享面板，选一个即把文字发过去
 * （投递逻辑见 [com.situ.aichat.share.ShareTargetCoordinator]）。
 *
 * 仿 [com.situ.aichat.widget.PetWidgetSync]：App 级一处观察自动覆盖所有会话写路径，数据层不反依赖本层（保持分层）。
 * 由 [com.situ.aichat.ui.AppViewModel] 在 init 调 [start] 一次。
 */
@Singleton
class ConversationShortcutPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val conversationRepo: ConversationRepository,
    private val characterRepo: CharacterRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false

    /** 幂等启动；由 [com.situ.aichat.ui.AppViewModel] 在 init 调用一次。 */
    fun start() {
        if (started) return
        started = true
        // 动态快捷方式数受系统上限约束（通常 5，与 manifest/pinned 共享预算），保守取 ≤4。
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceIn(1, MAX_SHORTCUTS)
        scope.launch {
            combine(conversationRepo.observeActive(), characterRepo.observeAll()) { convs, chars ->
                val byUuid = chars.associateBy { it.uuid }
                // 与列表口径一致：仅有消息的会话；DAO 已按「置顶优先→最近活动」序，取前 max。
                convs.asSequence()
                    .filter { it.lastMessageDate != null }
                    .take(max)
                    .map { conv ->
                        val character = byUuid[conv.characterUuid]
                        ShortcutRow(
                            conversationUuid = conv.uuid,
                            // 兜底非空（复核 LOW）：角色名与会话标题都空（如损坏备份导入的空 title）时给个占位，
                            // 避免快捷方式标签 / Direct Share Person 名显示成空白。
                            name = character?.name?.takeIf { it.isNotBlank() }
                                ?: conv.title.takeIf { it.isNotBlank() }
                                ?: context.getString(R.string.shortcut_unnamed_conversation),
                            avatarPath = character?.avatarPath,
                        )
                    }
                    .toList()
            }
                // 仅当「可见会话集 / 名 / 头像 / 顺序」变化才重发（每条新消息不重建图标，避免桌面闪烁）。
                .distinctUntilChanged()
                .collect { rows -> publish(rows) }
        }
    }

    private suspend fun publish(rows: List<ShortcutRow>) {
        val shortcuts = rows.mapIndexed { index, row ->
            val icon = iconFor(row)
            // Direct Share（13.10a）：Person 让分享面板按角色名/头像显示与排序；key 用会话 uuid（系统选中后回传）。
            val person = Person.Builder()
                .setName(row.name)
                .setKey(row.conversationUuid)
                .setIcon(icon)
                .build()
            ShortcutInfoCompat.Builder(context, row.conversationUuid)
                .setShortLabel(row.name)
                .setLongLabel(row.name)
                .setRank(index)
                .setLongLived(true)
                // 绑定为分享目标（与 shortcuts.xml 的 <share-target> 类目一致）；少了系统不把它当 Direct Share 候选。
                .setCategories(setOf(ShareRouting.SHARE_CATEGORY))
                .setPerson(person)
                .setIcon(icon)
                .setIntent(Notifier.conversationShortcutIntent(context, row.conversationUuid))
                .build()
        }
        // setDynamicShortcuts 整组替换 → 已删/已归档会话自动从列表消失。best-effort（部分国行桌面可能折扣）。
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    private suspend fun iconFor(row: ShortcutRow): IconCompat {
        val bitmap = AvatarStore.load(row.avatarPath) ?: monogram(row.name)
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }

    /** 无头像时按名生成首字母字母图（复用 [AvatarColor] 的稳定取色，对齐头像 monogram 兜底观感）。 */
    private fun monogram(name: String): Bitmap {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(AvatarColor.color(name).toArgb())
        val letter = name.trim().take(1).uppercase().ifEmpty { "·" }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.42f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val baseline = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(letter, size / 2f, baseline, paint)
        return bmp
    }

    private data class ShortcutRow(
        val conversationUuid: String,
        val name: String,
        val avatarPath: String?,
    )

    private companion object {
        const val MAX_SHORTCUTS = 4
    }
}
