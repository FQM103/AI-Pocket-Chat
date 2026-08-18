package com.situ.aichat.data.backup

import android.content.Context
import android.util.Log
import com.situ.aichat.sticker.StickerImageStore
import com.situ.aichat.util.AudioStore
import com.situ.aichat.util.AvatarStore
import com.situ.aichat.util.ContentImageStore
import com.situ.aichat.util.WallpaperStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 导入侧媒体重存（卷 A 从 [BackupImporter] 抽出·行数硬顶预案）：**读一条、重存一条、弃一条**。
 *
 * 旧路径把 zip 里全部媒体解压进一张 `HashMap<String, ByteArray>` 再逐条重存——峰值内存 = 整包媒体总量
 * （小米 14 实测 64MB 假包即吃满 97.9% 堆）。现在字节按条 materialize，峰值 ≈ 单个媒体文件。
 *
 * [collectCharacterMediaKeys] / [resaveMedia] 由 [BackupImporter] **原样搬入**（路由规则、Store 选择、
 * 扩展名回退一字未改）；新写的只有[restoreMedia] 这层编排。本类不碰 DB、不碰事务。
 */
@Singleton
class BackupMediaRestorer @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {

    /**
     * 遍二：把 zip 里的媒体逐条重存到各 Store，登记「zip 键 → 新绝对路径」。
     *
     * @param skipKeys 「跳过」策略角色的私有媒体键——**连字节都不读**（不解压不落盘，也不计失败）。
     * @param mediaTotal 进度分母 = manifest 记的媒体条目数；每处理一个条目 done+1（**跳过项也计入**，
     *   对齐导出侧 `writeTo.onEntry` 的既有口径，进度条单调收敛）。
     * @param newPathByKey 收集键→新路径（事务内建实体时用）。同键重复出现 = **后者胜**：前者刚落盘的文件当场删掉。
     * @param newMediaFiles 收集已落盘的新文件；**所有权留在调用方**——中途抛出/取消时由调用方按此清孤儿。
     * @return 未能恢复的媒体条数（单条读断/解码失败/重存失败不拖垮整包：结构化数据照常恢复，失败数如实上报）。
     */
    suspend fun restoreMedia(
        source: BackupByteSource,
        skipKeys: Set<String>,
        mediaTotal: Int,
        newPathByKey: MutableMap<String, String>,
        newMediaFiles: MutableList<String>,
        onProgress: ((BackupProgress) -> Unit)?,
    ): Int {
        var failed = 0
        var done = 0
        BackupArchive.forEachMediaEntry(source::open) { key, readBytes ->
            done++
            onProgress?.invoke(BackupProgress(BackupProgress.Stage.RESTORE_MEDIA, done, mediaTotal))
            if (key !in skipKeys) {
                val path = try {
                    resaveMedia(key, readBytes())
                } catch (e: CancellationException) {
                    throw e // 结构化并发：取消绝不吞，交调用方统一清孤儿后重抛
                } catch (e: Exception) {
                    null // 这一条坏了（字节损坏/解码失败）：记一笔继续下一条，不因一条媒体丢掉整包聊天记录
                }
                if (path == null) {
                    failed++
                } else {
                    newPathByKey.put(key, path)?.let { previous ->
                        newMediaFiles.remove(previous)
                        runCatching { File(previous).delete() }
                    }
                    newMediaFiles.add(path)
                }
            }
        }
        return failed
    }

    /**
     * 只把 [keys] 点名的那几条媒体读进内存（预览段的头像缩略图用）：**其余条目连字节都不读**。
     * [keys] 为空 → 整条遍历都省了。读到一半断了不抛（预览是只读的展示，缩略图缺几张不该拦住用户导入）。
     */
    fun readMediaBytes(source: BackupByteSource, keys: Set<String>): Map<String, ByteArray> {
        if (keys.isEmpty()) return emptyMap()
        val bytes = HashMap<String, ByteArray>(keys.size)
        try {
            BackupArchive.forEachMediaEntry(source::open) { key, readBytes ->
                if (key in keys) bytes[key] = readBytes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "预览媒体读取中断：${e.message}（不影响预览与后续导入判定）")
        }
        return bytes
    }

    /** 收集一个角色私有段引用的全部媒体 zip 键（头像 + 壁纸 + 消息 audio/image/thumb），用于跳过策略不重存。 */
    fun collectCharacterMediaKeys(cd: CharacterBackupData, into: MutableSet<String>) {
        cd.character.avatarArchiveKey?.let(into::add)
        cd.character.chatWallpaperArchiveKey?.let(into::add)
        cd.conversations?.forEach { conv ->
            conv.messages?.forEach { m ->
                m.audioArchiveKey?.let(into::add)
                m.imageArchiveKey?.let(into::add)
                m.imageThumbnailArchiveKey?.let(into::add)
            }
        }
    }

    /** zip 内媒体字节按 key 前缀路由到对应 Store 重存，返回新绝对路径。 */
    private suspend fun resaveMedia(key: String, bytes: ByteArray): String? = when {
        key.startsWith("${BackupArchive.MEDIA_PREFIX}avatars/") -> AvatarStore.saveBytes(appContext, bytes)
        key.startsWith("${BackupArchive.MEDIA_PREFIX}wallpapers/") -> WallpaperStore.saveBytes(appContext, bytes)
        key.startsWith("${BackupArchive.MEDIA_PREFIX}audio/") -> AudioStore.saveBytes(appContext, bytes, fileExt(key, "wav"))
        key.startsWith("${BackupArchive.MEDIA_PREFIX}stickers/") -> StickerImageStore.save(appContext, bytes, key.endsWith(".gif"))
        else -> ContentImageStore.saveBytes(appContext, bytes) // images / moment / diary / gift
    }

    private companion object {
        const val TAG = "BackupImport"
    }
}
