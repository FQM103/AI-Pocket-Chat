// security-crypto 1.1.0（stable）整库被 Google 标记弃用——EncryptedSharedPreferences/MasterKey 行为不变、
// 仍是无 GMS 的可用密钥加密方案；迁移到替代实现属独立功能决策、不在本次依赖升级范围。抑制弃用告警以保
// 0 警告 + 行为零变化（依赖升级 2026-06）。
@file:Suppress("DEPRECATION")

package com.situ.aichat.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android equivalent of the iOS Keychain (`KeychainService`). API keys are stored here keyed by
 * `apiKeyId`; the DB only holds the id. Backed by the Android Keystore (no Google Play Services
 * required — safe for mainland-China devices).
 *
 * **主线程安全（2026-07-12 性能线程专项 K3）**：三个 API 均为 suspend + 内部切 [Dispatchers.IO]——
 * 首次访问的 MasterKey/Keystore 初始化（可达上百 ms）与逐次 AES 解密读盘都不再落在调用方线程
 * （聊天回合作用域 = Main.immediate，此前每回合都在主线程解密）。语义不变：get 可空、put 同步
 * commit 返 Boolean、delete apply。
 */
class ApiKeyStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    suspend fun get(apiKeyId: String): String? = withContext(Dispatchers.IO) {
        prefs.getString(apiKeyId, null)
    }

    /**
     * 写入密钥并返回是否成功（settings-api-5）。用 commit()（同步、返回 Boolean）而非 apply()（异步、吞失败），
     * 并用 runCatching 兜住 EncryptedSharedPreferences/Keystore 初始化或写入异常——失败时返回 false，
     * 让上层中止保存并提示用户，而非静默丢失密钥。
     */
    suspend fun put(apiKeyId: String, value: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { prefs.edit().putString(apiKeyId, value).commit() }.getOrDefault(false)
    }

    suspend fun delete(apiKeyId: String) {
        withContext(Dispatchers.IO) {
            prefs.edit().remove(apiKeyId).apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "api_keys"
    }
}
