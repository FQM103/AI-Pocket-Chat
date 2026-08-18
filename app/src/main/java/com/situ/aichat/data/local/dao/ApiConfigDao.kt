package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.situ.aichat.data.local.entity.ApiConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ApiConfigDao {
    @Query("SELECT * FROM api_configurations ORDER BY creationDate ASC")
    fun observeAll(): Flow<List<ApiConfigEntity>>

    @Query("SELECT * FROM api_configurations WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<ApiConfigEntity?>

    @Query("SELECT * FROM api_configurations WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): ApiConfigEntity?

    @Query("SELECT * FROM api_configurations WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): ApiConfigEntity?

    @Query("SELECT COUNT(*) FROM api_configurations")
    suspend fun count(): Int

    @Query("UPDATE api_configurations SET isActive = 0")
    suspend fun clearActive()

    @Query("UPDATE api_configurations SET isActive = 1 WHERE uuid = :uuid")
    suspend fun setActiveFlag(uuid: String)

    @Upsert
    suspend fun upsert(config: ApiConfigEntity)

    @Update
    suspend fun update(config: ApiConfigEntity)

    @Delete
    suspend fun delete(config: ApiConfigEntity)

    // MARK: - Capability detection writebacks (targeted column updates, race-free across probes)

    @Query("UPDATE api_configurations SET detectedThinkingModelType = :type WHERE uuid = :uuid")
    suspend fun updateThinkingDetection(uuid: String, type: Int)

    @Query("UPDATE api_configurations SET detectedVisionSupport = :support WHERE uuid = :uuid")
    suspend fun updateVisionDetection(uuid: String, support: Int)

    @Query("UPDATE api_configurations SET detectedAudioInputSupport = :support WHERE uuid = :uuid")
    suspend fun updateAudioDetection(uuid: String, support: Int)

    @Query(
        """
        UPDATE api_configurations SET
          detectedToolSupportLevelRaw = :levelRaw,
          detectedToolProtocolFamilyRaw = :familyRaw,
          detectedStreamingToolSupportRaw = :streamingRaw,
          detectedThinkingToolSupportRaw = :thinkingRaw,
          toolDetectionSummary = :summary,
          toolDetectionCheckedAt = :checkedAt
        WHERE uuid = :uuid
        """,
    )
    suspend fun updateToolDetection(
        uuid: String,
        levelRaw: String,
        familyRaw: String,
        streamingRaw: String,
        thinkingRaw: String,
        summary: String,
        checkedAt: Long?,
    )

    @Query(
        """
        UPDATE api_configurations SET
          detectedThinkingModelType = -1,
          detectedVisionSupport = -1,
          detectedAudioInputSupport = -1,
          detectedToolSupportLevelRaw = 'unknown',
          detectedStreamingToolSupportRaw = 'unknown',
          detectedThinkingToolSupportRaw = 'unknown',
          toolDetectionSummary = '',
          toolDetectionCheckedAt = NULL
        WHERE uuid = :uuid
        """,
    )
    suspend fun resetDetection(uuid: String)
}
