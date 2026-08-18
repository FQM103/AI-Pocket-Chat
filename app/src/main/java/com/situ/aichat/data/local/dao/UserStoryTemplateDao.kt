package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.situ.aichat.data.local.entity.UserStoryTemplateEntity
import kotlinx.coroutines.flow.Flow

/**
 * 「我的模板」数据访问（图纸四 §3.2）。只做 CRUD：抽取/装配纯逻辑在
 * [com.situ.aichat.data.model.UserStoryTemplatePayload]，存入业务在
 * [com.situ.aichat.ui.story.StorySettingsViewModel]，读取在模板墙 VM。
 */
@Dao
interface UserStoryTemplateDao {

    /** 存入（uuid REPLACE 幂等·同时服务备份恢复）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(template: UserStoryTemplateEntity)

    /** 模板墙数据源：新存的排前面。 */
    @Query("SELECT * FROM user_story_templates ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<UserStoryTemplateEntity>>

    /** 全表（备份导出用）。 */
    @Query("SELECT * FROM user_story_templates")
    suspend fun getAll(): List<UserStoryTemplateEntity>

    /** 按 uuid 查（开书 / 改一改再开的取件口）。 */
    @Query("SELECT * FROM user_story_templates WHERE uuid = :uuid LIMIT 1")
    suspend fun byUuid(uuid: String): UserStoryTemplateEntity?

    /** 重命名（只动名字，payload 与 createdAt 不动）。 */
    @Query("UPDATE user_story_templates SET name = :name WHERE uuid = :uuid")
    suspend fun rename(uuid: String, name: String)

    @Query("DELETE FROM user_story_templates WHERE uuid = :uuid")
    suspend fun delete(uuid: String)

    /** 存入端的上限把关（≥ MAX_USER_TEMPLATES 时拦下）。 */
    @Query("SELECT COUNT(*) FROM user_story_templates")
    suspend fun count(): Int
}
