package com.situ.aichat.data.repository

import com.situ.aichat.data.local.dao.PromiseDao
import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.promise.PromiseInjectionRenderer
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「我们的约定」承诺账本仓库（记忆改造一期·部件①·图纸 §3.1）：[PromiseDao] 薄包装——注入候选查询 /
 * open 查询 / 级联删。落库业务（守卫 / 去重 / 惦记桥 / 对账落库）在
 * [com.situ.aichat.promise.PromiseLedgerService]；渲染 / 排序 / 裁剪归
 * [com.situ.aichat.promise.PromiseInjectionRenderer]。
 */
@Singleton
class PromiseRepository @Inject constructor(
    private val dao: PromiseDao,
) {
    /** 某角色全部进行中约定（createdAtMillis 升序）。 */
    suspend fun openByCharacter(characterUuid: String): List<PromiseEntity> = dao.openByCharacter(characterUuid)

    /**
     * 注入候选（图纸 §3.1）：进行中约定全量 + 最近 7 天已了结约定。排序 / 软上限裁剪归
     * [com.situ.aichat.promise.PromiseInjectionRenderer]，本处只做「取全集」。
     * 取数窗与渲染器过滤窗须永远同值，故 7 天窗常量单源引用 [PromiseInjectionRenderer.RESOLVED_WINDOW_MS]
     *（data/repository 引用领域纯函数 object 常量先例：`OpenLoopRepository.revisitCandidates`）。
     */
    suspend fun injectableForCharacter(characterUuid: String, nowMillis: Long): List<PromiseEntity> {
        val open = dao.openByCharacter(characterUuid)
        val resolved = dao.resolvedSince(characterUuid, nowMillis - PromiseInjectionRenderer.RESOLVED_WINDOW_MS)
        return open + resolved
    }

    /** Flow 版进行中查询（三期 UI·资料页卡 + 账本子页数据源·图纸 §3.1）。排序单源见 [PromiseInjectionRenderer.sortedOpen]。 */
    fun observeOpenByCharacter(characterUuid: String): Flow<List<PromiseEntity>> = dao.observeOpenByCharacter(characterUuid)

    /** Flow 版某角色全部已了结（全部历史·了结时间降序·三期 UI·图纸 §3.1）。 */
    fun observeResolvedByCharacter(characterUuid: String): Flow<List<PromiseEntity>> = dao.observeResolvedByCharacter(characterUuid)

    suspend fun byUuid(uuid: String): PromiseEntity? = dao.byUuid(uuid)

    suspend fun upsert(promise: PromiseEntity) = dao.upsert(promise)

    /** 全表（备份导出用）。 */
    suspend fun getAll(): List<PromiseEntity> = dao.getAll()

    /** 角色级联删（无 FK·手动清·图纸 §3.1·E15）。 */
    suspend fun deleteByCharacter(characterUuid: String) = dao.deleteByCharacter(characterUuid)
}
