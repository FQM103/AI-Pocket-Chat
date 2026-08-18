package com.situ.aichat.data.backup

/**
 * 导入冲突的三策略（1:1 iOS `ImportConflictStrategy`）。仅用于「逐角色私有段」（角色 + 会话+消息 / 里程碑 /
 * 宠物 / 💰角色钱包 / 日程 / 通知模板）；顶层全局段（朋友圈 / 日记 / 故事 / 💰礼物 / 💰红包 / 贴纸 / 💰用户钱包 /
 * 用户资料 / 设置）按 13.6a 已签字设计整体恢复一次，不随策略走（创建副本不另得重映射的全局段 = 已登记 LOW）。
 */
enum class ImportStrategy {
    /** 覆盖：删旧角色（FK 级联清其私有子树）→ 原 uuid 重插。 */
    OVERWRITE,

    /** 创建副本：整条私有子树主键全部重映射为新 uuid（原角色原封不动）。详见 [remapCharacterSubtree]。 */
    DUPLICATE,

    /** 跳过：完全不碰该角色（其私有段一行不动）。 */
    SKIP,
}

/**
 * 导入预览（不写库）：解包 manifest 后逐角色构建，供预览屏显示 + 让用户选策略。
 *
 * @param characters 逐角色预览行（含冲突标记）。
 * @param mediaCount 备份内媒体文件总数（manifest 速览）。
 * @param hasGlobalData 备份是否含全局段（朋友圈 / 日记 / 故事 / 💰钱包 / 设置…）——预览屏提示「将同时恢复全局数据」。
 */
data class BackupPreview(
    val characters: List<CharacterPreviewRow>,
    val mediaCount: Int,
    val hasGlobalData: Boolean,
)

/**
 * 预览的单角色行。[avatarBytes] 直接取自 zip 内字节（预览段不重存媒体），由预览屏解码显示。
 * [hasConflict]=本地已存在同 uuid 角色（1:1 iOS：按 **uuid** 比对，非按名字）；[existingName]=该本地角色的名字。
 */
data class CharacterPreviewRow(
    val uuid: String,
    val name: String,
    val messageCount: Int,
    val avatarBytes: ByteArray? = null,
    val hasConflict: Boolean = false,
    val existingName: String? = null,
) {
    // data class with a ByteArray field：用 uuid 当唯一身份（避免 equals/hashCode 比较大字节数组；列表项 key 也用 uuid）。
    override fun equals(other: Any?): Boolean = other is CharacterPreviewRow && other.uuid == uuid
    override fun hashCode(): Int = uuid.hashCode()
}
