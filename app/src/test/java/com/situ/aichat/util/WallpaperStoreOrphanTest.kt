package com.situ.aichat.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-function tests for [WallpaperStore.findOrphans] (孤儿壁纸判定·复核 confirmed MED 的根治扫描核心)。
 * 不变量：只把「不在角色引用集」的磁盘文件判为孤儿 → 绝不误删在用壁纸。
 */
class WallpaperStoreOrphanTest {

    @Test fun `all referenced means no orphans`() {
        val files = listOf("/w/a.jpg", "/w/b.jpg")
        assertEquals(emptyList<String>(), WallpaperStore.findOrphans(files, setOf("/w/a.jpg", "/w/b.jpg")))
    }

    @Test fun `unreferenced files are orphans`() {
        val files = listOf("/w/a.jpg", "/w/b.jpg", "/w/c.jpg")
        // 只有 a 被引用 → b、c 是孤儿（裁剪重选/取消遗留）。
        assertEquals(listOf("/w/b.jpg", "/w/c.jpg"), WallpaperStore.findOrphans(files, setOf("/w/a.jpg")))
    }

    @Test fun `empty referenced means all orphans`() {
        val files = listOf("/w/a.jpg", "/w/b.jpg")
        assertEquals(files, WallpaperStore.findOrphans(files, emptySet()))
    }

    @Test fun `no files means no orphans`() {
        assertEquals(emptyList<String>(), WallpaperStore.findOrphans(emptyList(), setOf("/w/a.jpg")))
    }

    @Test fun `referenced path with missing file does not affect orphan set`() {
        // DB 引用一条文件已不在的路径（残留引用）——不影响：只对磁盘真实存在的文件判孤儿。
        val files = listOf("/w/a.jpg")
        assertEquals(emptyList<String>(), WallpaperStore.findOrphans(files, setOf("/w/a.jpg", "/w/gone.jpg")))
    }
}
