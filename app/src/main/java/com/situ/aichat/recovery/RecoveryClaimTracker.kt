package com.situ.aichat.recovery

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 未答恢复的**进程级、按会话**占坑锁（复核 HIGH#1）。补 iOS `ChatViewModelStore` 交接缺失留下的互斥缺口：
 * 后台扫描 [UnansweredMessageRecoveryService] 与聊天页内 autoRecover 两条路径都要为同一对话补发回复，若不协调，
 * 后台正在跑 LLM（数秒）时用户恰好进入该对话、聊天页 autoRecover 600ms 后又起一回合 → 同一条未回复消息被双答。
 *
 * 两路都先 [tryBegin]（原子占坑），失败即让位、不重复补发；完成后 [end]。后台扫描另带「跳过活跃对话」一层，
 * 二者合起来覆盖两个方向。@Singleton + @Synchronized 跨线程安全（后台 Worker 线程 vs VM 主线程）。
 */
@Singleton
class RecoveryClaimTracker @Inject constructor() {

    private val claimed = HashSet<String>()

    /** 原子占坑：未占 → 占并返回 true；已被另一路占 → false（调用方让位）。 */
    @Synchronized
    fun tryBegin(conversationUuid: String): Boolean = claimed.add(conversationUuid)

    /** 释放占坑（finally 调）。 */
    @Synchronized
    fun end(conversationUuid: String) {
        claimed.remove(conversationUuid)
    }
}
