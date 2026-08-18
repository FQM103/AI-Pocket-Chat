package com.situ.aichat.ui.chat

import android.os.Looper
import com.situ.aichat.world.stage.WorldPresenceLine
import com.situ.aichat.world.stage.WorldStageResolver.PlaceType
import com.situ.aichat.world.stage.WorldStageService
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [ChatWorldStatusViewModel] T2-6（W13 图纸 §7）：五态 → (emoji/text/focusSpec) 映射逐条（含 interior/town spec 格式）+
 * null→pill 为 null。MockK 假 stage service·Robolectric zh-rCN 资源·主循环驱动 refresh。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class ChatWorldStatusViewModelTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val yunye = "city_yunye"

    private fun build(line: WorldPresenceLine?): ChatWorldStatusViewModel {
        val stage = mockk<WorldStageService> {
            coEvery { presenceLineFor("c1", any()) } returns line
        }
        return ChatWorldStatusViewModel(stage, RuntimeEnvironment.getApplication())
    }

    private fun pillFor(line: WorldPresenceLine?): ChatWorldPill? {
        val vm = build(line)
        vm.refresh("c1")
        idle()
        return vm.pill.value
    }

    @Test
    fun `AT_PLACE_emoji按型_text带地名_interior spec`() {
        val p = pillFor(
            WorldPresenceLine(WorldPresenceLine.Kind.AT_PLACE, "拾光咖啡馆", PlaceType.CAFE, "yunye_cafe", yunye, "云野镇", null, null),
        )!!
        assertEquals("☕", p.emoji)
        assertEquals("在拾光咖啡馆", p.text)
        assertEquals("interior:city_yunye:yunye_cafe", p.focusSpec)
    }

    @Test
    fun `AT_HOME_town spec`() {
        val p = pillFor(WorldPresenceLine(WorldPresenceLine.Kind.AT_HOME, null, null, null, yunye, "云野镇", null, null))!!
        assertEquals("🏠", p.emoji)
        assertEquals("在家", p.text)
        assertEquals("town:city_yunye", p.focusSpec)
    }

    @Test
    fun `SLEEPING_town spec`() {
        val p = pillFor(WorldPresenceLine(WorldPresenceLine.Kind.SLEEPING, null, null, null, yunye, "云野镇", null, null))!!
        assertEquals("💤", p.emoji)
        assertEquals("睡着了", p.text)
        assertEquals("town:city_yunye", p.focusSpec)
    }

    @Test
    fun `IN_TOWN_带城名_town spec`() {
        val p = pillFor(WorldPresenceLine(WorldPresenceLine.Kind.IN_TOWN, null, null, null, yunye, "云野镇", null, null))!!
        assertEquals("🏘️", p.emoji)
        assertEquals("在云野镇街上", p.text)
        assertEquals("town:city_yunye", p.focusSpec)
    }

    @Test
    fun `TRAVELING_带目的城_town目的城 spec`() {
        val p = pillFor(
            WorldPresenceLine(WorldPresenceLine.Kind.TRAVELING, null, null, null, yunye, "云野镇", "city_taoqiu", "陶丘"),
        )!!
        assertEquals("🚌", p.emoji)
        assertEquals("在去陶丘的路上", p.text)
        assertEquals("town:city_taoqiu", p.focusSpec)
    }

    @Test
    fun `null位置行_pill为null`() {
        assertNull(pillFor(null))
    }
}
