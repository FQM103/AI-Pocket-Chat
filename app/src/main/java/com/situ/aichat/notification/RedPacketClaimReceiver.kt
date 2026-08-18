package com.situ.aichat.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.situ.aichat.data.model.RedPacketStatus
import com.situ.aichat.redpacket.RedPacketError
import com.situ.aichat.redpacket.RedPacketExpirationScanService
import com.situ.aichat.redpacket.RedPacketService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 13.8·B2 红包「领取」接收者（**安卓超越 iOS**：iOS 红包预警通知点击只跳会话、需进 App 手拆）。点通知里的「领取」按钮
 * → 触发**既有** [RedPacketService.acceptRedPacket]（幂等·单 DB 事务·绝不新增钱算路径）+ 取消 22h 预警闹钟，
 * 镜像 [com.situ.aichat.ui.chat.ChatViewModel.openRedPacket]。manifest exported=false（仅本应用动作 PI 能触发）。
 *
 * 💰 钱铁律：本接收者**零钱算**——只作既有 acceptRedPacket 的薄壳。acceptRedPacket 在单事务内 `guardPending` →
 * 二次/并发点击、或拆后已过期退回，均抛 [RedPacketError]（[RedPacketError.AlreadyResolved]），**不可能双倍到账**。
 * 捕获作 benign：已领（双击/已在 App 内拆）仍显「已领取」；已退回 / 记录缺失则只撤通知、不显误导信息。
 *
 * onReceive 须轻量；acceptRedPacket 是 suspend（DB 事务），故 [goAsync] 撑过 onReceive 同步窗口后在 IO 协程完成。
 * App 可能已被杀、由系统拉起本进程（Hilt 已在 Application.onCreate 初始化）→ [EntryPointAccessors] 取 @Singleton 服务。
 */
class RedPacketClaimReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ClaimEntryPoint {
        fun redPacketService(): RedPacketService
        fun redPacketExpirationScanService(): RedPacketExpirationScanService
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Notifier.ACTION_CLAIM_RED_PACKET) return
        val recordUuid = intent.getStringExtra(Notifier.EXTRA_RED_PACKET_UUID)?.takeIf { it.isNotBlank() } ?: return
        val conversationUuid = intent.getStringExtra(Notifier.EXTRA_CLAIM_CONVERSATION)
        val notificationId = intent.getIntExtra(Notifier.EXTRA_CLAIM_NOTIF_ID, 0)
        val appContext = context.applicationContext
        val entry = EntryPointAccessors.fromApplication(appContext, ClaimEntryPoint::class.java)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                entry.redPacketService().acceptRedPacket(recordUuid) // 💰 既有领取逻辑（幂等转账），零新增钱算
                entry.redPacketExpirationScanService().cancelWarningAlarm(recordUuid)
                Notifier.postRedPacketClaimed(appContext, notificationId, conversationUuid)
            } catch (e: RedPacketError) {
                entry.redPacketExpirationScanService().cancelWarningAlarm(recordUuid)
                if ((e as? RedPacketError.AlreadyResolved)?.status == RedPacketStatus.ACCEPTED) {
                    Notifier.postRedPacketClaimed(appContext, notificationId, conversationUuid) // 已领（双击/已在 App 拆）
                } else {
                    NotificationManagerCompat.from(appContext).cancel(notificationId) // 已退回 / 记录缺失：撤通知不显误导信息
                }
                Log.w(TAG, "红包领取已解决 $recordUuid: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "红包领取失败 $recordUuid（保留通知可重试）", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "RedPacketClaimRcv"
    }
}
