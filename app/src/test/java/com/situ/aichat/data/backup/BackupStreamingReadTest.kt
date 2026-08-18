package com.situ.aichat.data.backup

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * T1-1 / T1-2（性能专项卷 A 图纸 §7）：[BackupArchive] **两遍流式读**的契约。
 *
 * 断言从图纸 §3.2 的锁定契约与 §5 边界表独立反推（不照抄实现）：
 * - 遍一 `consumeManifest`：manifest 首位/非首位都拿得到（E2/B5）；**只读到 manifest 就停手**（不整读整包 = 本卷病根）；
 *   不是 zip / 没有 manifest / 打不开流 → null（走 legacy 回退，E5）；consume 自己抛出的异常**原样上抛不吞成 null**（E6）。
 * - 遍二 `forEachMediaEntry`：逐条 (key, readBytes) 回调、`media/` 前缀之外不回调；**不调 readBytes 就一个字节都不读**
 *   （E10 跳过策略的底座）；zip 截断 → 异常上抛（E8）；空包零回调（E20）。
 */
@OptIn(ExperimentalSerializationApi::class)
class BackupStreamingReadTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = true }

    private val manifestJson =
        json.encodeToString(BackupPackage.serializer(), BackupPackage(manifest = BackupManifest(version = 2, mediaCount = 1)))

    // ── 造 zip：条目顺序完全由调用方决定（要能造出「manifest 不在首位」的用户重打包包） ──
    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** 数一数「从源头真的拉走了多少字节」——「只读到 manifest 就停手」这类主张只有量出来才算数。 */
    private class CountingInputStream(private val delegate: InputStream) : InputStream() {
        var bytesRead = 0L
            private set

        override fun read(): Int = delegate.read().also { if (it >= 0) bytesRead++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            delegate.read(b, off, len).also { if (it > 0) bytesRead += it }

        override fun close() = delegate.close()
    }

    private fun manifestText(bytes: ByteArray): String? =
        BackupArchive.consumeManifest({ bytes.inputStream() }) { it.readBytes().decodeToString() }

    // ── 遍一：consumeManifest ──

    @Test fun `manifest 在首位时只读到它就停手_不整读整包`() {
        // 2MB 随机（不可压缩）载荷排在 manifest 之后：流式实现拉走的字节应只够 manifest 那一条，
        // 旧的「整包 readBytes + 全量解压」实现会把 2MB 全拉进来 —— 这条就是本卷病根的回归钉。
        val payload = Random(20260802).nextBytes(2 * 1024 * 1024)
        val bytes = zipOf(
            BackupArchive.MANIFEST_ENTRY to manifestJson.encodeToByteArray(),
            "${BackupArchive.MEDIA_PREFIX}audio/big.wav" to payload,
        )
        assertTrue("包本身应远大于 manifest（否则这条测试没有区分力）", bytes.size > 1_000_000)

        val opened = ArrayList<CountingInputStream>()
        val text = BackupArchive.consumeManifest(
            { CountingInputStream(bytes.inputStream()).also { opened.add(it) } },
        ) { it.readBytes().decodeToString() }

        assertEquals(manifestJson, text)
        val pulled = opened.single().bytesRead
        assertTrue("只该拉走 manifest 那一小段，实拉 $pulled 字节", pulled < 64 * 1024)
    }

    @Test fun `manifest 不在首位也照样拿得到（用户解压重打包_E2）`() {
        val bytes = zipOf(
            "${BackupArchive.MEDIA_PREFIX}avatars/a.jpg" to byteArrayOf(1, 2, 3),
            "${BackupArchive.MEDIA_PREFIX}audio/b.wav" to byteArrayOf(4, 5),
            BackupArchive.MANIFEST_ENTRY to manifestJson.encodeToByteArray(),
        )

        assertEquals(manifestJson, manifestText(bytes))
    }

    @Test fun `manifest 那一条能直接喂给解析器（不物化字符串）`() {
        val pkg = BackupArchive.consumeManifest({ zipOf(BackupArchive.MANIFEST_ENTRY to manifestJson.encodeToByteArray()).inputStream() }) {
            json.decodeFromStream(BackupPackage.serializer(), it)
        }

        assertNotNull(pkg)
        assertEquals(2, pkg!!.manifest.version)
    }

    @Test fun `zip 里没有 manifest 返回 null（E5）`() {
        val bytes = zipOf("${BackupArchive.MEDIA_PREFIX}avatars/a.jpg" to byteArrayOf(1))

        assertNull(manifestText(bytes))
    }

    @Test fun `不是 zip 返回 null（走旧 json 回退路）`() {
        assertNull(manifestText("not a zip, just text".encodeToByteArray()))
    }

    @Test fun `打不开流返回 null`() {
        assertNull(BackupArchive.consumeManifest({ null }) { it.readBytes().decodeToString() })
    }

    @Test fun `manifest 的 JSON 损坏时异常原样上抛_绝不吞成 null（E6）`() {
        // 「损坏的备份」与「根本不是 zip」必须分得开：吞成 null 会让损坏包误入 legacy 路、报出风马牛不相及的错。
        val bytes = zipOf(BackupArchive.MANIFEST_ENTRY to "{ 这不是合法 JSON".encodeToByteArray())

        var thrown: Throwable? = null
        try {
            BackupArchive.consumeManifest({ bytes.inputStream() }) { json.decodeFromStream(BackupPackage.serializer(), it) }
        } catch (e: Exception) {
            thrown = e
        }

        assertNotNull("consume 抛出的必须原样上抛", thrown)
    }

    // ── 遍二：forEachMediaEntry ──

    @Test fun `逐条给出 key 与字节_media 前缀之外的条目不回调`() {
        val a = byteArrayOf(1, 2, 3, 0, -1, 127, -128)
        val b = byteArrayOf(9, 8, 7)
        val bytes = zipOf(
            BackupArchive.MANIFEST_ENTRY to manifestJson.encodeToByteArray(),
            "${BackupArchive.MEDIA_PREFIX}audio/a.mp3" to a,
            "notes/readme.txt" to byteArrayOf(42), // 非 media 前缀 → 不回调
            "${BackupArchive.MEDIA_PREFIX}avatars/x.jpg" to b,
        )

        val seen = LinkedHashMap<String, ByteArray>()
        BackupArchive.forEachMediaEntry({ bytes.inputStream() }) { key, readBytes -> seen[key] = readBytes() }

        assertEquals(listOf("${BackupArchive.MEDIA_PREFIX}audio/a.mp3", "${BackupArchive.MEDIA_PREFIX}avatars/x.jpg"), seen.keys.toList())
        assertArrayEquals(a, seen["${BackupArchive.MEDIA_PREFIX}audio/a.mp3"])
        assertArrayEquals(b, seen["${BackupArchive.MEDIA_PREFIX}avatars/x.jpg"])
    }

    @Test fun `不调 readBytes 的条目一个字节都不读（E10 跳过策略的底座）`() {
        // 「跳过」的条目照样回调一次（进度要计数），但字节只在调用方索取时才解压——materialize 的开关握在调用方手里。
        val big = Random(20260803).nextBytes(1024 * 1024)
        val small = byteArrayOf(7, 7, 7)
        val bytes = zipOf(
            BackupArchive.MANIFEST_ENTRY to manifestJson.encodeToByteArray(),
            "${BackupArchive.MEDIA_PREFIX}audio/skip.wav" to big,
            "${BackupArchive.MEDIA_PREFIX}avatars/keep.jpg" to small,
        )

        val callbackKeys = ArrayList<String>()
        val materialized = LinkedHashMap<String, Int>()
        BackupArchive.forEachMediaEntry({ bytes.inputStream() }) { key, readBytes ->
            callbackKeys.add(key)
            if (key.endsWith("keep.jpg")) materialized[key] = readBytes().size
        }

        assertEquals(2, callbackKeys.size) // 跳过项也回调（进度 done 才收敛到 total）
        assertEquals(mapOf("${BackupArchive.MEDIA_PREFIX}avatars/keep.jpg" to small.size), materialized)
    }

    @Test fun `空包（只有 manifest_零媒体）遍二零回调（E20）`() {
        val bytes = zipOf(BackupArchive.MANIFEST_ENTRY to manifestJson.encodeToByteArray())

        var calls = 0
        BackupArchive.forEachMediaEntry({ bytes.inputStream() }) { _, _ -> calls++ }

        assertEquals(0, calls)
    }

    @Test fun `打不开流时遍二静默返回`() {
        var calls = 0
        BackupArchive.forEachMediaEntry({ null }) { _, _ -> calls++ }

        assertEquals(0, calls)
    }

    @Test fun `媒体区截断时异常上抛（E8）`() {
        val full = zipOf(
            BackupArchive.MANIFEST_ENTRY to manifestJson.encodeToByteArray(),
            "${BackupArchive.MEDIA_PREFIX}audio/a.wav" to Random(20260804).nextBytes(256 * 1024),
        )
        val truncated = full.copyOf(full.size / 2) // 中央目录连同半条媒体一起砍掉

        var thrown: Throwable? = null
        try {
            BackupArchive.forEachMediaEntry({ truncated.inputStream() }) { _, readBytes -> readBytes() }
        } catch (e: Exception) {
            thrown = e
        }

        assertNotNull("截断的包必须炸出来（调用方据此中止导入），不许静默当成读完了", thrown)
    }

    @Test fun `进度计数逐条递增且收敛到 manifest 记的媒体总数（E19）`() {
        val total = 3
        val entries = (0 until total).map { i -> "${BackupArchive.MEDIA_PREFIX}audio/m$i.wav" to byteArrayOf(i.toByte()) }
        val bytes = zipOf(BackupArchive.MANIFEST_ENTRY to manifestJson.encodeToByteArray(), *entries.toTypedArray())

        val done = ArrayList<Int>()
        var counter = 0
        BackupArchive.forEachMediaEntry({ bytes.inputStream() }) { _, _ -> done.add(++counter) }

        assertEquals(listOf(1, 2, 3), done) // 单调递增、每条恰一次 → 收敛到 total
    }
}
