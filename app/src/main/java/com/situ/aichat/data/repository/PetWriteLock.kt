package com.situ.aichat.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 每只宠物一把写锁（P12.6 移植欠账 D1d：宠物「批量维护」↔「护理」整行覆盖竞态）。同 [CharacterWriteLock] 思路。
 *
 * **背景**：iOS 在单线程逐属性改 `@Model`，天然不打架；安卓曾「读快照 → copy(改几列) → 整行写」，回前台批量维护
 * （惰性衰减/进化）与用户喂养/清洁/玩耍/治疗/搜寻/散步结算/库存使用/装扮/购买/改名并发时，后写者用旧快照把别人
 * 刚写的列覆盖回旧值 → 极窄窗口内偶发「刚喂的状态闪回」。
 *
 * **本锁的作用**：把对「同一宠物行」的「读-改-写」串行化。**所有**并发改宠物的路径都在 [withPetLock] 内执行，
 * 且锁内**重新读最新宠物**再算再写（不要用进入锁前的旧快照）；锁内串行后，整行 `upsert` 写回也不会丢更新。
 * 按 pet uuid 隔离，不同宠物互不阻塞。新建（领养）是全新 uuid、不参与竞态，无需进锁。
 *
 * **不可重入**：同一协程持锁期间不要再调用 [withPetLock]（各写点各自独立持锁、不嵌套）。涉及事务的购买路径按
 * 「先 Mutex 再 Room 事务」固定序（与 D1b 一致，杜绝锁序反转死锁）。
 */
@Singleton
class PetWriteLock @Inject constructor() {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withPetLock(uuid: String, block: suspend () -> T): T =
        locks.computeIfAbsent(uuid) { Mutex() }.withLock { block() }
}
