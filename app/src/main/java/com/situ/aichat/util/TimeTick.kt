package com.situ.aichat.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 进程内相对时间刷新源（= iOS `Utilities/TimeTick.swift`，60s 节拍）：返回当前毫秒，每 60s 重发一次，
 * 驱动「X 分钟前 / 刚刚」等相对时间标签随时间推移自动刷新（moments-ui-10）。
 *
 * 仅在视图组合期间随生命周期运行、离屏即停（produceState 协程随组合销毁取消），比 iOS 全局常驻 Timer
 * 更省电、效果一致——只在标签可见时才跳动。复用 StoryChapterListScreen 的 produceState+delay(60_000) 习惯写法。
 *
 * 注意 1:1 范围：iOS 仅朋友圈信息流卡片 + 通知列表行读 TimeTick；详情页/评论行有意保持静态（进入时算一次），
 * 故本助手只挂到对应的两处，勿扩散到详情/评论。
 */
@Composable
fun rememberTimeTick(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000L)
        }
    }
    return now
}

/**
 * [rememberTimeTick] 的 Flow 版（供 ViewModel / 协作者 `combine` 进 StateFlow 用）：立即发一次当前毫秒、之后每
 * [periodMillis] 重发一次。用来把「墙钟推移才会变」的派生态变成**流的输入**——典型如「未来约定见面」的倒数小条
 * 到点就地变身为赴约按钮：判定读 now，但底层 Room Flow 不会因时间流逝而再发射，少了时间这个输入就只能等下次 DB
 * 写 / 重订阅才刷新（用户持续盯着聊天页时到点不变身）。
 *
 * 与 DB Flow `combine` 后挂 `stateIn(WhileSubscribed)` → 仅订阅期（界面在屏）跳动、离屏即停，省电；StateFlow 按值
 * 去重，结果没变的 tick 不触发下游重组。[periodMillis] 决定「到点变身」的最大滞后（默认 30s）——注意系统精确闹钟
 * 通知到点照常准时弹（决策①保送达），本流只驱动 App 内「就地变身」这一**辅助**态，故 30s 粒度足够、无需秒级。
 */
fun timeTickFlow(periodMillis: Long = 30_000L): Flow<Long> = flow {
    while (true) {
        emit(System.currentTimeMillis())
        delay(periodMillis)
    }
}
