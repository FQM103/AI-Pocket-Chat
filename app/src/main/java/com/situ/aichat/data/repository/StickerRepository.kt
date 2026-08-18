package com.situ.aichat.data.repository

import android.content.Context
import com.situ.aichat.data.local.dao.CustomStickerDao
import com.situ.aichat.data.local.entity.CustomStickerEntity
import com.situ.aichat.sticker.StickerImageStore
import com.situ.aichat.sticker.StickerService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Custom sticker aggregate (M17). Wraps [CustomStickerDao] + [StickerImageStore] so callers never
 * touch disk or the DB directly: import writes the image file then inserts the row; delete removes
 * both. Prompt building reads [getAllForPrompt] (createdAt-ascending = deterministic alias map); the
 * management UI observes [observeAll] (newest first).
 */
@Singleton
class StickerRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: CustomStickerDao,
) {
    /** createdAt-ascending — prompt list + alias map determinism. */
    suspend fun getAllForPrompt(): List<CustomStickerEntity> = dao.getAllOrderByCreatedAtAsc()

    /** createdAt-descending Flow — management page (newest first). */
    fun observeAll(): Flow<List<CustomStickerEntity>> = dao.observeAllOrderByCreatedAtDesc()

    suspend fun count(): Int = dao.count()

    /** True when at the 100-sticker cap (iOS `customStickerLimit`) — gate the import button. */
    suspend fun isAtLimit(): Boolean = dao.count() >= StickerService.CUSTOM_STICKER_LIMIT

    /**
     * Import a custom sticker: persist [bytes] (GIF raw / static 512px PNG) then insert the row.
     * Returns the created entity, or null if the image could not be saved.
     */
    suspend fun importSticker(
        name: String,
        semanticDescription: String,
        isAnimated: Boolean,
        bytes: ByteArray,
    ): CustomStickerEntity? {
        val path = StickerImageStore.save(context, bytes, isAnimated) ?: return null
        val entity = CustomStickerEntity(
            name = name.trim(),
            semanticDescription = semanticDescription.trim(),
            isAnimated = isAnimated,
            imagePath = path,
        )
        dao.insert(entity)
        return entity
    }

    /** Delete the row + its image file on disk. */
    suspend fun delete(sticker: CustomStickerEntity) {
        dao.delete(sticker.stickerUuid)
        StickerImageStore.delete(sticker.imagePath)
    }
}
