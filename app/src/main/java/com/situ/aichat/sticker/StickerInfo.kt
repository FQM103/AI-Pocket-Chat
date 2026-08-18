package com.situ.aichat.sticker

/**
 * 1:1 port of iOS `StickerInfo` (Models/StickerTypes.swift) — the view model shared by built-in and
 * custom stickers.
 *
 * - [id]: 唯一标识，内置是中文短 ID（如 `开心_1`），自定义是 36 位 UUID。
 * - [fileExtension]: `png` / `jpg` / `gif`。
 */
data class StickerInfo(
    val id: String,
    val name: String,
    val semanticDescription: String,
    val isAnimated: Boolean,
    val isBuiltIn: Boolean,
    val fileExtension: String,
)

/**
 * Where a sticker's image bytes live — the pure resolution of iOS `StickerService.loadGIFData` /
 * `loadImage` source selection, lifted out of the (async, I/O) decode so it can be unit-tested.
 *
 * Android adaptation: iOS `resolveRenderDecision` is a synchronous `@MainActor` function that loads
 * cached `PlatformImage`/`Data`. On Android image loading is async I/O (asset stream vs internal
 * file), so the composable resolves a [StickerSource] (pure) and then decodes it off-thread,
 * deciding animated-vs-still-vs-failed at decode time (see the chat sticker renderer in M17 UI).
 */
sealed interface StickerSource {
    /** 稳定缓存键：标识底层图片字节；路径变（导入/删除都 mint 新 UUID 文件）⇒ 键变 ⇒ 自动失效。 */
    val cacheKey: String

    /** Built-in sticker packed under `assets/stickers/<id>.<ext>`. */
    data class Asset(val assetPath: String, val isAnimated: Boolean) : StickerSource {
        override val cacheKey: String get() = "asset:$assetPath"
    }

    /** User custom sticker stored at an internal-storage absolute file path. */
    data class CustomFile(val filePath: String, val isAnimated: Boolean) : StickerSource {
        override val cacheKey: String get() = "custom:$filePath"
    }
}
