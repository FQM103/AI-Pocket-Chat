package com.situ.aichat.util

import android.content.Context
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Stores synthesized TTS audio (per-message voice + call recordings) as raw files under
 * `filesDir/tts_audio`, mirroring how iOS keeps `Message.audioData` as external-storage `Data`
 * referenced by `MessageMediaStore` relative paths. The entity holds the absolute file path in
 * `MessageEntity.audioRelativePath` (same convention as [ContentImageStore] / [AvatarStore]: the
 * field is named "relative" after iOS but Android stores the absolute path).
 *
 * Unlike the image stores there is no decoding/downscaling — the bytes are written verbatim
 * (system TTS → WAV, remote MiniMax/Volink → MP3). `ext` only sets the filename suffix; playback
 * (ExoPlayer) auto-detects the container, so a wrong suffix never breaks playback.
 */
object AudioStore {
    private const val DIR = "tts_audio"

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** Write synthesized audio bytes to internal storage. Returns the absolute path, or null on failure. */
    suspend fun saveBytes(context: Context, bytes: ByteArray, ext: String = "wav"): String? =
        withContext(Dispatchers.IO) {
            if (bytes.isEmpty()) return@withContext null
            runCatching {
                val safeExt = ext.trim().lowercase().ifEmpty { "wav" }
                val file = File(dir(context), "${UUID.randomUUID()}.$safeExt")
                FileOutputStream(file).use { it.write(bytes) }
                file.absolutePath
            }.getOrNull()
        }

    /** Read stored audio bytes for playback. Returns null for a null/blank/missing path. */
    suspend fun load(path: String?): ByteArray? = withContext(Dispatchers.IO) {
        if (path.isNullOrEmpty()) return@withContext null
        runCatching { File(path).takeIf { it.exists() }?.readBytes() }.getOrNull()
    }

    /** Delete one audio file (best-effort), e.g. when its message is removed. */
    fun delete(path: String?) {
        if (path.isNullOrEmpty()) return
        runCatching { File(path).takeIf { it.exists() }?.delete() }
    }

    /**
     * Read the clip's duration in seconds via [MediaMetadataRetriever] (1:1 iOS `TTSService.duration(of:)`).
     * Returns null when the file is missing/unreadable so callers can fall back to an estimate.
     */
    suspend fun durationSeconds(path: String?): Double? = withContext(Dispatchers.IO) {
        if (path.isNullOrEmpty()) return@withContext null
        if (!File(path).exists()) return@withContext null
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.let { it / 1000.0 }
            } finally {
                retriever.release()
            }
        }.getOrNull()
    }
}
