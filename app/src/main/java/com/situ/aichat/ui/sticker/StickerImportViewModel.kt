package com.situ.aichat.ui.sticker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.repository.StickerRepository
import com.situ.aichat.sticker.StickerImageStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 自定义表情包导入（1:1 iOS `StickerImportView`）。选图后用 [StickerImageStore.isAnimatedGif] 严格判定动图
 * （不靠扩展名），保存时 GIF 原样、静态压到 512px PNG（由 [StickerRepository.importSticker] 落盘）。
 */
@HiltViewModel
class StickerImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stickerRepo: StickerRepository,
) : ViewModel() {

    data class ImportState(
        val bytes: ByteArray? = null,
        val preview: Bitmap? = null,
        val isAnimated: Boolean = false,
        val name: String = "",
        val description: String = "",
        val saving: Boolean = false,
    ) {
        val canSave: Boolean get() = bytes != null && name.trim().isNotEmpty() && !saving
    }

    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state.asStateFlow()

    fun setName(value: String) = _state.update { it.copy(name = value) }
    fun setDescription(value: String) = _state.update { it.copy(description = value) }

    /** 选中图片：读字节 → 严格判定 GIF → 解首帧做预览（全程 IO）。 */
    fun pickImage(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull() ?: return@withContext null
                val animated = StickerImageStore.isAnimatedGif(bytes)
                val preview = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                Triple(bytes, animated, preview)
            } ?: return@launch
            _state.update { it.copy(bytes = loaded.first, isAnimated = loaded.second, preview = loaded.third) }
        }
    }

    /** 保存：trim 名称非空 + 有字节 → 落库；成功回调 [onSaved]。 */
    fun save(onSaved: () -> Unit) {
        val s = _state.value
        val bytes = s.bytes ?: return
        if (s.name.trim().isEmpty() || s.saving) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val entity = stickerRepo.importSticker(s.name, s.description, s.isAnimated, bytes)
            _state.update { it.copy(saving = false) }
            if (entity != null) onSaved()
        }
    }
}
