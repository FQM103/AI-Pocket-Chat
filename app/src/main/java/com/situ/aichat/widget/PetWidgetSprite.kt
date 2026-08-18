package com.situ.aichat.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.situ.aichat.ui.pet.PetSpriteManager

/**
 * 从 `assets/petsprites/` 加载宠物 idle 首帧位图（小组件用）。
 *
 * **安卓地道适配**：小组件与 App 同进程，直接读 assets，不照搬 iOS 把精灵图写入 App Group 共享目录的桥。
 * 失败（缺帧/解码失败）返回 null，由视图回退 emoji 占位。
 */
fun loadPetSpriteBitmap(context: Context, speciesRaw: String, stageRaw: String): Bitmap? {
    val path = PetSpriteManager.assetPath(
        speciesRaw = speciesRaw,
        stageRaw = stageRaw,
        stateRaw = PetSpriteManager.AnimationState.IDLE.raw,
        frame = 1,
    )
    return try {
        context.assets.open(path).use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) {
        null
    }
}
