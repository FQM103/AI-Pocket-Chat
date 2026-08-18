package com.situ.aichat.world.notify

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「上次进世界」单值仓（W8 图纸 §3.5·决策 33 只降频不静音）：记录用户最后一次打开世界界面的时刻，供每日封顶曲线
 * 算「连续多少天没回世界」。SharedPreferences 形态仿 [com.situ.aichat.notification.PendingDeliveryStore]——不入备份
 * （恢复后曲线锚回落世界 createdAt·可接受降级·§5 E21）。
 *
 * [markWorldEntered] 公开给 W9/W11 在「用户打开世界界面」时调（本块无人调·挂账 W9/W11）。
 */
@Singleton
class WorldNotifyStateStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 上次进世界的时刻（epoch millis·从未进过 → 0）。 */
    val lastWorldEnteredAt: Long
        get() = prefs().getLong(KEY_LAST_ENTERED, 0L)

    /** 记下用户打开世界界面的时刻（W9/W11 调·回世界即让封顶曲线归位）。 */
    fun markWorldEntered(nowMs: Long) {
        prefs().edit { putLong(KEY_LAST_ENTERED, nowMs) }
    }

    private companion object {
        const val PREFS_NAME = "world_notify_state"
        const val KEY_LAST_ENTERED = "last_world_entered_at"
    }
}
