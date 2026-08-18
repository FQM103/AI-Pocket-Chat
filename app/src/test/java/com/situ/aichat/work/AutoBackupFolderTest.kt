package com.situ.aichat.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 13.6c 自动备份目录纯函数单测：文件名生成 + 轮转选择（文本留 N / 媒体留 M，只动本 app 备份，按文件名时间倒序）。
 */
class AutoBackupFolderTest {

    @Test fun fileName_textBackup_hasPrefixZip_noMediaTag() {
        val name = AutoBackupFolder.fileName(0L, includeMedia = false)
        assertTrue(name.startsWith(AutoBackupFolder.PREFIX))
        assertTrue(name.endsWith(".zip"))
        assertFalse(name.contains(AutoBackupFolder.MEDIA_TAG))
    }

    @Test fun fileName_mediaBackup_hasMediaTagBeforeExt() {
        val name = AutoBackupFolder.fileName(0L, includeMedia = true)
        assertTrue(name.startsWith(AutoBackupFolder.PREFIX))
        assertTrue(name.endsWith("${AutoBackupFolder.MEDIA_TAG}.zip"))
    }

    @Test fun backupsToPrune_keepsNewestTextAndMedia_deletesRest_leavesForeignFiles() {
        val names = listOf(
            "AIChat_backup_20260101_0900.zip",
            "AIChat_backup_20260102_0900.zip",
            "AIChat_backup_20260103_0900.zip",
            "AIChat_backup_20260104_0900.zip", // 文本 4 份
            "AIChat_backup_20260101_0900_media.zip",
            "AIChat_backup_20260108_0900_media.zip", // 媒体 2 份
            "my_photo.jpg", // 外来文件，不该被删
            "notes.txt",
        )
        val pruned = AutoBackupFolder.backupsToPrune(names, keepText = 2, keepMedia = 1).toSet()
        // 文本留最近 2（0104,0103）→ 删 0102,0101；媒体留最近 1（0108）→ 删 0101_media；外来文件不动。
        assertEquals(
            setOf(
                "AIChat_backup_20260102_0900.zip",
                "AIChat_backup_20260101_0900.zip",
                "AIChat_backup_20260101_0900_media.zip",
            ),
            pruned,
        )
        assertFalse(pruned.contains("my_photo.jpg"))
        assertFalse(pruned.contains("notes.txt"))
    }

    @Test fun backupsToPrune_underLimit_deletesNothing() {
        val names = listOf(
            "AIChat_backup_20260101_0900.zip",
            "AIChat_backup_20260101_0900_media.zip",
        )
        assertTrue(AutoBackupFolder.backupsToPrune(names, keepText = 7, keepMedia = 4).isEmpty())
    }

    @Test fun backupsToPrune_ignoresNonOursAndNonZip() {
        // 不以 PREFIX 开头 / 不以 .zip 结尾 → 都不算本 app 备份，绝不入删除集。
        val names = listOf("random.zip", "AIChat_backup_x.txt", "photo.png")
        assertTrue(AutoBackupFolder.backupsToPrune(names, keepText = 0, keepMedia = 0).isEmpty())
    }

    // ── P1-8 文件名解析（真值锚 = AutoBackupFolder.fileName 与 BackupScreen.exportFileName 的拼名代码） ──

    @Test fun parse_roundTripsAutoTextName_secondPrecision() {
        val t = 1_765_432_198_765L // 任意毫秒时间戳
        val parsed = AutoBackupFolder.parseBackupFileName(AutoBackupFolder.fileName(t, includeMedia = false))
        // fileName 用 yyyyMMdd_HHmmss（秒级）→ 解析回来 = 截断到秒。
        assertEquals(t - t % 1000, parsed!!.timestampMillis)
        assertFalse(parsed.includesMedia)
    }

    @Test fun parse_roundTripsAutoMediaName() {
        val t = 1_765_432_198_765L
        val parsed = AutoBackupFolder.parseBackupFileName(AutoBackupFolder.fileName(t, includeMedia = true))
        assertEquals(t - t % 1000, parsed!!.timestampMillis)
        assertTrue(parsed.includesMedia)
    }

    @Test fun parse_acceptsManualExportName_minutePrecision() {
        // 手动导出（BackupScreen.exportFileName）= 分钟级 13 位核、永不带 _media；存进同目录会被轮转纳入，
        // 最近列表必须同样认得。期望值用同 API 现算（时区一致性，勿硬编码 epoch）。
        val parsed = AutoBackupFolder.parseBackupFileName("AIChat_backup_20260610_1530.zip")
        val expected = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).parse("20260610_1530")!!.time
        assertEquals(expected, parsed!!.timestampMillis)
        assertFalse(parsed.includesMedia)
    }

    @Test fun parse_rejectsForeignAndMalformedNames() {
        val rejects = listOf(
            "random.zip", // 非本 app 前缀
            "AIChat_backup_20260610_153045.json", // 错扩展名
            "AIChat_backup_20260610_153045 (1).zip", // SAF 去重后缀 → 核长不对
            "AIChat_backup_20261332_996099.zip", // isLenient=false 拒非法月/时
            "AIChat_backup_20260610_15304.zip", // 14 位核（非 13/15）
            "AIChat_backup_20260610_153045_media_media.zip", // 剥一层 tag 后核长不对
            "AIChat_backup_.zip", // 空核
        )
        rejects.forEach { assertEquals("应拒绝：$it", null, AutoBackupFolder.parseBackupFileName(it)) }
    }

    // ── P1-8 最近备份选择（时间倒序 + 媒体份保底） ──

    @Test fun selectRecent_appendsNewestMediaWhenOutsideLimit() {
        // 7 份逐日文本 + 1 份 6 天前媒体，limit=5 → 5 份最新文本 + 末尾追加该媒体份（换机最需要全量媒体份）。
        val names = (4..10).map { d -> "AIChat_backup_202606%02d_090000.zip".format(d) } +
            "AIChat_backup_20260604_080000_media.zip"
        val picked = AutoBackupFolder.selectRecentBackups(names, limit = 5)
        assertEquals(6, picked.size)
        assertEquals("AIChat_backup_20260610_090000.zip", picked.first())
        assertEquals("AIChat_backup_20260604_080000_media.zip", picked.last())
    }

    @Test fun selectRecent_mediaInsideLimit_notDuplicated() {
        val names = listOf(
            "AIChat_backup_20260610_090000.zip",
            "AIChat_backup_20260609_090000_media.zip",
            "AIChat_backup_20260608_090000.zip",
        )
        val picked = AutoBackupFolder.selectRecentBackups(names, limit = 5)
        assertEquals(3, picked.size) // 媒体份已在前 limit 内 → 不重复追加
        assertEquals("AIChat_backup_20260609_090000_media.zip", picked[1])
    }

    @Test fun selectRecent_noMedia_capsAtLimit_ignoresForeign() {
        val names = (1..8).map { d -> "AIChat_backup_202606%02d_090000.zip".format(d) } + "my_photo.jpg"
        val picked = AutoBackupFolder.selectRecentBackups(names, limit = 5)
        assertEquals(5, picked.size)
        assertEquals("AIChat_backup_20260608_090000.zip", picked.first())
        assertFalse(picked.contains("my_photo.jpg"))
    }

    @Test fun selectRecent_sameTimestampMixedPrecision_deterministicByNameDesc() {
        // 同一分钟的 HHmm（手动）与 HHmmss=00（自动）时间戳相同 → 按名字倒序平局裁决，顺序确定。
        val a = "AIChat_backup_20260610_1530.zip" // 分钟级 → 15:30:00.000
        val b = "AIChat_backup_20260610_153000.zip" // 秒级 → 15:30:00.000
        val picked = AutoBackupFolder.selectRecentBackups(listOf(a, b), limit = 5)
        assertEquals(listOf(b, a), picked) // "…_153000.zip" > "…_1530.zip" 字典序
    }

    // E2#2：原子写临时文件 `.part` 必须对所有「备份列表/轮转」逻辑隐形（截断残片绝不冒充备份、不占名额）。

    @Test fun parse_tempPartFile_returnsNull() {
        // 写到一半留下的 .part（不以 .zip 结尾）→ 解析必为 null（不被当成可导入备份）。
        assertNull(AutoBackupFolder.parseBackupFileName("AIChat_backup_20260613_090000_media.zip.part"))
        assertNull(AutoBackupFolder.parseBackupFileName("AIChat_backup_20260613_090000.zip.part"))
    }

    @Test fun prune_ignoresTempPartFiles() {
        // .part 残片不进轮转候选（既不被算进保留份数、也不会被当备份删/留）。
        val names = listOf(
            "AIChat_backup_20260613_090000.zip",
            "AIChat_backup_20260612_090000.zip",
            "AIChat_backup_20260611_090000.zip.part", // 截断残片
        )
        val pruned = AutoBackupFolder.backupsToPrune(names, keepText = 5, keepMedia = 2)
        assertTrue(pruned.isEmpty()) // 两份完整文本备份 ≤ keepText=5；.part 不参与
        assertFalse(pruned.any { it.endsWith(".part") })
    }

    @Test fun selectRecent_excludesTempPartFiles() {
        val names = listOf(
            "AIChat_backup_20260613_090000.zip.part", // 最新但是残片
            "AIChat_backup_20260612_090000.zip",
        )
        val picked = AutoBackupFolder.selectRecentBackups(names, limit = 5)
        assertEquals(listOf("AIChat_backup_20260612_090000.zip"), picked)
    }
}
