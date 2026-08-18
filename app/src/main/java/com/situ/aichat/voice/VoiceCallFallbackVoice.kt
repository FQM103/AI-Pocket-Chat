package com.situ.aichat.voice

import android.content.Context
import android.util.Log
import com.situ.aichat.R
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.tts.TtsConfigurationRepository
import com.situ.aichat.tts.TtsService
import com.situ.aichat.tts.TtsVoiceProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 通话「没听清」兜底语音的预烘焙仓（C5 通话体验加固 2026-07-12·预烘焙先例 = P6 通知语音）。
 *
 * 为什么要预烘焙：LLM 回合失败（网络断/首字超时）的那一刻，往往网络本身就不可用——现场合成兜底语音
 * 大概率跟着失败。所以在通话开始、网络还好的时候，就把两三句「呀，刚没听清，你再说一遍好不好？」用
 * **角色自己的音色**合成好存本地；失败时刻直接播本地文件，一定有声音。用户的体感不是「出错了」，而是
 * 「她没听清」——失败被对话流自然吸收（体验加固卷总原则）。
 *
 *  - 缓存按「音色指纹」失效：音色/供应商/模型/情绪/语速/音调任一变化 → 整组重烘；
 *  - 部分成功也可用（有几句存几句）；全部失败不写指纹，下通电话再试；
 *  - 兜底句只播声音，**不进 transcript / 通话卡 / 模型历史**——它是「系统代言」，不是角色真说过的话。
 */
@Singleton
class VoiceCallFallbackVoice @Inject constructor(
    @ApplicationContext private val context: Context,
    private val characterRepo: CharacterRepository,
    private val ttsConfigRepo: TtsConfigurationRepository,
    private val ttsService: TtsService,
) {
    private val bakeMutex = Mutex()

    /**
     * 确保该角色的兜底语音已按当前音色烘好（通话开始时后台调用；拨号 1.5s + 首轮间隙足够）。
     * 指纹未变且已有成品 → 直接返回；无可用音色/全部合成失败 → 静默放弃（播放侧优雅退化）。
     */
    suspend fun ensureBaked(characterUuid: String) = withContext(Dispatchers.IO) {
        bakeMutex.withLock {
            try {
                val character = characterRepo.get(characterUuid) ?: return@withLock
                val config = ttsConfigRepo.getConfiguration()
                val apiKey = ttsConfigRepo.getApiKey()
                val profile = TtsVoiceProfile(
                    voiceIdentifier = character.voiceIdentifier,
                    remoteVoiceID = character.remoteVoiceID,
                    ttsEmotionRaw = character.ttsEmotionRaw,
                    ttsSpeed = character.ttsSpeed,
                    ttsPitch = character.ttsPitch,
                )
                val fingerprint = voiceFingerprint(profile, config.providerType.raw, config.modelName)
                val dir = clipDir(characterUuid)
                if (isBakedFor(dir, fingerprint)) return@withLock

                dir.deleteRecursively()
                dir.mkdirs()
                var baked = 0
                FALLBACK_LINE_RES.forEachIndexed { index, resId ->
                    val text = context.getString(resId)
                    val audio = ttsService.synthesize(text, profile, config, apiKey, moodEmoji = null)
                    if (audio != null && audio.isNotEmpty()) {
                        File(dir, "$index$CLIP_EXT").writeBytes(audio)
                        baked++
                    }
                }
                if (baked > 0) {
                    File(dir, FINGERPRINT_FILE).writeText(fingerprint)
                    Log.i(TAG, "baked $baked fallback clips for $characterUuid")
                } else {
                    dir.deleteRecursively() // 全失败不留半成品，下通电话再试
                }
            } catch (e: Exception) {
                Log.w(TAG, "fallback bake failed: ${e.message}") // 烘焙失败绝不影响通话主流程
            }
        }
    }

    /** 随机取一段已烘好的兜底音频；没烘好/指纹缺失 → null（调用方静默退化为直接回听）。 */
    suspend fun bakedClipOrNull(characterUuid: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val dir = clipDir(characterUuid)
            if (!File(dir, FINGERPRINT_FILE).exists()) return@withContext null
            val clips = dir.listFiles { f -> f.name.endsWith(CLIP_EXT) }?.takeIf { it.isNotEmpty() }
                ?: return@withContext null
            clips[Random.nextInt(clips.size)].readBytes()
        } catch (e: Exception) {
            Log.w(TAG, "fallback clip read failed: ${e.message}")
            null
        }
    }

    private fun clipDir(characterUuid: String): File =
        File(File(context.filesDir, BAKE_DIR), characterUuid)

    private fun isBakedFor(dir: File, fingerprint: String): Boolean {
        val stamp = File(dir, FINGERPRINT_FILE)
        if (!stamp.exists() || stamp.readText() != fingerprint) return false
        return dir.listFiles { f -> f.name.endsWith(CLIP_EXT) }?.isNotEmpty() == true
    }

    internal companion object {
        private const val TAG = "VoiceCallFallbackVoice"
        private const val BAKE_DIR = "voice_fallback"
        private const val FINGERPRINT_FILE = "fingerprint.txt"
        private const val CLIP_EXT = ".audio"

        /** 兜底话术（中性温和、不绑人设；双语资源）。改动话术会因指纹不含文本而复用旧音频——加句时同步 bump [FORMAT_VERSION]。 */
        val FALLBACK_LINE_RES = listOf(
            R.string.voice_call_fallback_line_1,
            R.string.voice_call_fallback_line_2,
            R.string.voice_call_fallback_line_3,
        )

        private const val FORMAT_VERSION = 1

        /** 音色指纹：任一发声参数变化 → 缓存整组失效（明文拼接便于调试；私有目录无敏感泄漏）。 */
        internal fun voiceFingerprint(profile: TtsVoiceProfile, providerRaw: String, modelName: String): String =
            listOf(
                "v$FORMAT_VERSION", providerRaw, modelName,
                profile.voiceIdentifier, profile.remoteVoiceID,
                profile.ttsEmotionRaw, profile.ttsSpeed.toString(), profile.ttsPitch.toString(),
            ).joinToString("|")
    }
}
