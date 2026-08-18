package com.situ.aichat.sticker

import com.situ.aichat.data.local.entity.CustomStickerEntity

/** 有效语义（空回退 name），1:1 iOS `CustomSticker.effectiveDescription`。 */
val CustomStickerEntity.effectiveDescription: String
    get() = semanticDescription.ifEmpty { name }

/** 转通用 [StickerInfo]，1:1 iOS `CustomSticker.toStickerInfo()`（ext = if isAnimated "gif" else "png"）。 */
fun CustomStickerEntity.toStickerInfo(): StickerInfo = StickerInfo(
    id = stickerUuid,
    name = name,
    semanticDescription = effectiveDescription,
    isAnimated = isAnimated,
    isBuiltIn = false,
    fileExtension = if (isAnimated) "gif" else "png",
)

/**
 * 1:1 port of iOS `StickerService` pure logic (Services/StickerService.swift): sticker lookup, the
 * custom-sticker short-alias map, the AI-reply transform chain (alias⇄UUID / strip / semantic), and
 * the prompt sticker list. Image loading / caching / GIF decode are platform I/O and live in
 * `StickerImageStore` + the chat renderer (M17 UI); only the testable pure logic is here.
 *
 * `customStickers` arguments must be ordered by `createdAt` ascending (as `StickerRepository`
 * returns them) so the alias map is deterministic — same data ⇒ same `c_name`/`c_name_2` numbering.
 */
object StickerService {

    /** 自定义表情包上限（1:1 iOS `customStickerLimit`）。 */
    const val CUSTOM_STICKER_LIMIT = 100

    // MARK: - 查找

    /** 内置 ID 查找（全集 byId）。 */
    fun builtInSticker(id: String): StickerInfo? = BuiltInStickerCatalog.byId[id]

    /** 通过 ID 查找（先内置全集，再自定义）。校验/渲染统一用全集，确保不误删隐藏的内置表情。 */
    fun stickerInfo(id: String, customStickers: List<CustomStickerEntity>): StickerInfo? {
        BuiltInStickerCatalog.byId[id]?.let { return it }
        return customStickers.firstOrNull { it.stickerUuid == id }?.toStickerInfo()
    }

    /** 合并所有**可用**表情（启用的内置在前 + 自定义在后）；选择器/列表用，渲染请走 [stickerInfo]。 */
    fun allStickers(customStickers: List<CustomStickerEntity>, disabled: Set<String>): List<StickerInfo> =
        BuiltInStickerCatalog.enabled(disabled) + customStickers.map { it.toStickerInfo() }

    // MARK: - 自定义表情短别名（alias ↔ UUID）

    /**
     * 为自定义表情生成短别名映射（alias → UUID），1:1 iOS `buildCustomStickerAliasMap`。
     * 别名 `c_` + 清洗后名称；重名追加 `_2`/`_3`；名称为空回退 `c_表情包`。
     * 清洗：删 `[ ] :` + 过滤所有 Unicode 空白（`Char.isWhitespace()` 含 tab/换行/全角空格 　）。
     */
    fun buildCustomStickerAliasMap(customStickers: List<CustomStickerEntity>): Map<String, String> {
        val aliasToUuid = LinkedHashMap<String, String>()
        val nameCount = HashMap<String, Int>()
        for (sticker in customStickers) {
            val sanitized = sticker.name
                .replace("[", "")
                .replace("]", "")
                .replace(":", "")
                .filter { !it.isWhitespace() }
            val baseName = sanitized.ifEmpty { "表情包" }
            val count = (nameCount[baseName] ?: 0) + 1
            nameCount[baseName] = count
            val alias = if (count == 1) "c_$baseName" else "c_${baseName}_$count"
            aliasToUuid[alias] = sticker.stickerUuid
        }
        return aliasToUuid
    }

    /**
     * uuid → 短别名反查表（K8·2026-07-12 性能线程专项）：= [buildCustomStickerAliasMap] 的反转，重复 UUID
     * 保留首个（对齐 iOS `uniquingKeysWith { first }`）。供 [convertUUIDToAlias] 重载与 [buildStickerListForPrompt]
     * 复用——按历史消息逐条转换时**在循环外建一次**，别每条消息重建整表。
     */
    fun buildUuidToAliasMap(customStickers: List<CustomStickerEntity>): Map<String, String> {
        val aliasMap = buildCustomStickerAliasMap(customStickers)
        val uuidToAlias = HashMap<String, String>()
        for ((alias, uuid) in aliasMap) if (!uuidToAlias.containsKey(uuid)) uuidToAlias[uuid] = alias
        return uuidToAlias
    }

    /**
     * AI 回复里 `c_xx` → 真实 UUID（1:1 iOS `convertAliasToUUID`）。
     * 必须在 [stripInvalidStickerTags] **之前**调，使转换后的 UUID 能过校验。不在映射中的 ID 原样保留。
     */
    fun convertAliasToUUID(content: String, customStickers: List<CustomStickerEntity>): String {
        val aliasMap = buildCustomStickerAliasMap(customStickers)
        if (aliasMap.isEmpty()) return content
        val ids = StickerTagParser.extractStickerIds(content)
        if (ids.isEmpty()) return content
        var result = content
        for (id in ids) {
            val uuid = aliasMap[id] ?: continue
            result = result.replace("[sticker:$id]", "[sticker:$uuid]")
        }
        return result
    }

    /**
     * 历史 assistant 消息里 UUID → 别名（1:1 iOS `convertUUIDToAlias`），喂 prompt 防 AI 照抄 UUID。
     * 内置 ID 与未知 UUID 原样保留。单条便捷版；批量逐条转换请预建表走重载（K8）。
     */
    fun convertUUIDToAlias(content: String, customStickers: List<CustomStickerEntity>): String =
        convertUUIDToAlias(content, buildUuidToAliasMap(customStickers))

    /** 预建反查表版（K8）：调用方循环外 [buildUuidToAliasMap] 一次，循环内逐条转换。输出与便捷版逐字节同（测试锁）。 */
    fun convertUUIDToAlias(content: String, uuidToAlias: Map<String, String>): String {
        if (uuidToAlias.isEmpty()) return content
        val ids = StickerTagParser.extractStickerIds(content)
        if (ids.isEmpty()) return content
        var result = content
        for (id in ids) {
            val alias = uuidToAlias[id] ?: continue
            result = result.replace("[sticker:$id]", "[sticker:$alias]")
        }
        return result
    }

    /**
     * 用户消息里 `[sticker:ID]` → `[非语言情绪：semanticDescription]`（1:1 iOS
     * `convertStickerTagsToDescription`）。只用 semanticDescription（不含 name、不含「表情包」字眼），
     * 未知 ID 保留原文。
     */
    fun convertStickerTagsToDescription(content: String, customStickers: List<CustomStickerEntity>): String {
        val ids = StickerTagParser.extractStickerIds(content)
        if (ids.isEmpty()) return content
        var result = content
        for (id in ids) {
            val info = stickerInfo(id, customStickers) ?: continue // 未知表情包保留原文
            result = result.replace("[sticker:$id]", "[非语言情绪：${info.semanticDescription}]")
        }
        return result
    }

    // MARK: - 无效标签清洗

    /** 含尾空白的标签正则（剥除用）。 */
    private val tagWithTrailingWhitespace = Regex("""\[sticker:([^\]\s]+)\]\s*""")

    /**
     * 剥掉无效 `[sticker:xxx]`（1:1 iOS `stripInvalidStickerTags`）。校验源 = 内置全集 + 当前自定义，
     * 与历史消息渲染一致（不误删用户隐藏的内置表情）。从后往前剥，含尾空白，最后 trim。
     */
    fun stripInvalidStickerTags(content: String, customStickers: List<CustomStickerEntity>): String {
        val ids = StickerTagParser.extractStickerIds(content)
        if (ids.isEmpty()) return content
        val invalid = HashSet<String>()
        for (id in ids) if (id !in invalid && stickerInfo(id, customStickers) == null) invalid.add(id)
        if (invalid.isEmpty()) return content
        val matches = tagWithTrailingWhitespace.findAll(content).toList()
        if (matches.isEmpty()) return content
        val sb = StringBuilder(content)
        for (match in matches.reversed()) {
            val id = match.groupValues[1]
            if (id in invalid) sb.replace(match.range.first, match.range.last + 1, "")
        }
        return sb.toString().trim()
    }

    /**
     * AI 回复落库前的表情包归一（1:1 iOS `ChatViewModel+PostProcess` 第三道防线，:167-181）。
     * 开关开：`c_别名` → 真实 UUID，再剥掉 AI 凭空发明的无效标签（与历史渲染同一全集校验）。
     * 开关关：全剥，兜死「AI 偶发凭记忆说出真实 ID」的漏网。聊天与忙碌回复共用此一处逻辑。
     */
    fun normalizeAssistantStickerTags(
        content: String,
        customStickers: List<CustomStickerEntity>,
        allowStickers: Boolean,
    ): String = if (allowStickers) {
        stripInvalidStickerTags(convertAliasToUUID(content, customStickers), customStickers)
    } else {
        stripAllStickerTags(content)
    }

    /** 全剥所有 `[sticker:xxx]`（1:1 iOS `stripAllStickerTags`），开关关闭时兜底。 */
    fun stripAllStickerTags(content: String): String {
        if (!content.contains("[sticker:")) return content
        val regex = Regex("""\[sticker:[^\]\s]+\]\s*""")
        val matches = regex.findAll(content).toList()
        if (matches.isEmpty()) return content
        val sb = StringBuilder(content)
        for (match in matches.reversed()) sb.replace(match.range.first, match.range.last + 1, "")
        return sb.toString().trim()
    }

    // MARK: - 提示词列表

    /**
     * 生成提示词表情包列表文本（1:1 iOS `buildStickerListForPrompt`）。
     * 内置只列**启用**的：`- {id}：{semantic}`；自定义用短别名：`- {alias}：{effectiveDescription}（用户添加）`。
     */
    fun buildStickerListForPrompt(customStickers: List<CustomStickerEntity>, disabled: Set<String>): String {
        val lines = ArrayList<String>()
        for (s in BuiltInStickerCatalog.enabled(disabled)) {
            lines.add("- ${s.id}：${s.semanticDescription}")
        }
        val uuidToAlias = buildUuidToAliasMap(customStickers) // K8：反转逻辑单源
        for (s in customStickers) {
            val alias = uuidToAlias[s.stickerUuid] ?: s.stickerUuid
            lines.add("- $alias：${s.effectiveDescription}（用户添加）")
        }
        return lines.joinToString("\n")
    }

    // MARK: - 渲染源解析（纯函数；解码在 StickerImageStore / 渲染层）

    /** GIF 魔数预筛（纯函数，1:1 iOS `isAnimatedGIFData` 第一关）：≥6 字节且前三字节为 "GIF"。 */
    fun looksLikeGifHeader(bytes: ByteArray): Boolean =
        bytes.size >= 6 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()

    /**
     * 解析某 sticker 的图片来源（内置 asset / 自定义文件 / 找不到）。校验/渲染用**全集** byId。
     * 返回 null = 既非内置也非当前自定义 → 渲染层坍缩为空（对齐 iOS `.failed` → EmptyView）。
     */
    fun resolveSource(stickerId: String, customStickers: List<CustomStickerEntity>): StickerSource? {
        BuiltInStickerCatalog.byId[stickerId]?.let { info ->
            val path = BuiltInStickerCatalog.assetPath(stickerId) ?: return null
            return StickerSource.Asset(path, info.isAnimated)
        }
        val custom = customStickers.firstOrNull { it.stickerUuid == stickerId } ?: return null
        return StickerSource.CustomFile(custom.imagePath, custom.isAnimated)
    }
}
