package com.situ.aichat.diagnostics.perf

import android.util.Base64
import com.situ.aichat.data.backup.BackupArchive
import com.situ.aichat.data.backup.BackupManifest
import com.situ.aichat.data.backup.BackupPackage
import com.situ.aichat.data.backup.CharacterBackupData
import com.situ.aichat.data.backup.CharacterExport
import com.situ.aichat.data.backup.CharacterSummary
import com.situ.aichat.data.backup.ConversationExport
import com.situ.aichat.data.backup.MessageExport
import java.io.File

/**
 * 可导入档假包的 manifest 内容（卷 A·J10；从 [FakeBackupBuilder] 分出来控行数）。
 *
 * 默认档那个占位 manifest 只够体检——它进不了真导入，也就验不了本卷最要紧的那一条：
 * 「**导入一个比内存上限还大的备份**」。这里造的是货真价实的角色 / 会话 / 消息 / 头像。
 */
internal object FakeImportableManifest {

    /**
     * 造一份可导入档的 manifest：[CHARACTERS] 个「体检角色」，各带 1 个会话 ×
     * [MESSAGES_PER_CHARACTER] 条纯文本消息 + 一张 1×1 的真 JPEG 头像（顺手把头像条目登记进 [mediaPaths]）。
     * uuid 一律 `fakebkp-` 前缀 —— 验完照这个前缀清干净。**钱段一个都不填**。
     */
    fun build(mediaDir: File, mediaPaths: MutableMap<String, String>): BackupPackage {
        val avatarBytes = Base64.decode(TINY_JPEG_BASE64, Base64.DEFAULT)
        val characters = (0 until CHARACTERS).map { i ->
            val uuid = "$UUID_PREFIX$i"
            val avatarKey = "${BackupArchive.MEDIA_PREFIX}avatars/$uuid.jpg"
            mediaPaths[avatarKey] = File(mediaDir, "$uuid.jpg").apply { writeBytes(avatarBytes) }.absolutePath
            CharacterBackupData(
                character = CharacterExport(
                    uuid = uuid,
                    name = "体检角色$i",
                    creationDate = FIXED_DATE,
                    avatarArchiveKey = avatarKey,
                ),
                conversations = listOf(
                    ConversationExport(
                        uuid = "$uuid-conv",
                        title = "体检会话",
                        creationDate = FIXED_DATE,
                        messages = (0 until MESSAGES_PER_CHARACTER).map { m ->
                            MessageExport(
                                messageUUID = "$uuid-m$m",
                                role = if (m % 2 == 0) "user" else "assistant",
                                content = "体检消息 $m",
                                timestamp = FIXED_DATE + m,
                            )
                        },
                    ),
                ),
            )
        }
        return BackupPackage(
            manifest = BackupManifest(
                version = ARCHIVE_VERSION,
                appVersion = "perf-fake",
                includesMedia = true,
                mediaCount = mediaPaths.size,
                characterSummaries = characters.map {
                    CharacterSummary(name = it.character.name, uuid = it.character.uuid, messageCount = MESSAGES_PER_CHARACTER)
                },
            ),
            characters = characters,
        )
    }


    /** zip 全量格式版本（= `BackupManifest.version` 的 2）。 */
    private const val ARCHIVE_VERSION = 2

    /** 角色数 / 每角色消息数 / uuid 前缀（**验完照前缀清理**）/ 固定时间戳（同输入同产出）。 */
    private const val CHARACTERS = 3
    private const val MESSAGES_PER_CHARACTER = 20
    const val UUID_PREFIX = "fakebkp-"
    private const val FIXED_DATE = 1_754_000_000_000L

    /** 1×1 像素的真 JPEG（可导入档的头像；解得开才不会被记成「媒体未能恢复」·JVM ImageIO 已复核可解码）。 */
    private const val TINY_JPEG_BASE64 =
        "/9j/4AAQSkZJRgABAgAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a" +
            "HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIy" +
            "MjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIA" +
            "AhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQA" +
            "AAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3" +
            "ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWm" +
            "p6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEA" +
            "AwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSEx" +
            "BhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElK" +
            "U1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3" +
            "uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD2eiii" +
            "sTQ//9k="
}
