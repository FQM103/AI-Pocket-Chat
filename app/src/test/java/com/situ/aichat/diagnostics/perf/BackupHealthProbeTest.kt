package com.situ.aichat.diagnostics.perf

import com.situ.aichat.data.backup.BackupArchive
import com.situ.aichat.data.backup.BackupManifest
import com.situ.aichat.data.backup.BackupPackage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * T2-4（图纸 2026-07-30 性能采集与量尺 §7）：[BackupHealthProbe] 只读体检的分支与兜底。
 *
 * 断言从图纸 §5 E12/E13 与 §2.3 的「只读」边界独立反推：
 * - 损坏 zip → `stage="parse_failed"`，不崩；
 * - 撞 `OutOfMemoryError` → `oomCaught=true` 且带上当时的 `fileBytes`（**这正是要量的东西**）；
 * - 正常包 → `stage="done"` 且媒体条数 / manifest 字符数如实；
 * - **零写库**：本类构造函数里根本没有 DAO / Store —— 结构上就没有写库的句柄，比「注入了但保证不调」更硬，
 *   这里再用构造签名钉一道，防后人顺手加依赖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupHealthProbeTest {

    private val app = RuntimeEnvironment.getApplication()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false; isLenient = true }
    private lateinit var collector: PerfCollector
    private lateinit var probe: BackupHealthProbe

    @Before
    fun setUp() {
        collector = mockk(relaxed = true)
        every { collector.newHeader(any()) } answers {
            PerfHeader(PERF_SCHEMA_VERSION, 1_754_000_000_000L, firstArg())
        }
        every { collector.isEnabled } returns true
        probe = BackupHealthProbe(app, collector, json)
    }

    private fun validArchiveBytes(mediaCount: Int = 2): ByteArray {
        val manifest = json.encodeToString(
            BackupPackage.serializer(),
            BackupPackage(manifest = BackupManifest(version = 2, includesMedia = true, mediaCount = mediaCount)),
        )
        val mediaDir = app.cacheDir.resolve("probe_src").apply { mkdirs() }
        val paths = (0 until mediaCount).associate { i ->
            val f = mediaDir.resolve("m$i.bin").apply { writeBytes(ByteArray(64) { it.toByte() }) }
            "${BackupArchive.MEDIA_PREFIX}m$i.bin" to f.absolutePath
        }
        val out = ByteArrayOutputStream()
        out.use { BackupArchive.writeTo(it, manifest, paths) }
        return out.toByteArray()
    }

    @Test
    fun `正常包体检到底_stage 为 done 且统计如实`() = runBlocking {
        val bytes = validArchiveBytes(mediaCount = 3)

        val sample = probe.probeSource(BackupHealthProbe.MODE_READONLY, bytes.size.toLong()) { bytes.inputStream() }

        assertEquals(BackupHealthProbe.STAGE_DONE, sample.stage)
        assertFalse(sample.oomCaught)
        assertEquals(3, sample.mediaEntryCount)
        assertTrue("manifest 字符数应大于 0", sample.manifestChars > 0)
        assertEquals(bytes.size.toLong(), sample.fileBytes)
        assertEquals(PerfSampleKind.BACKUP_PROBE, sample.header.kind)
        assertTrue("应记下堆上限", sample.maxHeapBytes > 0)
    }

    @Test
    fun `损坏 zip 停在 parse_failed 且不崩（E12）`() = runBlocking {
        val garbage = "这不是一个 zip 包".toByteArray()

        val sample = probe.probeSource(BackupHealthProbe.MODE_READONLY, garbage.size.toLong()) { garbage.inputStream() }

        assertEquals(BackupHealthProbe.STAGE_PARSE_FAILED, sample.stage)
        assertFalse(sample.oomCaught)
        assertEquals(0, sample.mediaEntryCount)
    }

    @Test
    fun `zip 正常但 manifest 解不出也停在 parse_failed`() = runBlocking {
        val out = ByteArrayOutputStream()
        out.use { BackupArchive.writeTo(it, "{ 不是合法 JSON", emptyMap()) }
        val bytes = out.toByteArray()

        val sample = probe.probeSource(BackupHealthProbe.MODE_READONLY, bytes.size.toLong()) { bytes.inputStream() }

        assertEquals(BackupHealthProbe.STAGE_PARSE_FAILED, sample.stage)
        assertTrue("解不出也要把已知统计如实带出", sample.manifestChars > 0)
    }

    @Test
    fun `撞 OOM 时 oomCaught 为真并带上当时的包大小（E13）`() = runBlocking {
        val hugeBytes = 3_000_000_000L

        val sample = probe.probeSource(BackupHealthProbe.MODE_FAKE, hugeBytes) {
            object : InputStream() {
                override fun read(): Int = throw OutOfMemoryError("Failed to allocate")
                override fun read(b: ByteArray, off: Int, len: Int): Int = throw OutOfMemoryError("Failed to allocate")
            }
        }

        assertTrue("撞 OOM 必须如实记下", sample.oomCaught)
        assertEquals("门槛就是靠这个数字钉的", hugeBytes, sample.fileBytes)
        assertEquals(BackupHealthProbe.MODE_FAKE, sample.mode)
        assertEquals(BackupHealthProbe.STAGE_READ, sample.stage)
    }

    @Test
    fun `打不开来源时停在 read_failed`() = runBlocking {
        val sample = probe.probeSource(BackupHealthProbe.MODE_READONLY, BackupHealthProbe.UNKNOWN_SIZE) { null }

        assertEquals(BackupHealthProbe.STAGE_READ_FAILED, sample.stage)
        assertEquals(BackupHealthProbe.UNKNOWN_SIZE, sample.fileBytes)
    }

    @Test
    fun `体检结果会进采集队列并强制落盘`() = runBlocking {
        val bytes = validArchiveBytes()

        probe.probeSource(BackupHealthProbe.MODE_READONLY, bytes.size.toLong()) { bytes.inputStream() }

        val recorded = slot<PerfSample>()
        verify(exactly = 1) { collector.record(capture(recorded)) }
        assertTrue(recorded.captured is PerfSample.BackupProbe)
        verify(exactly = 1) { collector.requestFlush() }
    }

    @Test
    fun `只读铁律_构造依赖里不许出现任何 DAO 或 Store`() {
        val paramTypes = BackupHealthProbe::class.java.declaredConstructors
            .single().parameterTypes.map { it.name }

        val offenders = paramTypes.filter { it.contains("Dao") || it.endsWith("Store") || it.contains("Repository") }
        assertEquals("BackupHealthProbe 只读：不许注入任何写库句柄，实为 $paramTypes", emptyList<String>(), offenders)
    }
}

/**
 * T2-5（图纸 §7）：[FakeBackupBuilder] 造包体积可控 + 失败清理。
 *
 * 假包只含合成随机字节 —— 不碰任何真数据（拍板 4）。随机字节不可压缩，所以 zip 体积≈投入体积，
 * 目标大小才控得住（±10% 是图纸给的验收线）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FakeBackupBuilderTest {

    private val app = RuntimeEnvironment.getApplication()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false; isLenient = true }
    private val builder = FakeBackupBuilder(app, json)

    @Before
    fun setUp() {
        builder.clear()
    }

    @Test
    fun `产出体积落在目标的正负一成内`() = runBlocking {
        val target = 4L * 1024 * 1024

        val file = builder.build(target)!!

        val ratio = file.length().toDouble() / target
        assertTrue("实为 ${file.length()} 字节（目标 $target）", ratio in 0.9..1.1)
    }

    @Test
    fun `产出的假包能被真解析器读回（体检才量得到内存峰值而不是停在解析失败）`() = runBlocking {
        val file = builder.build(1L * 1024 * 1024)!!

        val manifest = BackupArchive.consumeManifest({ file.inputStream() }) { it.readBytes().decodeToString() }
        assertTrue("假包必须是合法容器", manifest != null)
        val pkg = json.decodeFromString(BackupPackage.serializer(), manifest!!)
        assertEquals(2, pkg.manifest.version)
        var mediaEntries = 0
        BackupArchive.forEachMediaEntry({ file.inputStream() }) { _, _ -> mediaEntries++ }
        assertTrue("媒体条目必须真的在包里", mediaEntries > 0)
    }

    /** T2-5（卷 A 图纸 §7）：可导入档必须是**真解析器解得开**的包，且钱段一个都没有（E2E 导入零碰钱路）。 */
    @Test
    fun `可导入档的 manifest 是真角色真消息且钱段全空（T2-5）`() = runBlocking {
        val file = builder.build(1L * 1024 * 1024, importable = true)!!

        val pkg = BackupArchive.consumeManifest({ file.inputStream() }) {
            json.decodeFromString(BackupPackage.serializer(), it.readBytes().decodeToString())
        }!!

        assertEquals(2, pkg.manifest.version)
        assertEquals(3, pkg.characters.size)
        pkg.characters.forEachIndexed { i, cd ->
            assertEquals("fakebkp-$i", cd.character.uuid)
            assertEquals("体检角色$i", cd.character.name)
            assertEquals(20, cd.conversations!!.single().messages!!.size)
            assertTrue("头像键要指向包里真有的条目", cd.character.avatarArchiveKey!!.startsWith("media/avatars/"))
        }
        // 💰 一个都不许有：导入这个假包绝不会走到任何钱的还原分支。
        assertEquals(null, pkg.userWallet)
        assertEquals(null, pkg.currencyTransactions)
        assertEquals(null, pkg.gifts)
        assertEquals(null, pkg.redPackets)
        assertEquals(null, pkg.redeemCodeUsages)
        assertTrue("角色钱包也不许有", pkg.characters.all { it.wallet == null })

        val keys = ArrayList<String>()
        BackupArchive.forEachMediaEntry({ file.inputStream() }) { key, _ -> keys.add(key) }
        assertEquals("manifest 记的媒体数要和包里真有的条目数对得上", keys.size, pkg.manifest.mediaCount)
        assertTrue("体积载荷走音频键（重存是纯字节落盘·不会假失败）", keys.any { it.startsWith("media/audio/pad_") })
    }

    @Test
    fun `同一目标大小两次产出完全一致（固定种子_禁无种随机）`() = runBlocking {
        val a = builder.build(512L * 1024)!!.readBytes()
        builder.clear()
        val b = builder.build(512L * 1024)!!.readBytes()

        assertTrue("固定种子 → 两次字节数相同", a.size == b.size)
    }

    @Test
    fun `清理后目录里不留任何残留`() = runBlocking {
        builder.build(512L * 1024)

        builder.clear()

        assertFalse(app.cacheDir.resolve("perf_fake").exists())
    }

    @Test
    fun `份数换算_目标越大份数越多但有上限`() {
        assertEquals(1, FakeBackupBuilder.entryCountFor(1))
        assertEquals(1, FakeBackupBuilder.entryCountFor(FakeBackupBuilder.BYTES_PER_ENTRY))
        assertEquals(2, FakeBackupBuilder.entryCountFor(FakeBackupBuilder.BYTES_PER_ENTRY + 1))
        assertEquals(4096, FakeBackupBuilder.entryCountFor(Long.MAX_VALUE / 2))
    }
}
