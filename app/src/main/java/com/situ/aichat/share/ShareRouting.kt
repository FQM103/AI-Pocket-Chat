package com.situ.aichat.share

/**
 * 分享给角色（Direct Share · C3，13.10a·安卓超越 iOS）的**纯路由判定**。
 *
 * iOS 完全没有 Share Extension（只有往外分享导出备份）→ 这是纯安卓超越。系统分享面板里出现「最近聊过的角色」
 * （= [com.situ.aichat.shortcut.ConversationShortcutPublisher] 推的动态快捷方式，已挂 [SHARE_CATEGORY] + Person），
 * 从任意 App 把一段文字分享给某个角色，角色**直接接住并回复**（用户 2026-06-09 拍板「直接发出+角色马上回复」）。
 *
 * 两条落地路径由系统是否带上「被选中的快捷方式 id」（= 会话 uuid）区分：
 * - 选了具体角色行 → 系统在 Intent 带 `EXTRA_SHORTCUT_ID` → [ShareRoute.Direct]：直接投到那个会话。
 * - 选了 App 通用入口（分享面板里 App 自身那一格，无快捷方式 id）→ [ShareRoute.Picker]：跳联系人让用户点选收件角色，
 *   **绝不静默丢弃**分享内容。
 *
 * 判定逻辑设为纯函数便于单测（不碰 Android `Intent`）：调用方负责从 Intent 取出 rawText / shortcutId。
 */
sealed interface ShareRoute {
    /** 命中具体角色：投到该会话（[conversationUuid] = 被选中的快捷方式 id）。 */
    data class Direct(val conversationUuid: String, val text: String) : ShareRoute

    /** 未命中具体角色：暂存文本、跳联系人点选收件角色。 */
    data class Picker(val text: String) : ShareRoute

    /** 空白文本 / 非分享：忽略。 */
    data object Ignore : ShareRoute
}

object ShareRouting {
    /**
     * 动态快捷方式与 `res/xml/shortcuts.xml` 的 `<share-target>` 共用的分享类目。两处必须**字面一致**，系统才会把
     * 快捷方式绑定为分享目标。改这里务必同步改 shortcuts.xml 的 `<category android:name=...>`。
     */
    const val SHARE_CATEGORY = "com.situ.aichat.category.SHARE_TARGET"

    /**
     * 由（分享文本，被选中的快捷方式 id）判定落地路径。文本两端去空白后为空 → [ShareRoute.Ignore]；
     * 有有效快捷方式 id（= 会话 uuid）→ [ShareRoute.Direct]；否则 → [ShareRoute.Picker]。
     */
    fun decide(rawText: String?, shortcutId: String?): ShareRoute {
        val text = rawText?.trim().orEmpty()
        if (text.isEmpty()) return ShareRoute.Ignore
        val uuid = shortcutId?.trim().orEmpty()
        return if (uuid.isNotEmpty()) ShareRoute.Direct(uuid, text) else ShareRoute.Picker(text)
    }
}
