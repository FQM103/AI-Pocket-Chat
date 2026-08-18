package com.situ.aichat.ui.world.eggnest

import android.os.Looper
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.PetDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.CharacterPetEntity
import androidx.datastore.preferences.core.emptyPreferences
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.pet.EggNestPhrase
import com.situ.aichat.pet.EggNestService
import com.situ.aichat.pet.EggNestState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [EggNestViewModel] T2-2（图纸 §7·Robolectric + MockK DAO/service）：候选装配（有宠禁选文案 / 无宠短语+percent /
 * 排序）+ setPact 恰一次（E4）+ 空角色（E5）+ 全有宠（E6）。断言从图纸 §3 候选规则独立反推。
 * WhileSubscribed 需订阅者才开闸 → CoroutineScope(Main)+idle() 驱动（同 MomentsHubViewModelTest 惯例）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EggNestViewModelTest {

    private val DAY = 86_400_000L

    private val characters = MutableStateFlow<List<CharacterEntity>>(emptyList())
    private val pets = MutableStateFlow<List<CharacterPetEntity>>(emptyList())
    private val messageDao = mockk<MessageDao>()
    private val service = mockk<EggNestService>()

    private fun newVm(): EggNestViewModel {
        val characterDao = mockk<CharacterDao> { every { observeAll() } returns characters }
        val petDao = mockk<PetDao> { every { observeAll() } returns pets }
        every { service.observeState(any()) } returns MutableStateFlow(EggNestState.Empty)
        coEvery { service.setPact(any(), any()) } returns emptyPreferences()
        return EggNestViewModel(service, characterDao, petDao, messageDao)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** 订阅 candidates（WhileSubscribed 开闸）并泵主循环，跑完 block 再退订。 */
    private fun <T> withSubscriptions(vm: EggNestViewModel, block: () -> T): T {
        val scope = CoroutineScope(Dispatchers.Main + Job())
        scope.launch { vm.candidates.collect {} }
        idle()
        return try { block() } finally { scope.coroutineContext[Job]?.cancel() }
    }

    private fun await(message: String, condition: () -> Boolean) {
        repeat(400) {
            idle()
            if (condition()) return
            Thread.sleep(5)
        }
        error("等待超时：$message")
    }

    // 中档关系（未达标·overallPercent 落 WARMING 带 [0.35,0.75)）：陪伴 7 天 + trust20/fam18/close15 + msg50。
    private fun midCharacter(uuid: String, name: String) = CharacterEntity(
        uuid = uuid, name = name, creationDate = System.currentTimeMillis() - 6 * DAY,
        relationshipQualityJSON = GrowthJson.encode(RelationshipQuality(familiarity = 18, trust = 20, closeness = 15)),
    )

    private fun plainCharacter(uuid: String, name: String) =
        CharacterEntity(uuid = uuid, name = name, creationDate = 0L)

    private fun petFor(uuid: String, petName: String) =
        CharacterPetEntity(uuid = "pet-$uuid", name = petName, characterUuid = uuid)

    @Test
    fun `candidates assemble has-pet locked and no-pet phrase with percent and ordering`() {
        val vm = newVm()
        characters.value = listOf(midCharacter("uA", "小K"), plainCharacter("uB", "林"))
        pets.value = listOf(petFor("uB", "团子"))
        coEvery { messageDao.countAllForCharacter("uA") } returns 50

        withSubscriptions(vm) {
            await("candidates ready") { vm.candidates.value.size == 2 }
            val list = vm.candidates.value
            // 排序：无宠(uA) 在前、有宠(uB) 沉底。
            assertEquals(listOf("uA", "uB"), list.map { it.characterUuid })
            // 无宠者：petName 空 + 朦胧短语 WARMING + percent 落带内。
            val a = list[0]
            assertNull(a.petName)
            assertEquals(EggNestPhrase.WARMING, a.phrase)
            assertTrue("percent in warming band", a.overallPercent > 0.35f && a.overallPercent < 0.75f)
            // 有宠者：petName 带宠名 + 无短语 + percent 0。
            val b = list[1]
            assertEquals("团子", b.petName)
            assertNull(b.phrase)
            assertEquals(0f, b.overallPercent, 0f)
        }
        // 有宠角色跳过 eligibility 取数（不查消息数）。
        coVerify(exactly = 0) { messageDao.countAllForCharacter("uB") }
    }

    @Test
    fun `setPact delegates to service exactly once`() {
        val vm = newVm()
        vm.setPact("uA")
        idle()
        coVerify(exactly = 1) { service.setPact("uA", any()) }
    }

    @Test
    fun `E5 empty characters yields empty candidates`() {
        val vm = newVm()
        characters.value = emptyList()
        pets.value = emptyList()
        withSubscriptions(vm) {
            idle()
            assertTrue(vm.candidates.value.isEmpty())
        }
    }

    @Test
    fun `E6 all have pets sink and none carry phrase`() {
        val vm = newVm()
        characters.value = listOf(plainCharacter("uA", "小K"), plainCharacter("uB", "林"))
        pets.value = listOf(petFor("uA", "饭团"), petFor("uB", "团子"))
        withSubscriptions(vm) {
            await("candidates ready") { vm.candidates.value.size == 2 }
            val list = vm.candidates.value
            assertTrue(list.all { it.petName != null })
            assertTrue(list.all { it.phrase == null })
        }
        coVerify(exactly = 0) { messageDao.countAllForCharacter(any()) }
    }
}
