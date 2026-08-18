package com.situ.aichat.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进程级网络连通监测（P15·P0-2）。1:1 移植 iOS `NetworkMonitorService`（NWPathMonitor）：
 * - [isConnected] 默认 true（与 iOS 一致，避免冷启动一闪「离线」误报）。
 * - [statusChanged]：`null`=无变化 / `true`=刚恢复 / `false`=刚断开；**首条回调不置**（[hasReceivedInitial] 抑制，
 *   等价 iOS hasReceivedInitialStatus）。
 * - [clearStatusChange]：UI 显示「已恢复」绿条 2s 后清。
 *
 * 用 `ConnectivityManager.registerNetworkCallback` + INTERNET capability（**不要求 VALIDATED**，避免 captive-portal/
 * 慢校验时误报离线而阻断发送，对齐 iOS `.satisfied` 的宽松语义）。回调来自 binder 线程，[applyState] 同步化。
 * @Singleton 全进程仅一个回调（过多注册会抛，单例规避）。
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val _isConnected = MutableStateFlow(true)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _statusChanged = MutableStateFlow<Boolean?>(null)
    val statusChanged: StateFlow<Boolean?> = _statusChanged.asStateFlow()

    private var hasReceivedInitial = false
    private val networks = Collections.synchronizedSet(mutableSetOf<Network>())

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { networks.add(network); applyState() }
        override fun onLost(network: Network) { networks.remove(network); applyState() }
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm?.registerNetworkCallback(request, callback) }
            .onFailure { Log.e(TAG, "registerNetworkCallback 失败,离线检测可能失效", it) }
        // R4#4：注册后主动探一次当前网络喂初值。完全离线冷启时 onAvailable 永不触发（它只对「已/新可用」网络发，
        // onLost 也只对曾可用的网络发），isConnected 会永远停在默认 true → 离线横幅与发送阻断在该场景失效。
        // 在线则把当前 INTERNET 网络播种进 networks 集（与回调最终态一致，避免首条 applyState 误判后再被回调翻成
        // 「已恢复」绿条）；离线则 applyState 直接落 false。首条经 applyState 不置 statusChanged（hasReceivedInitial 抑制）。
        runCatching {
            val active = cm?.activeNetwork
            val online = active != null &&
                cm.getNetworkCapabilities(active)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            if (online) networks.add(active)
        }
        applyState()
    }

    @Synchronized
    private fun applyState() {
        val (newConnected, delta) = nextNetworkState(_isConnected.value, hasReceivedInitial, networks.isNotEmpty())
        hasReceivedInitial = true
        _isConnected.value = newConnected
        if (delta != null) {
            _statusChanged.value = delta
            // 连通状态迁移升 Log.i：release 也可见 connect/disconnect（P15·诊断可观测）。
            Log.i(TAG, if (delta) "网络已恢复" else "网络已断开")
        }
    }

    /** UI 消费完「已恢复」绿条（2s 后）调用，复位一次性信号（对齐 iOS clearStatusChange）。 */
    fun clearStatusChange() {
        _statusChanged.value = null
    }

    private companion object {
        const val TAG = "NetworkMonitor"
    }
}

/**
 * 网络状态迁移纯函数（P0-2，单测覆盖）。1:1 iOS `NetworkMonitorService.pathUpdateHandler` 逻辑：
 * 返回 `(新 isConnected, statusChanged 增量)`；增量 `null` 表示不改 statusChanged。
 * 首条回调（`!hasReceivedInitial`）永不置 statusChanged；与上次相同且已收过首条 → 无变化（早退）。
 */
internal fun nextNetworkState(
    prevConnected: Boolean,
    hasReceivedInitial: Boolean,
    nowConnected: Boolean,
): Pair<Boolean, Boolean?> {
    if (nowConnected == prevConnected && hasReceivedInitial) return prevConnected to null
    val changed = if (hasReceivedInitial) nowConnected else null
    return nowConnected to changed
}
