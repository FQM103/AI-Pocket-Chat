package com.situ.aichat.data.backup

import com.situ.aichat.data.local.dao.UserStoryTemplateDao
import com.situ.aichat.data.local.entity.UserStoryTemplateEntity
import kotlinx.serialization.Serializable

/**
 * 「我的模板」表备份（图纸四 §3.2·逐字照 [PromiseExport] 六件范式）：**顶层全局段**，整体恢复一次。
 *
 * 无幽灵过滤——模板不挂角色也不挂故事（无外键），整段直接搬回；uuid 原样保留 → 再导入按 uuid REPLACE 幂等。
 * 旧版备份（无此段）导入后 rows 空 = 没有模板（E11），内置 12 套模板不受影响。
 * [payloadJson] 原样往返：即便产出它的版本比导入端新（多几个键），decode 端 `ignoreUnknownKeys` 自愈。
 */
@Serializable
data class UserStoryTemplateExport(
    val uuid: String = "",
    val name: String = "",
    val createdAt: Long = 0L,
    val payloadJson: String = "",
)

internal fun UserStoryTemplateEntity.toExport() = UserStoryTemplateExport(uuid, name, createdAt, payloadJson)

internal fun UserStoryTemplateExport.toEntity() = UserStoryTemplateEntity(uuid, name, createdAt, payloadJson)

/** 导出采集（Exporter 全局段之一）。 */
internal suspend fun collectUserStoryTemplates(dao: UserStoryTemplateDao): List<UserStoryTemplateExport>? =
    dao.getAll().map { it.toExport() }.ifEmpty { null }

/** 恢复（Importer 事务内·uuid REPLACE 幂等·无幽灵过滤）。 */
internal suspend fun restoreUserStoryTemplates(
    dao: UserStoryTemplateDao,
    data: List<UserStoryTemplateExport>?,
) {
    data?.forEach { dao.insert(it.toEntity()) }
}
