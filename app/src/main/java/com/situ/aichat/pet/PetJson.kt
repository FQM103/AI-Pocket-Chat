package com.situ.aichat.pet

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 宠物 JSON 编解码（和 `GrowthJson`/`StringListJson` 同模式）。`ignoreUnknownKeys` + `coerceInputValues`
 * = iOS `decodeIfPresent` 的向后兼容（旧数据缺字段/多字段/null 都安全回退默认）。解码永不抛（坏数据→默认）。
 */
object PetJson {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    fun decodeMetadata(jsonStr: String): PetMetadata =
        if (jsonStr.isBlank()) PetMetadata.EMPTY
        else runCatching { json.decodeFromString<PetMetadata>(jsonStr) }.getOrDefault(PetMetadata.EMPTY)

    fun encodeMetadata(metadata: PetMetadata): String =
        runCatching { json.encodeToString(metadata) }.getOrDefault("")

    fun decodeGrowthLog(jsonStr: String): List<PetGrowthLogEntry> =
        if (jsonStr.isBlank()) emptyList()
        else runCatching { json.decodeFromString<List<PetGrowthLogEntry>>(jsonStr) }.getOrDefault(emptyList())

    fun encodeGrowthLog(log: List<PetGrowthLogEntry>): String =
        if (log.isEmpty()) "" else runCatching { json.encodeToString(log) }.getOrDefault("")
}
