package com.situ.aichat.util

import android.content.Context
import com.situ.aichat.data.local.dao.CharacterDao
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 聊天壁纸冷启维护：清理 `filesDir/wallpapers/` 下**无任何角色引用**的孤儿文件。
 *
 * 孤儿来源（裁剪取景编辑器引入·复核 confirmed MED·契约 §9.2/§5.3）：同一次编辑里重选壁纸的中间成品图、
 * 裁完后取消/退出不保存的成品图、删角色残留——这些文件已落盘但 DB 从未/不再指向。`WallpaperStore.save`
 * 每次写新 `UUID.jpg`，而保存时的清理（[com.situ.aichat.ui.character.CharacterEditViewModel]）只删「编辑会话开始前
 * DB 那条」旧路径，故需本扫描兜底。**只删不在引用集的文件 → 绝不误删在用壁纸**。
 *
 * 编辑会话内的常见重选/移除由 `CharacterEditScreen` 即时回收（只删本会话 save 出来的中间文件）；本服务是 catch-all 兜底。
 */
@Singleton
class WallpaperMaintenanceService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val characterDao: CharacterDao,
) {
    /** 冷启清孤儿壁纸（off-main·best-effort），返回删除数。失败/无目录返回 0。 */
    suspend fun purgeOrphanWallpapers(): Int {
        val referenced = characterDao.allChatWallpaperPaths().toSet()
        return WallpaperStore.purgeOrphans(context, referenced)
    }
}
