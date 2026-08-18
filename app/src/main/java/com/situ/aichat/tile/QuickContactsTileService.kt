package com.situ.aichat.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.situ.aichat.MainActivity
import com.situ.aichat.R
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.notification.Notifier
import com.situ.aichat.widget.CharacterStatusWidgetData
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 快捷设置「活」磁贴（C7 · 13.10c 启动型 → P1-27 升级）：label=最近角色名（置顶优先 → 最近活动，
 * 与桌面小组件/动态快捷方式同口径 [CharacterStatusWidgetData.pickConversation]），点按直达该会话
 * （[Notifier.conversationShortcutIntent] 纯导航不物化）；无任何有消息会话时回退现行「找角色」→ 联系人 Tab。
 *
 * iOS 没有第三方快捷设置磁贴等价能力（只有灵动岛语音键）→ 纯安卓超越。
 * 图标保持 manifest 的 app glyph（QS 位图在 HyperOS 渲染差，不用头像位图——计划 §10#6 裁定）。
 *
 * 竞态取舍：onStartListening 刚触发、Room 异步读未回时被点 → 走联系人 fallback（窗口=一次小表读，毫秒级）。
 * 绝不为消竞态在 onClick 里 runBlocking 主线程（§10#6 红线）或 launch 后延迟收起面板（悬置体验差）。
 */
class QuickContactsTileService : TileService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun conversationRepository(): ConversationRepository
        fun characterRepository(): CharacterRepository
    }

    /** TileService 无 lifecycleScope（直接继承 Service）→ 自管 scope（仿 VoiceCallService），onDestroy 兜底取消。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null

    /** 最近一次解析出的主对话 uuid；null = 无有消息会话 → onClick 走联系人 Tab fallback（现行为）。 */
    private var targetConversationUuid: String? = null

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE // 启动型磁贴无开关态；先同步铺默认 label 防 ROM 首帧空白
            label = getString(R.string.qs_tile_contacts_label)
            updateTile()
        }
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
            val conv = CharacterStatusWidgetData.pickConversation(deps.conversationRepository().activeSnapshot())
            targetConversationUuid = conv?.uuid
            val name = conv?.let { tileLabel(deps.characterRepository().get(it.characterUuid)?.name, it.title) }
            qsTile?.apply { // 协程回来面板可能已收起 → qsTile=null 安全跳过（updateTile 只能在 listening 期调）
                label = name ?: getString(R.string.qs_tile_contacts_label)
                updateTile()
            }
        }
    }

    override fun onStopListening() {
        refreshJob?.cancel()
        refreshJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @Suppress("DEPRECATION") // startActivityAndCollapse(Intent) 在 API 34 弃用 → 34+ 走 PendingIntent 重载，<34 走旧重载
    override fun onClick() {
        super.onClick()
        val uuid = targetConversationUuid
        val intent = if (uuid != null) {
            Notifier.conversationShortcutIntent(this, uuid) // 自带 NEW_TASK|CLEAR_TOP，满足 collapse 要求
        } else {
            Intent(this, MainActivity::class.java).apply {
                action = Notifier.ACTION_OPEN_CONTACTS
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // 两 action 不同 → PendingIntent filterEquals 各自独立；同 action 仅 extras 换会话 →
            // FLAG_UPDATE_CURRENT 恰好刷成最新目标（磁贴永远只指「当前最近」）。
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            startActivityAndCollapse(intent)
        }
    }
}

/**
 * 磁贴 label 解析（P1-27）：角色名（非空白）→ 会话标题（非空白）→ null（调用方回退默认「找角色」label）。
 * 与 ConversationShortcutPublisher 同序兜底；末档有意微分叉——快捷方式末档用「未命名会话」串，磁贴空标签
 * 观感更差，回退默认 label 更稳。internal 顶层便于单测。
 */
internal fun tileLabel(characterName: String?, conversationTitle: String): String? =
    characterName?.takeIf { it.isNotBlank() } ?: conversationTitle.takeIf { it.isNotBlank() }
