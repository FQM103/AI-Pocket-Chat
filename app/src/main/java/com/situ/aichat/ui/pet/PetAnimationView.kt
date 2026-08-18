package com.situ.aichat.ui.pet

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.pet.metadata
import com.situ.aichat.util.trimForMemoryLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 宠物精灵帧动画（1:1 iOS `PetAnimationView`）：按 [frameDurationMs] 循环切帧，`FilterQuality.None` 保持像素
 * 锐利（对应 iOS `.interpolation(.none)`）。帧从 `assets/petsprites/` 解码（[PetSpriteLoader] 带缓存）。
 * 叠加当前佩戴装扮 [PetCostumeOverlay]。资源缺失回退 🐾（对应 iOS `pawprint.fill`）。
 */
@Composable
fun PetAnimationView(
    speciesRaw: String,
    stageRaw: String,
    animationState: PetSpriteManager.AnimationState,
    size: Dp,
    modifier: Modifier = Modifier,
    isAnimating: Boolean = true,
    equippedCostumeId: String? = null,
    frameDurationMs: Long = 300,
) {
    val context = LocalContext.current
    val frameCount = animationState.frameCount

    // 当前 (species, stage, state) 的全部帧（切状态时异步重解码；缓存命中近乎瞬时）。
    val frames by produceState(initialValue = emptyList<ImageBitmap?>(), speciesRaw, stageRaw, animationState) {
        value = withContext(Dispatchers.IO) {
            (1..frameCount).map { f ->
                PetSpriteLoader.load(context, PetSpriteManager.assetPath(speciesRaw, stageRaw, animationState.raw, f))
            }
        }
    }

    var frameIndex by remember(speciesRaw, stageRaw, animationState) { mutableIntStateOf(0) }
    LaunchedEffect(isAnimating, frameCount, speciesRaw, stageRaw, animationState) {
        if (!isAnimating) {
            frameIndex = 0
            return@LaunchedEffect
        }
        while (true) {
            delay(frameDurationMs)
            frameIndex = (frameIndex + 1) % frameCount
        }
    }

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        val bmp = frames.getOrNull(if (isAnimating) frameIndex.coerceIn(0, frameCount - 1) else 0)
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.size(size),
                filterQuality = FilterQuality.None,
            )
        } else {
            // 资源缺失/加载中回退（iOS pawprint.fill）
            Text(
                text = "🐾",
                fontSize = (size.value * 0.4f).sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PetCostumeOverlay(itemId = equippedCostumeId, speciesRaw = speciesRaw, stageRaw = stageRaw, hostSize = size)
    }
}

/** 从 [CharacterPetEntity] 直接创建（自动选动画状态 + 带当前装扮），1:1 iOS 便捷初始化。 */
@Composable
fun PetAnimationView(
    pet: CharacterPetEntity,
    size: Dp,
    modifier: Modifier = Modifier,
    isAnimating: Boolean = true,
    stateOverride: PetSpriteManager.AnimationState? = null,
) {
    PetAnimationView(
        speciesRaw = pet.speciesRaw,
        stageRaw = pet.growthStageRaw,
        animationState = stateOverride ?: PetSpriteManager.animationStateFor(pet),
        size = size,
        modifier = modifier,
        isAnimating = isAnimating,
        equippedCostumeId = pet.metadata.petInventory.equippedItemId,
    )
}

/** 精灵帧加载器：从 assets 解码 PNG（inScaled=false 保 64×64 不被密度缩放）+ 进程内 [LruCache]。 */
object PetSpriteLoader {

    /**
     * 8MB 上限（64×64 ARGB 帧约 16KB/张 ≈ 可容 500 帧，远超单次会话所需）：此前 ConcurrentHashMap
     * 只进不出，浏览过的 物种×阶段×状态 帧单调累积（全目录 1225 帧 ≈ 19MB）且不响应内存压力。
     * 现对齐 Avatar/Gift/Content/Wallpaper 四仓模式：LruCache 按字节计容 + [onTrimMemory] 收缩
     * （K4·2026-07-12；evict 不 recycle——Compose 可能仍持有显示中，交给 GC，同四仓约束）。
     */
    private const val CACHE_BYTES = 8 * 1024 * 1024

    private val cache = object : LruCache<String, ImageBitmap>(CACHE_BYTES) {
        // ARGB_8888 每像素 4 字节；若解码为更省格式则此为保守高估，只会提前淘汰不会超限。
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun load(context: Context, assetPath: String): ImageBitmap? {
        cache.get(assetPath)?.let { return it }
        val opts = BitmapFactory.Options().apply { inScaled = false }
        val bmp = runCatching {
            context.assets.open(assetPath).use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull() ?: return null
        val img = bmp.asImageBitmap()
        cache.put(assetPath, img)
        return img
    }

    /** 系统内存压力时收缩（[com.situ.aichat.AIChatApplication.onTrimMemory] 统一分发）。 */
    fun onTrimMemory(level: Int) = cache.trimForMemoryLevel(level)
}
