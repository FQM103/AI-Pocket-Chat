package com.situ.aichat.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [orderCharactersForPicker] 纯函数排序规格（聊天「+」发起聊天选择器·D4）：
 * 有活跃会话的角色按最近消息时间倒序在前，无会话的保持输入次序（= observeAll newest-first）排后。
 */
class ChatPickerOrderTest {

    private data class C(val uuid: String)

    @Test
    fun activeByRecency_then_inactiveKeepInputOrder() {
        // 输入次序（observeAll newest-first）= A, B, C, D；仅 B/D 有活跃会话。
        val all = listOf(C("A"), C("B"), C("C"), C("D"))
        val lastByUuid = mapOf("B" to 300L, "D" to 100L)
        val ordered = orderCharactersForPicker(all) { lastByUuid[it.uuid] }.map { it.uuid }
        // 活跃 B(300) > D(100) 在前；无会话 A、C 保持输入次序排后。
        assertEquals(listOf("B", "D", "A", "C"), ordered)
    }

    @Test
    fun allInactive_keepsInputOrder() {
        val all = listOf(C("A"), C("B"), C("C"))
        val ordered = orderCharactersForPicker(all) { null }.map { it.uuid }
        assertEquals(listOf("A", "B", "C"), ordered)
    }

    @Test
    fun allActive_sortedDescByRecency() {
        val all = listOf(C("A"), C("B"), C("C"))
        val lastByUuid = mapOf("A" to 10L, "B" to 50L, "C" to 30L)
        val ordered = orderCharactersForPicker(all) { lastByUuid[it.uuid] }.map { it.uuid }
        assertEquals(listOf("B", "C", "A"), ordered)
    }

    @Test
    fun equalRecency_keepsInputOrder() {
        // 同一时间戳（边界）→ 稳定排序保留输入次序，不抖动。
        val all = listOf(C("A"), C("B"))
        val lastByUuid = mapOf("A" to 100L, "B" to 100L)
        val ordered = orderCharactersForPicker(all) { lastByUuid[it.uuid] }.map { it.uuid }
        assertEquals(listOf("A", "B"), ordered)
    }

    @Test
    fun emptyInput_returnsEmpty() {
        assertEquals(emptyList<String>(), orderCharactersForPicker(emptyList<C>()) { 1L }.map { it.uuid })
    }
}
