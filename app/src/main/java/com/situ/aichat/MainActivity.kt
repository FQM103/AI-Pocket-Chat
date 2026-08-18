package com.situ.aichat

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.animation.doOnEnd
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.situ.aichat.diagnostics.perf.FrameMetricsProbe
import com.situ.aichat.notification.NotificationNavigator
import com.situ.aichat.notification.Notifier
import com.situ.aichat.notification.NotifierWorld
import com.situ.aichat.world.WorldDebugEntry
import com.situ.aichat.notification.StreakNotificationBridgeService
import com.situ.aichat.share.ShareRoute
import com.situ.aichat.share.ShareRouting
import com.situ.aichat.share.ShareTargetCoordinator
import com.situ.aichat.ui.AppRoot
import com.situ.aichat.ui.AppViewModel
import com.situ.aichat.util.LocaleManager
import com.situ.aichat.widget.PetWidgetActionHandler
import com.situ.aichat.widget.PetWidgetIntents
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** 通知点击物化 + 跳转（P6.1d）。@AndroidEntryPoint 字段注入；@Singleton 实例与 ViewModel 侧同一个。 */
    @Inject lateinit var notificationBridge: StreakNotificationBridgeService

    /** 朋友圈互动通知点击 → 帖子详情跳转（决策①，P7.2.8）。@Singleton 与 AppViewModel 侧同一个。 */
    @Inject lateinit var notificationNavigator: NotificationNavigator

    /** 宠物小组件 feed/pet 操作执行（P11.3b）。@Singleton 字段注入。 */
    @Inject lateinit var petWidgetActionHandler: PetWidgetActionHandler

    /** 分享给角色（Direct Share · C3，13.10a）：把分享文本投到会话 / 暂存待联系人点选。@Singleton 字段注入。 */
    @Inject lateinit var shareTargetCoordinator: ShareTargetCoordinator

    /** 性能采集·尺 3 掉帧探针（卷 0）：要 Activity 的 window 才挂得上帧监听。采集关时全程 no-op。 */
    @Inject lateinit var frameMetricsProbe: FrameMetricsProbe

    /** P1-9+28：splash 释放门控读 [AppViewModel.splashReady]。AppRoot 的 hiltViewModel() 解析到同一
     *  Activity ViewModelStore——与组合内是同一实例，零重复创建。 */
    private val appViewModel: AppViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // P1-9+28 启动品牌：installSplashScreen 必须在 super.onCreate 之前（且 activity 主题须为
        // Theme.SplashScreen 后代，见 Theme.AIPocketChat.Starting）——12+ 桥系统 splash，API 29/30 库画 compat 启动窗。
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        // 门控：DataStore 协议/外观偏好读出才放首帧（防 LoadingGate 闪烁/主题错档）。lambda 每帧主线程求值，
        // 只读 StateFlow.value（O(1)），绝不在此做 IO。
        splashScreen.setKeepOnScreenCondition { !appViewModel.splashReady.value }
        // 退出=200ms 淡出。结束必须 provider.remove()，漏调=splash 视图常驻遮挡。reduceMotion（系统动画时长
        // 缩放=0，与 AppMotion.rememberReduceMotion 同信号）直接移除零动画——不依赖 0 时长动画的帧调度。
        // 批7 复核修：pre-31 仅冷启注册——compat 实现注册即盖整屏浮层、不判「真出现过启动窗」，配置重建
        // （旋转/深浅/换语言，本 activity 无 configChanges 全走重建）会每次重放 splash 闪屏；31+ 恒安全
        // （平台路径无真 splash 不回调）。副作用=29/30 进程死带状态重启不播淡出（系统启动窗仍在），可接受。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || savedInstanceState == null) {
            splashScreen.setOnExitAnimationListener { provider ->
                val scale = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
                if (scale == 0f) {
                    provider.remove()
                    return@setOnExitAnimationListener
                }
                ObjectAnimator.ofFloat(provider.view, View.ALPHA, 1f, 0f).apply {
                    duration = 200L
                    interpolator = LinearInterpolator()
                    doOnEnd { provider.remove() }
                    start()
                }
            }
        }
        enableEdgeToEdge()
        // 批2 2-8 / 13.10a：仅首次创建时处理启动 Intent——本 activity 无 configChanges 全走重建，无守卫时旋屏/
        // 深浅切换/进程死亡恢复会把旧 Intent 原样再投，重放通知深链或分享（旧分享文本再暂存+凭空跳回联系人点选）。
        // 新 Intent 恒经 onNewIntent，不受此闸影响（repo 记忆「深链导航两陷阱」已列此为标准解法）。
        if (savedInstanceState == null) {
            handleNotificationIntent(intent)
        }
        frameMetricsProbe.attach(window)
        setContent {
            // 主题已下沉到 AppRoot（11.4a：随 DataStore 外观偏好驱动深浅/动态取色）。
            AppRoot()
        }
    }

    override fun onDestroy() {
        frameMetricsProbe.detach()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop：通知点击不重建 Activity，由此投递新 Intent；存起来供后续读取并处理。
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    /**
     * 通知点击（深链）→ 物化成会话 assistant 消息 + 投放跳转目标（[com.situ.aichat.notification.NotificationNavigator]
     * → [com.situ.aichat.ui.AIChatApp] 导航到 `chat/{uuid}`）。非通知点击（普通启动）静默忽略。
     */
    private fun handleNotificationIntent(intent: Intent?) {
        // 分享给角色（Direct Share · C3，13.10a）：从外部 App 分享 text/plain 进来 → 投会话直接发 / 跳联系人点选。
        // 放最前：action=SEND 与下面各通知/快捷方式 action 互斥，命中即处理返回。
        if (handleShareIntent(intent)) return
        // 朋友圈互动通知（决策①）/「X 发了新动态」单帖（13.7e）：直接投放帖子详情跳转目标，不走会话物化。
        val momentUuid = Notifier.momentClickUuidFrom(intent)
        if (momentUuid != null) {
            notificationNavigator.requestMoment(momentUuid)
            return
        }
        // 朋友圈「N 位好友发了新动态」合并通知（13.7e）：投放朋友圈 feed 跳转目标（无单帖 uuid）。
        if (Notifier.isMomentsFeedClick(intent)) {
            notificationNavigator.requestMomentsFeed()
            return
        }
        // 宠物小组件 feed/pet 操作（P11.3b）：执行护理动作，不跳转（1:1 iOS pet-action），小组件随后自刷新。
        val petAction = intent?.getStringExtra(PetWidgetIntents.EXTRA_PET_ACTION)
        val petActionCharacter = intent?.getStringExtra(PetWidgetIntents.EXTRA_PET_CHARACTER)
        if (petAction != null && petActionCharacter != null) {
            lifecycleScope.launch { petWidgetActionHandler.perform(petActionCharacter, petAction) }
            return
        }
        // 宠物小组件整块点击（P11.3a）：投放宠物详情跳转目标。
        val petUuid = intent?.getStringExtra(PetWidgetIntents.EXTRA_OPEN_PET_DETAIL)
        if (petUuid != null) {
            notificationNavigator.requestPet(petUuid)
            return
        }
        // 会话快捷方式（C2 图标长按）：纯导航到会话，不走通知物化（无待物化通知）。
        val shortcutConversation = Notifier.conversationShortcutUuidFrom(intent)
        if (shortcutConversation != null) {
            notificationNavigator.request(shortcutConversation)
            return
        }
        // 快捷设置磁贴（QS Tile · C7，13.10c「找角色」）：纯导航到联系人 Tab。
        if (Notifier.isOpenContactsClick(intent)) {
            notificationNavigator.requestContacts()
            return
        }
        // 世界通知（W9a·W8 挂账落地）：纯导航到世界屏（无物化）。
        if (intent?.action == NotifierWorld.ACTION_OPEN_WORLD) {
            notificationNavigator.requestWorld()
            return
        }
        // W10 debug 直达（效率契约·**仅 DEBUG**·adb --es debug_world "starmap|continent:<r>|town:<c>|interior:<c>:<p>"）。
        // 沿用本方法既有「Build.VERSION/savedInstanceState==null」防重放调用条件（onCreate/onNewIntent）；release 短路。
        if (BuildConfig.DEBUG) {
            intent?.getStringExtra("debug_world")?.let {
                WorldDebugEntry.pending = it
                notificationNavigator.requestWorld()
                return
            }
        }
        // 关系里程碑庆祝通知（P1-33）：纯导航到该角色资料页（关系历程卡在页内）。
        val profileUuid = Notifier.characterProfileClickUuidFrom(intent)
        if (profileUuid != null) {
            notificationNavigator.requestCharacterProfile(profileUuid)
            return
        }
        // 自动备份通知（P15·P0-19）：纯导航到备份设置；失败/目录丢失（focusFolder=true）进页自动开目录选择器。
        Notifier.backupClickFocusFolderFrom(intent)?.let { focusFolder ->
            notificationNavigator.requestBackup(focusFolder)
            return
        }
        // 故事章节解锁/完成/失败通知（U4·11.1g）：纯导航到该故事详情（此前 storyClickIdFrom 无消费方=死链落空首屏）。
        val storyId = Notifier.storyClickIdFrom(intent)
        if (storyId != null) {
            notificationNavigator.requestStory(storyId)
            return
        }
        // Phase 10 未来约定见面到点：回会话 + 自动赴约（进线下见面沉浸）。会话 uuid 缺失（理论不会）→ 不处理交后续兜底。
        val meetupArrival = Notifier.meetupArrivalClickFrom(intent)
        if (meetupArrival?.conversationUuid != null) {
            notificationNavigator.requestMeetupArrival(meetupArrival.conversationUuid, meetupArrival.appointmentUuid)
            return
        }
        val payload = Notifier.clickPayloadFrom(intent) ?: return
        lifecycleScope.launch { notificationBridge.materializeFromClick(payload) }
    }

    /**
     * 分享给角色（Direct Share · C3，13.10a）：处理外部 App 的 `ACTION_SEND` text/plain。返回是否已处理（命中即拦下）。
     *
     * 系统在用户选了某个角色行时会带上被选中的快捷方式 id（[ShortcutManagerCompat.EXTRA_SHORTCUT_ID] = 会话 uuid）：
     * - [ShareRoute.Direct]：直接投到该会话（后台落消息 + 跑一轮 LLM 回复，复用 B5/B1 管线）+ 跳进会话看回复。
     * - [ShareRoute.Picker]：选了 App 通用入口（无快捷方式 id）→ 暂存文本，App 根跳联系人让用户点选收件角色。
     * - [ShareRoute.Ignore]：空白文本 → 不处理（返回 false，交后续分支/默认启动）。
     */
    private fun handleShareIntent(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_SEND) return false
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val shortcutId = intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID)
        val route = ShareRouting.decide(text, shortcutId)
        // 观测点（用户数据入口·丢失即分享内容蒸发）：只打路由与长度，绝不打分享内容本身。
        Log.d(
            "ShareTarget",
            "分享进入 route=${route::class.simpleName} textLen=${text?.length ?: 0} hasShortcut=${shortcutId != null}",
        )
        return when (route) {
            is ShareRoute.Direct -> {
                // 投递前校验会话仍存在（复核 MED）：残留的 long-lived 快捷方式可能指向已删会话 → 投不出去就退回
                // 联系人点选（绝不静默丢弃）。deliverToConversation 是 suspend，故起协程。
                lifecycleScope.launch {
                    if (shareTargetCoordinator.deliverToConversation(route.conversationUuid, route.text)) {
                        notificationNavigator.request(route.conversationUuid)
                    } else {
                        shareTargetCoordinator.stashForPicker(route.text)
                        notificationNavigator.requestContacts()
                    }
                }
                true
            }
            is ShareRoute.Picker -> {
                shareTargetCoordinator.stashForPicker(route.text)
                notificationNavigator.requestContacts()
                true
            }
            ShareRoute.Ignore -> false
        }
    }
}
