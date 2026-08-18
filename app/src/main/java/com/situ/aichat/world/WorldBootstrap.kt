package com.situ.aichat.world

import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.world.cast.WorldResidentService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 静默建世唯一口（W9a 图纸 §3.1·决策 34③）：首次进世界屏时懒建世界单行——随机种子**一次固定**、时区暂
 * null（跟设备·[WorldClock.resolveZone] 回退系统时区）、家乡默认云野镇（[WorldStateEntity] 默认值）。
 *
 * [Mutex] 串行防并发双建（E8）；已有 state 恒复用（重进不重建·seed 不变·E9）。只在进屏时调（VM init·IO）——
 * app 冷启零税（契约 §17）。建世后**不**主动跑 WorldLinkRunner（它挂 app 回前台自然接管·本块零碰 W1–W8）。
 */
@Singleton
class WorldBootstrap @Inject constructor(
    private val worldDao: WorldDao,
    private val residentService: WorldResidentService,
) {
    private val mutex = Mutex()

    /**
     * 首建世界单行（恒不覆盖已存行·W14 图纸 §3.1 种子竞态修）：`insert-if-absent + 重读`——若建世盲写窗内
     * （getState 读到 null 与写种子之间）备份导入恰落了世界行，IGNORE 保它不被本机新种子换掉、重读拿回的就是备份行。
     * 正常首建路径行为逐位不变（空表 → insert 成功 → 重读同一行）。竞态另一半（导入后 ensureCreated 才跑）天然安全：
     * getState 非 null 直接返回、不进 run 块。
     */
    suspend fun ensureCreated(nowMs: Long): WorldStateEntity {
        val state = mutex.withLock {
            worldDao.getState() ?: run {
                worldDao.insertStateIfAbsent(
                    WorldStateEntity(
                        seed = Random.nextLong(),   // 一次随机·永久固定（一期无重开/换种子·契约 §3）
                        userTimezoneId = null,      // 暂跟设备·W13 引导再补设
                        createdAt = nowMs,
                    ),
                )
                worldDao.getState()!!   // 重读：竞态期间导入已落备份行 → 拿到备份行（IGNORE 保它不被覆盖）
            }
        }
        // 战役 B（图纸 §3.3）：把用户自建居民装入花名册——在 ensureSeeded 之前（进世界屏必经此点·幂等·≤50 行）。
        residentService.loadIntoRoster()
        return state
    }
}
