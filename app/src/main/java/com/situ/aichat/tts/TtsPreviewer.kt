package com.situ.aichat.tts

import android.content.Context
import com.situ.aichat.R
import com.situ.aichat.tts.provider.TtsRemoteConfigValues
import com.situ.aichat.util.AudioStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Synthesizes a one-line sample and plays it for the "试听 / Preview" buttons (1:1 iOS
 * `TTSVoicePreviewView` preview action), shared by the TTS-config and character-edit screens. Stores
 * the clip via [AudioStore] and plays it on the shared [TtsAudioPlayer]; the previous preview file is
 * deleted on each new preview. Returns null on success or a localized error message.
 */
@Singleton
class TtsPreviewer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ttsService: TtsService,
    private val player: TtsAudioPlayer,
) {
    @Volatile
    private var lastPath: String? = null

    /** Installed system voices for the picker (best quality first). */
    suspend fun systemVoices() = ttsService.availableSystemVoices()

    /**
     * Synthesize the sample line for the given provider/voice and play it. For system TTS the zh-CN
     * default is allowed (preview), so an empty [systemVoice] still speaks; for a remote provider a
     * voice id is required. [remoteConfig] is the prebuilt remote request config (ignored for system).
     */
    suspend fun preview(
        provider: TtsProviderType,
        systemVoice: String,
        remoteVoiceId: String,
        remoteConfig: TtsRemoteConfigValues?,
    ): String? {
        player.stop()
        lastPath?.let { AudioStore.delete(it) }
        lastPath = null

        val sample = context.getString(R.string.tts_preview_sample)
        val bytes: ByteArray? = if (provider == TtsProviderType.SYSTEM) {
            ttsService.synthesizeSystem(sample, systemVoice.trim(), allowDefaultVoice = true)
        } else {
            val voice = remoteVoiceId.trim()
            if (voice.isEmpty() || remoteConfig == null) return context.getString(R.string.tts_preview_select_voice)
            ttsService.synthesizeRemote(sample, voice, remoteConfig)
        }
        if (bytes == null) return context.getString(R.string.tts_failed)

        val ext = if (provider == TtsProviderType.SYSTEM) "wav" else remoteConfig?.responseFormat?.raw ?: "mp3"
        val path = AudioStore.saveBytes(context, bytes, ext) ?: return context.getString(R.string.tts_failed)
        lastPath = path
        player.play(PREVIEW_ID, path)
        return null
    }

    fun stop() {
        player.stop()
        lastPath?.let { AudioStore.delete(it) }
        lastPath = null
    }

    private companion object {
        const val PREVIEW_ID = "tts_preview"
    }
}
