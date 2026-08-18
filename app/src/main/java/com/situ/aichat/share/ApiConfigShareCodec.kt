package com.situ.aichat.share

import com.situ.aichat.data.model.ApiProviderType
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 扫码导入/导出 API 配置（13.10b · C7，安卓便利加分）的**二维码负载编解码**（纯函数·可单测）。
 *
 * iOS 完全没有配置导入（纯手打、备份也故意不含 API 配置）→ 无 iOS 对照、无既有格式可镜像，自定义一个最小负载：
 * 一段带魔数 + 版本的 JSON，扫到后解出 provider/baseUrl/model/key 预填表单让用户复核再保存。
 *
 * ⚠️ **负载含明文 API 密钥**（无法分享设备绑定的加密密钥）——UI 须警示只扫可信的码、勿把导出的码截图公开。
 * 解码带魔数 + 版本校验：随便扫个别的二维码不会被误当成配置（魔数不符 / 解析失败 → null）。
 */
object ApiConfigShareCodec {
    /** 魔数：区分本应用的配置码与任意其它二维码内容。 */
    private const val MAGIC = "aichat.apiconfig"

    /** 负载格式版本：将来不兼容地扩展时 +1；解码遇到更高版本（看不懂）→ 拒绝。 */
    private const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Envelope(
        val t: String,
        val v: Int,
        val provider: String,
        val baseUrl: String,
        val model: String,
        val key: String,
    )

    /** 解码结果：provider 经 [ApiProviderType.fromRaw] 解析（永不失败，未知 → OPENAI_COMPATIBLE）。 */
    data class DecodedApiConfig(
        val provider: ApiProviderType,
        val baseUrl: String,
        val model: String,
        val key: String,
    )

    /** 把一份配置编码为二维码负载字符串（JSON，带魔数 + 版本）。 */
    fun encode(provider: ApiProviderType, baseUrl: String, model: String, key: String): String =
        json.encodeToString(
            Envelope(
                t = MAGIC,
                v = VERSION,
                provider = provider.raw,
                baseUrl = baseUrl.trim(),
                model = model.trim(),
                key = key.trim(),
            ),
        )

    /**
     * 解码扫到的字符串。非本应用配置码（魔数不符 / 版本过高 / 非 JSON / 缺字段）→ null（调用方据此提示「不是有效的配置二维码」）。
     * 字段取值的合法性（如 baseUrl 必须 https）不在此判，交保存路径既有校验（[com.situ.aichat.data.repository.isHttpsBaseUrl] 等）。
     */
    fun decode(raw: String): DecodedApiConfig? {
        val env = try {
            json.decodeFromString<Envelope>(raw.trim())
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (env.t != MAGIC || env.v > VERSION) return null
        return DecodedApiConfig(
            provider = ApiProviderType.fromRaw(env.provider),
            baseUrl = env.baseUrl.trim(),
            model = env.model.trim(),
            key = env.key.trim(),
        )
    }
}
