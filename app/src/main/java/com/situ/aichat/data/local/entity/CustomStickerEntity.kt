package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * User-imported custom sticker (1:1 iOS `CustomSticker` @Model, Models/CustomSticker.swift).
 *
 * Storage adaptation: iOS keeps the image as `@Attribute(.externalStorage) imageData: Data?`. The
 * Android port writes the image bytes to internal storage (`filesDir/stickers/<uuid>.<ext>` via
 * `StickerImageStore`) and stores only [imagePath] here — never the BLOB in SQLite. [usageCount]
 * exists for parity but, like iOS, has no write path today (ordering is by [createdAt]).
 *
 * Derived helpers (`effectiveDescription` / `toStickerInfo`) live as extensions in the `sticker`
 * package (see `StickerService.kt`), keeping this entity a pure Room row — same convention as the
 * diary/moment image-path accessors in `data/model`.
 */
@Entity(
    tableName = "custom_sticker",
    indices = [Index("createdAt")],
)
data class CustomStickerEntity(
    @PrimaryKey val stickerUuid: String = UUID.randomUUID().toString(),
    val name: String = "",
    val semanticDescription: String = "",
    val isAnimated: Boolean = false,
    /** Internal-storage absolute file path (replaces iOS `imageData` external-storage BLOB). */
    val imagePath: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val usageCount: Int = 0,
)
