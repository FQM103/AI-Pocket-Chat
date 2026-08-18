package com.situ.aichat.ui.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 乙 1·T1-B1：滑块触觉判定纯逻辑（E8 格变恰一记 / E9 贴边沿只响一次 / 连续模式中途零震）。
 *
 * 断言从**手感规格**独立反推，不照搬实现：有吸附格就逐格「嗒」、滑到头「咚」一下、
 * 连续滑动中途安静、贴着边反复微调不该一直响。
 */
class SliderHapticLogicTest {

    /** 把一串滑块值按顺序喂进判定链，收集每一步该震什么（模拟真实拖动的连续 onValueChange）。 */
    private fun drag(
        values: List<Float>,
        range: ClosedFloatingPointRange<Float> = 0f..1f,
        steps: Int = 0,
        from: Float = values.first(),
    ): List<SliderHapticEffect> {
        var prev = sliderHapticSnapshot(from, range, steps)
        return values.map { v ->
            val next = sliderHapticSnapshot(v, range, steps)
            sliderHapticEffect(prev, next).also { prev = next }
        }
    }

    // ---- 连续模式（steps == 0）----

    @Test
    fun 连续滑动_中途一记都不震() {
        val effects = drag(listOf(0.2f, 0.3f, 0.4f, 0.5f, 0.6f), from = 0.1f)
        assertEquals(List(5) { SliderHapticEffect.None }, effects)
    }

    @Test
    fun 连续滑动_滑到头咚一下() {
        val effects = drag(listOf(0.5f, 0.9f, 1f), from = 0.1f)
        assertEquals(
            listOf(SliderHapticEffect.None, SliderHapticEffect.None, SliderHapticEffect.Edge),
            effects,
        )
    }

    @Test
    fun E9_贴边反复微调_只在撞上那一下响一次() {
        // 滑到头 → 在边上抖两下（M3 会把值钳在 1f）→ 离开 → 再撞回来
        val effects = drag(listOf(1f, 1f, 1f, 0.8f, 1f), from = 0.5f)
        assertEquals(
            listOf(
                SliderHapticEffect.Edge, // 非贴边 → 贴边：响
                SliderHapticEffect.None, // 已经贴着了：不响
                SliderHapticEffect.None,
                SliderHapticEffect.None, // 离开边
                SliderHapticEffect.Edge, // 再撞一次：响
            ),
            effects,
        )
    }

    @Test
    fun 起点就在最小值_首个事件不该凭空先响一记() {
        // 从 0f（本就贴边）起拖：第一个事件仍在边上 → 不响；这是「用当前值初始化快照」的看门狗。
        assertEquals(listOf(SliderHapticEffect.None), drag(listOf(0f), from = 0f))
    }

    // ---- 吸附格模式（steps > 0）----

    @Test
    fun E8_逐格滑过_每过一格恰一记且不攒队列() {
        // steps=3 → 段数 4 → 格点 fraction = 0 / .25 / .5 / .75 / 1
        val effects = drag(listOf(0.25f, 0.5f, 0.75f), range = 0f..1f, steps = 3, from = 0f)
        assertEquals(List(3) { SliderHapticEffect.Detent }, effects)
    }

    @Test
    fun E8_一次事件跨好几格_也只响一记() {
        val effects = drag(listOf(0.75f), range = 0f..1f, steps = 3, from = 0f)
        assertEquals("快速扫过多格只该响一记，不许攒成一串", listOf(SliderHapticEffect.Detent), effects)
    }

    @Test
    fun 同一格内微动_不响() {
        // 0.26 与 0.27 都量化到同一格（fraction*4 ≈ 1）
        val effects = drag(listOf(0.26f, 0.27f), range = 0f..1f, steps = 3, from = 0.25f)
        assertEquals(List(2) { SliderHapticEffect.None }, effects)
    }

    @Test
    fun 有格模式滑到头_撞墙优先于格变_只响咚不响嗒() {
        val effects = drag(listOf(1f), range = 0f..1f, steps = 3, from = 0.75f)
        assertEquals(
            "到头那一下两个条件同时成立，撞墙更要紧且一次只许响一记",
            listOf(SliderHapticEffect.Edge),
            effects,
        )
    }

    // ---- 非 0..1 值域 / 退化值域 ----

    @Test
    fun 非零起点值域_格与边都按真实值域算() {
        // 语音灵敏度那类 1f..5f、steps=3 的真实用法
        val effects = drag(listOf(2f, 3f, 5f), range = 1f..5f, steps = 3, from = 1f)
        assertEquals(
            listOf(SliderHapticEffect.Detent, SliderHapticEffect.Detent, SliderHapticEffect.Edge),
            effects,
        )
    }

    @Test
    fun 退化值域_不崩不除零() {
        val effects = drag(listOf(1f, 1f), range = 1f..1f, steps = 0, from = 1f)
        // 起点即贴边（start==end），故全程无「非贴边→贴边」的沿 → 不响
        assertEquals(List(2) { SliderHapticEffect.None }, effects)
    }
}
