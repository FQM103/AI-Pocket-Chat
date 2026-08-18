package com.situ.aichat.prompt.growth

import android.util.Log
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.MilestoneDao
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.maintenance.MaintenanceThrottleStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * T2-5（图纸 §7）：[RelationshipArchetypeCalibrator.runStartupSweepIfNeeded] 指纹驱动全量扫（D-14）
 * + 指纹确定性/敏感性 + E12 中途抛出戳未写 + E15 空表仍写戳 + 进程内一次闩短路。recalibrateAll 打 Log → mockkStatic。
 * 微图纸「指纹五项加固」追加：APK 时间戳快路三态（快路零指纹读取 / 慢路指纹同仅补 APK 戳 / provider=0 恒慢路不写 APK 戳）。
 */
class ArchetypeSweepTest {

    private val key = MaintenanceThrottleStore.KEY_ARCHETYPE_CALIBRATION_FINGERPRINT
    private val apkKey = MaintenanceThrottleStore.KEY_ARCHETYPE_APK_STAMP
    private lateinit var dao: CharacterDao
    private lateinit var milestoneDao: MilestoneDao
    private lateinit var throttle: MaintenanceThrottleStore
    private lateinit var lexicon: RelationshipLexicon
    private lateinit var calibrator: RelationshipArchetypeCalibrator

    /** provider=0（取包信息失败态）= 恒慢路，既有慢路用例语义不变。快路用例自建非零 provider 实例（闩 per 实例）。 */
    private fun newCalibrator(apkTime: Long) =
        RelationshipArchetypeCalibrator(dao, CharacterWriteLock(), lexicon, throttle, milestoneDao, apkLastUpdateTime = { apkTime })

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any(), any<Throwable>()) } returns 0
        dao = mockk(relaxed = true)
        milestoneDao = mockk(relaxed = true)
        throttle = mockk(relaxed = true)
        lexicon = RelationshipLexicon.fromRawText(File("src/main/assets/growth/relationship_lexicon.tsv").readText())
        calibrator = newCalibrator(apkTime = 0L)
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    @Test fun `E15 指纹不同且空表 - 扫且完成后写指纹戳`() = runTest {
        coEvery { dao.getAll() } returns emptyList()
        every { throttle.readTextStamp(key) } returns "" // 不同 → 扫
        calibrator.runStartupSweepIfNeeded()
        coVerify(exactly = 1) { dao.getAll() } // 扫了（哪怕 0 角色）
        verify(exactly = 1) { throttle.writeTextStamp(key, calibrator.calibrationFingerprint()) } // 完成后写戳
    }

    @Test fun `指纹相同 - 不扫不写戳`() = runTest {
        every { throttle.readTextStamp(key) } returns calibrator.calibrationFingerprint()
        calibrator.runStartupSweepIfNeeded()
        coVerify(exactly = 0) { dao.getAll() }
        verify(exactly = 0) { throttle.writeTextStamp(any(), any()) }
    }

    @Test fun `E12 中途抛出 - 戳未写`() = runTest {
        coEvery { dao.getAll() } throws RuntimeException("扫到一半崩")
        every { throttle.readTextStamp(key) } returns ""
        var threw = false
        try { calibrator.runStartupSweepIfNeeded() } catch (e: RuntimeException) { threw = true }
        assertTrue("扫中途异常应上抛", threw)
        verify(exactly = 0) { throttle.writeTextStamp(any(), any()) } // 戳未写 → 下次冷启重扫
    }

    @Test fun `进程内一次 - 第二次调用被闩短路`() = runTest {
        coEvery { dao.getAll() } returns emptyList()
        every { throttle.readTextStamp(key) } returns ""
        calibrator.runStartupSweepIfNeeded()
        calibrator.runStartupSweepIfNeeded() // 第二次短路
        verify(exactly = 1) { throttle.readTextStamp(key) } // 只有首次抵达
    }

    @Test fun `指纹确定性`() {
        assertEquals(calibrator.calibrationFingerprint(), calibrator.calibrationFingerprint())
        assertEquals(RelationshipArchetypeCalibrator.sha256Hex("x"), RelationshipArchetypeCalibrator.sha256Hex("x"))
    }

    @Test fun `指纹敏感性 - 改地板值或词条摘要则变`() {
        val base = RelationshipArchetypeCalibrator.calibrationCanonicalInput("LEXICON_DIGEST_A")
        assertTrue("规范串含算法修订号", base.contains("algo=1"))
        assertTrue("规范串含 LOVER 逐维地板", base.contains("LOVER:55,30,50,45,45,40,0,0;"))
        assertTrue("规范串含词条摘要段", base.contains("lexicon=sha256:LEXICON_DIGEST_A"))
        // 改任一地板数字 → 指纹变
        val floorTweaked = base.replaceFirst("55,30,50", "55,30,51")
        assertNotEquals(base, floorTweaked)
        assertNotEquals(RelationshipArchetypeCalibrator.sha256Hex(base), RelationshipArchetypeCalibrator.sha256Hex(floorTweaked))
        // 词条摘要变（= 有效词条流变）→ 指纹变
        val diffLex = RelationshipArchetypeCalibrator.calibrationCanonicalInput("LEXICON_DIGEST_B")
        assertNotEquals(RelationshipArchetypeCalibrator.sha256Hex(base), RelationshipArchetypeCalibrator.sha256Hex(diffLex))
    }

    // ── 微图纸「指纹五项加固」③：APK 时间戳快路三态 ────────────────────────────────────

    @Test fun `快路 - APK 戳相同则零指纹读取零扫`() = runTest {
        every { throttle.readTextStamp(apkKey) } returns "12345"
        val fast = newCalibrator(apkTime = 12345L)
        fast.runStartupSweepIfNeeded()
        verify(exactly = 1) { throttle.readTextStamp(apkKey) }
        verify(exactly = 0) { throttle.readTextStamp(key) } // 连指纹戳都不读 = 未读词表未算哈希
        coVerify(exactly = 0) { dao.getAll() }
        verify(exactly = 0) { throttle.writeTextStamp(any(), any()) }
    }

    @Test fun `慢路 - APK 戳变但指纹相同 - 不扫仅补 APK 戳`() = runTest {
        every { throttle.readTextStamp(apkKey) } returns "" // 首次/重装
        val slow = newCalibrator(apkTime = 12345L)
        every { throttle.readTextStamp(key) } returns slow.calibrationFingerprint() // 内容没变
        slow.runStartupSweepIfNeeded()
        coVerify(exactly = 0) { dao.getAll() }
        verify(exactly = 0) { throttle.writeTextStamp(key, any()) } // 指纹戳不重写
        verify(exactly = 1) { throttle.writeTextStamp(apkKey, "12345") } // 补 APK 戳 → 下次走快路
    }

    @Test fun `慢路 - 指纹变 - 扫且指纹戳先写 APK 戳后写`() = runTest {
        coEvery { dao.getAll() } returns emptyList()
        every { throttle.readTextStamp(apkKey) } returns ""
        every { throttle.readTextStamp(key) } returns ""
        val slow = newCalibrator(apkTime = 777L)
        slow.runStartupSweepIfNeeded()
        coVerify(exactly = 1) { dao.getAll() }
        // T5 复核 🟡-1:写序是锁定项(fp 先、apk 后)——verifyOrder 钉死,防"APK 戳提前写"回归
        // (倒序 + 扫中途死 = APK 戳已落而指纹戳未落 → 下次快路永久跳过重扫,恰是写序要防的洞)。
        verifyOrder {
            throttle.writeTextStamp(key, slow.calibrationFingerprint())
            throttle.writeTextStamp(apkKey, "777")
        }
        verify(exactly = 1) { throttle.writeTextStamp(key, any()) }
        verify(exactly = 1) { throttle.writeTextStamp(apkKey, any()) }
    }

    @Test fun `慢路 - apkTime大于0且扫中途抛 - 双戳全零写`() = runTest {
        // T5 复核 🟡-1 补口:既有 E12 用例是 provider=0(APK 戳写本被闸死),测不出"扫中途抛时 APK 戳提前落"。
        coEvery { dao.getAll() } throws RuntimeException("扫到一半崩")
        every { throttle.readTextStamp(apkKey) } returns ""
        every { throttle.readTextStamp(key) } returns ""
        val slow = newCalibrator(apkTime = 999L)
        var threw = false
        try { slow.runStartupSweepIfNeeded() } catch (e: RuntimeException) { threw = true }
        assertTrue("扫中途异常应上抛", threw)
        verify(exactly = 0) { throttle.writeTextStamp(any(), any()) } // 指纹戳与 APK 戳都不许落
    }

    @Test fun `provider 返回 0 - 恒慢路且不写 APK 戳`() = runTest {
        coEvery { dao.getAll() } returns emptyList()
        every { throttle.readTextStamp(key) } returns ""
        calibrator.runStartupSweepIfNeeded() // setup 实例 provider=0
        coVerify(exactly = 1) { dao.getAll() } // 走了慢路
        verify(exactly = 1) { throttle.writeTextStamp(key, any()) }
        verify(exactly = 0) { throttle.writeTextStamp(apkKey, any()) } // 防 0==0 永久卡快路
    }
}
