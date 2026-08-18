package com.situ.aichat.data.worldbook

import com.situ.aichat.data.local.entity.WorldBookEntity
import kotlinx.serialization.json.JsonObject

/**
 * 酒馆独立世界书 JSON 解析（WB2·契约 §3.1-1）：`{"entries": {"<uid>": {...}}}`。
 * 官方文件根级只有 entries；社区工具常附带 name/description 等书级字段——认识的读进书实体，
 * 其余根级字段进书 extraJson（round-trip）。坏条目（非对象）跳过并计数，绝不因单条脏数据拒整本书。
 */
internal object WorldBookStParser {

    private val CONSUMED_ROOT_KEYS = setOf(
        "entries", "name", "description", "scan_depth", "token_budget", "recursive_scanning",
    )

    fun parse(rootObj: JsonObject, fallbackName: String): WorldBookCodec.ParsedWorldBook {
        val entriesObj = rootObj["entries"] as JsonObject
        val book = WorldBookEntity(
            name = rootObj.lenientString("name")?.takeIf { it.isNotBlank() } ?: fallbackName,
            description = rootObj.lenientString("description") ?: "",
            scanDepth = rootObj.lenientInt("scan_depth"),
            tokenBudget = rootObj.lenientInt("token_budget"),
            recursiveScanning = rootObj.lenientBool("recursive_scanning"),
            extraJson = extrasFrom(rootObj, CONSUMED_ROOT_KEYS),
        )

        var skipped = 0
        val entries = buildList {
            entriesObj.entries.forEachIndexed { index, (key, value) ->
                val entryObj = value as? JsonObject
                if (entryObj == null) {
                    skipped++
                    return@forEachIndexed
                }
                add(
                    WorldBookStEntryMapper.map(
                        obj = entryObj,
                        bookUuid = book.uuid,
                        fallbackUid = key.toIntOrNull() ?: index,
                        fallbackDisplayIndex = index,
                    ),
                )
            }
        }
        return WorldBookCodec.ParsedWorldBook(
            book = book,
            entries = WorldBookStEntryMapper.dedupeUids(entries),
            format = WorldBookCodec.WorldBookFormat.ST_STANDALONE,
            skippedEntryCount = skipped,
        )
    }
}
