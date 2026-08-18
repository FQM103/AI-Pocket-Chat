package com.situ.aichat.ui.chat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.situ.aichat.R

/**
 * 删除消息的「轻一声」音效（契约批准·短促柔和的下滑 pop，资源 `res/raw/message_delete.wav` 由脚本合成 110ms）。
 *
 * - 用 [SoundPool] 低延迟播放短 UI 音；进屏即预载、离屏释放（[DisposableEffect]）。
 * - 礼貌降噪：仅在响铃档（[AudioManager.RINGER_MODE_NORMAL]）出声；静音/振动档不出声（触觉仍给反馈）。
 * - 纯系统 API（SoundPool/AudioManager），无 GMS 依赖（铁律#4 不沾边）。
 *
 * @return 「播放一次」的函数；未加载完成或非响铃档时静默跳过（绝不抛异常、不阻塞）。
 */
@Composable
fun rememberMessageDeleteSound(): () -> Unit {
    val context = LocalContext.current
    val holder = remember(context) { MessageDeleteSoundHolder(context.applicationContext) }
    DisposableEffect(holder) { onDispose { holder.release() } }
    return holder::play
}

private class MessageDeleteSoundHolder(appContext: Context) {
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    @Volatile private var loaded = false
    private val soundId = soundPool.load(appContext, R.raw.message_delete, 1)

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (sampleId == soundId && status == 0) loaded = true
        }
    }

    fun play() {
        if (!loaded) return
        // 静音 / 振动档不出声（仅触觉反馈），= 多数精致 App 的礼貌做法。
        if (audioManager?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        soundPool.play(soundId, VOLUME, VOLUME, 1, 0, 1f)
    }

    fun release() = soundPool.release()

    private companion object {
        const val VOLUME = 0.42f // 克制音量
    }
}
