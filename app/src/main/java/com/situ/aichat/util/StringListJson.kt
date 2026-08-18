package com.situ.aichat.util

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Tiny codec for a `List<String>` stored as a JSON string column (the project's convention for
 * collection columns — same idea as `GrowthJson`, no Room `TypeConverter`). Used for moment/diary
 * image-path lists. Empty list ⇄ `""` so an empty column reads back cleanly; decode never throws
 * (bad data → empty list).
 */
object StringListJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(list: List<String>): String =
        if (list.isEmpty()) "" else runCatching { json.encodeToString(list) }.getOrDefault("")

    fun decode(s: String): List<String> =
        if (s.isBlank()) emptyList() else runCatching { json.decodeFromString<List<String>>(s) }.getOrDefault(emptyList())
}
