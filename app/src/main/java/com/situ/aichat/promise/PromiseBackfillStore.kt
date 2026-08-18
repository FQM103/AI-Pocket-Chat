package com.situ.aichat.promise

import android.content.Context

/**
 * 承诺账本历史回填的「一次性完成」标记（记忆改造一期·图纸 §3.11·照 [com.situ.aichat.prompt.memory.EmbeddingModelSignatureStore]
 * 逐字风格）。
 *
 * SharedPreferences——**非** AppSettings/Room——刻意 **device-local、不进备份**：换机导入的备份已带 promises 段，
 * 回填即便重跑也被注册端去重挡住（幂等），故目标设备保留自己的标记、各自判「是否回填过」，与 iOS UserDefaults 逐设备语义一致。
 */
object PromiseBackfillStore {
    private const val PREFS = "promise_ledger_state"
    private const val KEY_BACKFILLED = "meeting_promises_backfilled_v1"

    /** 是否已完成一次性回填（首装 / 未回填 → false）。 */
    fun done(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_BACKFILLED, false)

    /**
     * 同步写入（[android.content.SharedPreferences.Editor.commit] 而非 apply）：标记必须在 Worker 返回 success 前**落盘**，
     * 否则「约定已注册进账本但标记仍是 false」之间存在崩溃窗口——进程在 apply 落盘前被杀会让下次启动重跑回填（幂等
     * 无数据损失但白烧一次全表扫描，也偏离逐设备 UserDefaults 的同步语义）。仅由后台 worker（非主线程）调用，同步 I/O 无 ANR 风险。
     */
    @Suppress("ApplySharedPref")
    fun setDone(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BACKFILLED, true).commit()
    }
}
