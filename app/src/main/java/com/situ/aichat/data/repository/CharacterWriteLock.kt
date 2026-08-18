package com.situ.aichat.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 每角色一把写锁（P12.6 移植欠账 D1：角色数据并发覆盖竞态）。
 *
 * **背景**：iOS 在单线程上逐属性改 `@Model`，天然不打架；安卓曾「读快照 → copy(改几列) → 整行写」，AI 回完一条后
 * 成长/关系/结构化分析回写 + 各路每轮计数器递增并发跑，后写者用旧快照把别人刚写的列覆盖回旧值 → 偶发悄无声息丢更新。
 *
 * **本锁的作用**：把对「同一个角色行」的「读-改-写」串行化。所有并发改角色的路径都在 [withCharacterLock] 内执行，
 * 配合各写点改用列级 `@Query UPDATE`（只写自己那几列），跨列覆盖 + 同列读改写两类竞态全消。按 uuid 隔离，不同角色互不阻塞。
 *
 * **为何是进程级单例**：旧实现 ChatViewModel 与 VoiceCallPostReplyRounds 各持一把 `characterMetaMutex`、互不串行；
 * 本锁 @Singleton 全进程共用一把（按 uuid），聊天/语音/各协调器之间也真正串行。
 *
 * **用法约束**：锁内必须「重新读最新角色」再算再写（不要用进入锁前的旧快照），否则仍会基于陈旧值。允许在锁内做较慢的
 * LLM 调用（背景分析），代价是该角色的计数器递增会等这几秒——对单用户本地 App 可接受，且更贴 iOS 全串行语义。
 * **不可重入**：同一协程在持锁期间不要再调用 [withCharacterLock]（各协调器/计数器各自独立持锁、链式触发在释放后进行，无嵌套）。
 */
@Singleton
class CharacterWriteLock @Inject constructor() {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withCharacterLock(uuid: String, block: suspend () -> T): T =
        locks.computeIfAbsent(uuid) { Mutex() }.withLock { block() }
}
