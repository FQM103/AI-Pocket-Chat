package com.situ.aichat.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.situ.aichat.MainActivity

/**
 * 宠物小组件 → MainActivity 的深链 Intent 工厂（1:1 iOS widget 的 `aichat://pet-detail` / `aichat://pet-action`）。
 *
 * MainActivity 读 extra 后经 [com.situ.aichat.notification.NotificationNavigator] 投放跳转目标；
 * 唯一 data Uri 保证多宠物/多动作的 PendingIntent 互不串 extras。
 */
object PetWidgetIntents {
    /** extra：打开该 characterUuid 的宠物详情（点击整块小组件，1:1 iOS aichat://pet-detail）。 */
    const val EXTRA_OPEN_PET_DETAIL = "widget_open_pet_detail"

    /** extra：在该宠物上执行的护理动作（11.3b 中号组件 feed/pet 按钮，1:1 iOS aichat://pet-action）。 */
    const val EXTRA_PET_ACTION = "widget_pet_action"
    /** extra：护理动作的目标宠物 characterUuid（与导航 extra 分开，动作路径不跳转，对齐 iOS）。 */
    const val EXTRA_PET_CHARACTER = "widget_pet_character"
    const val ACTION_FEED = "feed"
    const val ACTION_PET = "pet"

    /** 点击整块小组件 → 打开 App 并跳到该宠物详情。 */
    fun openPetDetail(context: Context, characterUuid: String): Intent =
        baseIntent(context, "detail", characterUuid)
            .putExtra(EXTRA_OPEN_PET_DETAIL, characterUuid)

    /** 中号组件操作按钮 → 打开 App 执行护理动作（喂食/摸摸）。1:1 iOS pet-action：只执行不跳转，小组件随后自刷新。 */
    fun openPetAction(context: Context, characterUuid: String, action: String): Intent =
        baseIntent(context, action, characterUuid)
            .putExtra(EXTRA_PET_ACTION, action)
            .putExtra(EXTRA_PET_CHARACTER, characterUuid)

    private fun baseIntent(context: Context, kind: String, characterUuid: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            data = Uri.parse("aichat://pet/$kind/$characterUuid")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
}
