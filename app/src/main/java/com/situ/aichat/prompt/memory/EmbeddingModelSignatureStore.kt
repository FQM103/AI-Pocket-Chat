package com.situ.aichat.prompt.memory

import android.content.Context

/**
 * Device-local record of "which embedding model the stored vectors were produced by" (14.5a; 1:1 iOS
 * `VectorMemoryService.embeddingSignatureKey` UserDefaults string).
 *
 * SharedPreferences — **not** AppSettings/Room — same convention as [com.situ.aichat.moments.MomentApiMissingFlag].
 * Deliberately device-local and **excluded from backup**, mirroring iOS UserDefaults: a restored archive must
 * NOT carry the source device's signature, so the destination keeps its own model's signature and only its own
 * model-change events trigger a re-embed. (New backups already ship embeddings; on a same-model restore they're
 * kept verbatim, on a different-model restore the per-search dimension guard / next signature change heals it —
 * identical to iOS's per-device UserDefaults semantics.)
 */
object EmbeddingModelSignatureStore {
    private const val PREFS = "vector_memory_state"
    private const val KEY_SIGNATURE = "embedding_model_signature"

    /** Last recorded signature, or "" if never written (first install). */
    fun saved(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SIGNATURE, "") ?: ""

    /**
     * 同步写入（[android.content.SharedPreferences.Editor.commit] 而非 apply）：签名必须在
     * [VectorMemoryService.detectModelChangeAndClearIfNeeded] 返回前**落盘**，否则「已清空向量但签名仍是旧值」
     * 之间存在崩溃窗口——进程在 apply 落盘前被杀会让下次启动重判变更、反复清空（幂等无数据损失但破坏前进保证，
     * 也偏离 iOS UserDefaults.set 的同步语义）。仅由后台 worker（非主线程）调用，故同步 I/O 无 ANR 风险。
     */
    @Suppress("ApplySharedPref")
    fun set(context: Context, signature: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SIGNATURE, signature).commit()
    }
}
