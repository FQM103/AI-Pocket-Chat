package com.situ.aichat.data.backup

import com.situ.aichat.data.model.AppSettings
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 13.6 备份纯函数单测：① [BackupArchive] zip 容器 write↔read 往返忠实；② DTO 序列化往返忠实，
 * **重点锁 💰钱字段在 `encodeDefaults=false` 下不被静默丢失/篡改**（导出端丢「==默认」字段、导入端按默认补回，
 * 两端默认必须一致才不丢钱——本测试即锁住这一不变量）。断言值从 iOS 行为反推（余额是绝对快照、原样往返）。
 */
class BackupRoundTripTest {

    // 与 BackupService 同款 Json 配置（导出/导入双侧共用）。
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = true
    }

    private fun roundTrip(pkg: BackupPackage): BackupPackage =
        json.decodeFromString(BackupPackage.serializer(), json.encodeToString(BackupPackage.serializer(), pkg))

    // ── ① zip 容器（流式 writeTo↔read 往返；13.6c：媒体从磁盘流式写入，不在内存建整包，产出须与 read 字节级一致） ──

    @Test fun archive_read_returnsNull_forNonZip() {
        // 非 zip（旧 .json 文本 / 损坏）→ null，调用方回退旧 .json 解析。
        assertNull(readArchive("not a zip, just text".encodeToByteArray()))
    }

    @Test fun writeTo_streamsMediaFromDisk_roundTripsByteExact() {
        val a = byteArrayOf(1, 2, 3, 0, -1, 127, -128)
        val b = byteArrayOf(9, 8, 7)
        val f1 = tempFileWith("a", ".mp3", a)
        val f2 = tempFileWith("x", ".jpg", b)
        try {
            val manifest = """{"hello":"世界","n":42}"""
            val mediaPaths = linkedMapOf(
                "media/audio/a.mp3" to f1.absolutePath,
                "media/avatars/x.jpg" to f2.absolutePath,
            )
            val bos = ByteArrayOutputStream()
            bos.use { BackupArchive.writeTo(it, manifest, mediaPaths) }
            val (outManifest, outMedia) = readArchive(bos.toByteArray())!!
            assertEquals(manifest, outManifest)
            assertEquals(2, outMedia.size)
            assertArrayEquals(a, outMedia["media/audio/a.mp3"])
            assertArrayEquals(b, outMedia["media/avatars/x.jpg"])
        } finally {
            f1.delete(); f2.delete()
        }
    }

    @Test fun writeTo_skipsBlankKeysAndMissingFiles() {
        val present = tempFileWith("p", ".bin", byteArrayOf(5))
        try {
            val bos = ByteArrayOutputStream()
            bos.use {
                BackupArchive.writeTo(
                    it,
                    "{}",
                    linkedMapOf(
                        "" to present.absolutePath, // 空键跳过
                        "media/gone.jpg" to "/no/such/file/zzz.jpg", // 文件不存在跳过
                        "media/p.bin" to present.absolutePath, // 唯一有效
                    ),
                )
            }
            val (m, media) = readArchive(bos.toByteArray())!!
            assertEquals("{}", m)
            assertEquals(1, media.size)
            assertArrayEquals(byteArrayOf(5), media["media/p.bin"])
        } finally {
            present.delete()
        }
    }

    @Test fun writeTo_progressCallback_doesNotChangeArchiveContent_andCountsMonotonically() {
        // P1-7 格式零碰证据：带/不带 onEntry 两次 writeTo 的 read() 结果内容等价（不比原始字节——ZipEntry
        // 时戳非确定）；回调按条目逐次递增 done≤total，跳过项（空键/文件不存在）也计入 done → 收敛到 total。
        val f = tempFileWith("cb", ".bin", byteArrayOf(7, 7))
        try {
            val mediaPaths = linkedMapOf(
                "media/a.bin" to f.absolutePath,
                "" to f.absolutePath, // 空键 → 跳过但计入 done
                "media/gone.jpg" to "/no/such/file.jpg", // 缺文件 → 跳过但计入 done
            )
            val plain = ByteArrayOutputStream().also { bos ->
                bos.use { BackupArchive.writeTo(it, """{"m":1}""", mediaPaths) }
            }
            val seen = ArrayList<Pair<Int, Int>>()
            val withCb = ByteArrayOutputStream().also { bos ->
                bos.use { BackupArchive.writeTo(it, """{"m":1}""", mediaPaths) { done, total -> seen.add(done to total) } }
            }
            assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), seen)
            val (m1, media1) = readArchive(plain.toByteArray())!!
            val (m2, media2) = readArchive(withCb.toByteArray())!!
            assertEquals(m1, m2)
            assertEquals(media1.keys, media2.keys)
            media1.forEach { (k, v) -> assertArrayEquals(v, media2[k]) }
        } finally {
            f.delete()
        }
    }

    // ── ①b 卷 A·J7：manifest 直写 zip 流的 writer 重载（导出侧不再攒 String）↔ 两遍流式读 往返 ──

    @Test fun writeTo_manifestWriter_roundTripsByteExact_viaStreamingRead() {
        val a = byteArrayOf(1, 2, 3, 0, -1, 127, -128)
        val b = byteArrayOf(9, 8, 7)
        val f1 = tempFileWith("wa", ".mp3", a)
        val f2 = tempFileWith("wx", ".jpg", b)
        try {
            val manifest = """{"hello":"世界","n":42}"""
            val mediaPaths = linkedMapOf(
                "media/audio/a.mp3" to f1.absolutePath,
                "media/avatars/x.jpg" to f2.absolutePath,
            )
            val bos = ByteArrayOutputStream()
            bos.use { out ->
                BackupArchive.writeTo(
                    out,
                    manifestWriter = { it.write(manifest.encodeToByteArray()) },
                    mediaPaths = mediaPaths,
                )
            }
            val bytes = bos.toByteArray()

            val outManifest = BackupArchive.consumeManifest({ bytes.inputStream() }) { it.readBytes().decodeToString() }
            val outMedia = LinkedHashMap<String, ByteArray>()
            BackupArchive.forEachMediaEntry({ bytes.inputStream() }) { key, readBytes -> outMedia[key] = readBytes() }

            assertEquals(manifest, outManifest)
            assertEquals(2, outMedia.size)
            assertArrayEquals(a, outMedia["media/audio/a.mp3"])
            assertArrayEquals(b, outMedia["media/avatars/x.jpg"])
        } finally {
            f1.delete(); f2.delete()
        }
    }

    @Test fun writeTo_manifestWriter_producesSameArchiveAsStringOverload() {
        // B3：改前（String 版）与改后（writer 版）产出的包语义等价——manifest 逐字节同、媒体条目同。
        val f = tempFileWith("eq", ".bin", byteArrayOf(3, 1, 4, 1, 5))
        try {
            val manifest = """{"m":"等价","k":[1,2,3]}"""
            val mediaPaths = linkedMapOf("media/audio/eq.bin" to f.absolutePath)
            val viaString = ByteArrayOutputStream().also { bos ->
                bos.use { BackupArchive.writeTo(it, manifest, mediaPaths) }
            }.toByteArray()
            val viaWriter = ByteArrayOutputStream().also { bos ->
                bos.use { BackupArchive.writeTo(it, { s -> s.write(manifest.encodeToByteArray()) }, mediaPaths) }
            }.toByteArray()

            fun manifestOf(bytes: ByteArray) =
                BackupArchive.consumeManifest({ bytes.inputStream() }) { it.readBytes().decodeToString() }

            fun mediaOf(bytes: ByteArray) = LinkedHashMap<String, ByteArray>().also { m ->
                BackupArchive.forEachMediaEntry({ bytes.inputStream() }) { key, readBytes -> m[key] = readBytes() }
            }

            assertEquals(manifestOf(viaString), manifestOf(viaWriter))
            val m1 = mediaOf(viaString)
            val m2 = mediaOf(viaWriter)
            assertEquals(m1.keys, m2.keys)
            m1.forEach { (k, v) -> assertArrayEquals(v, m2[k]) }
        } finally {
            f.delete()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test fun encodeToStream_matchesEncodeToString_byteForByte_includingMoneyFields() {
        // 💰 B3 的地基：导出侧从 encodeToString 换成 encodeToStream，输出必须**逐字节**不变
        //（同一 Json 配置）。带上钱字段/中文/嵌套段，任一字节漂移即视为导出格式被动过。
        val pkg = BackupPackage(
            userWallet = UserWalletExport(uuid = "u", coinBalance = 0, totalEarned = 50, totalSpent = 50),
            gifts = listOf(GiftRecordExport(uuid = "g", pricePaid = 600, affinityGain = 5, isDIY = true)),
            currencyTransactions = listOf(
                CurrencyTransactionExport(
                    uuid = "t1", timestamp = 1717000000000L, ownerTypeRaw = "character", characterUuid = "c1",
                    kindRaw = "earn", categoryRaw = "salary", amount = 3000, balanceAfter = 3000,
                    relatedEntityId = "salary_c1_202606", note = "六月工资",
                ),
            ),
            characters = listOf(
                CharacterBackupData(character = CharacterExport(uuid = "c", name = "小喵", creationDate = 1L)),
            ),
        )

        val viaString = json.encodeToString(BackupPackage.serializer(), pkg).encodeToByteArray()
        val viaStream = ByteArrayOutputStream().also { json.encodeToStream(BackupPackage.serializer(), pkg, it) }.toByteArray()

        assertArrayEquals(viaString, viaStream)
    }

    // 前缀须 ≥3 字符（File.createTempFile 约束）→ 统一加 "bktest_" 前缀。
    private fun tempFileWith(prefix: String, suffix: String, bytes: ByteArray): File =
        File.createTempFile("bktest_$prefix", suffix).apply { writeBytes(bytes); deleteOnExit() }

    /**
     * 全量解包（原 `BackupArchive.read`·卷 A 从生产码删除后**只搬不改**搬进本测试）：本文件 ① 段锁的是
     * **容器行为**（写进去什么就该读回什么），不是 API 形态——故断言一字不改，只把解包工具挪到测试侧。
     * 生产码走的是两遍流式（`consumeManifest` / `forEachMediaEntry`），另由 BackupStreamingReadTest 锁。
     */
    private fun readArchive(bytes: ByteArray): Pair<String, Map<String, ByteArray>>? {
        var manifest: String? = null
        val media = HashMap<String, ByteArray>()
        runCatching {
            ZipInputStream(bytes.inputStream()).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val content = zis.readBytes()
                        if (entry.name == BackupArchive.MANIFEST_ENTRY) {
                            manifest = content.decodeToString()
                        } else if (entry.name.startsWith(BackupArchive.MEDIA_PREFIX)) {
                            media[entry.name] = content
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }.getOrElse { return null }
        return manifest?.let { it to media }
    }

    // ── ② 💰 钱 DTO 往返（encodeDefaults=false 不丢钱） ──

    @Test fun userWallet_zeroBalance_survivesRoundTrip() {
        // 余额 0（≠ 默认 100）→ 写入 → 还原 0；earned/spent ≠ 默认 → 原样。
        val back = roundTrip(BackupPackage(userWallet = UserWalletExport(uuid = "u", coinBalance = 0, totalEarned = 50, totalSpent = 50)))
        assertEquals(0, back.userWallet!!.coinBalance)
        assertEquals(50, back.userWallet.totalEarned)
        assertEquals(50, back.userWallet.totalSpent)
    }

    @Test fun userWallet_balanceEqualToDefault_survivesRoundTrip() {
        // 余额 100（== DTO 默认）→ encodeDefaults=false 丢字段 → 导入端按同款默认 100 补回 → 仍是 100（不丢钱）。
        val back = roundTrip(BackupPackage(userWallet = UserWalletExport(uuid = "u", coinBalance = 100, totalEarned = 0, totalSpent = 0)))
        assertEquals(100, back.userWallet!!.coinBalance)
    }

    @Test fun characterWallet_allMoneyFields_surviveRoundTrip() {
        val pkg = BackupPackage(
            characters = listOf(
                CharacterBackupData(
                    character = CharacterExport(uuid = "c", name = "小喵", creationDate = 1L),
                    wallet = CharacterWalletExport(
                        uuid = "w", coinBalance = 0, totalEarned = 500, totalSpent = 500,
                        monthlySalary = 8888, salaryInferred = true, salaryDay = 3, affinityFromUser = 42, affinityToUser = 7,
                    ),
                ),
            ),
        )
        val w = roundTrip(pkg).characters[0].wallet!!
        assertEquals(0, w.coinBalance)
        assertEquals(500, w.totalEarned)
        assertEquals(500, w.totalSpent)
        assertEquals(8888, w.monthlySalary)
        assertEquals(true, w.salaryInferred)
        assertEquals(3, w.salaryDay)
        assertEquals(42, w.affinityFromUser)
        assertEquals(7, w.affinityToUser)
    }

    @Test fun giftPrice_and_redPacketAmount_surviveRoundTrip() {
        val pkg = BackupPackage(
            gifts = listOf(GiftRecordExport(uuid = "g", pricePaid = 600, affinityGain = 5, isDIY = true)),
            redPackets = listOf(RedPacketRecordExport(uuid = "r", amount = 888, status = "pending", blessingText = "新年快乐")),
        )
        val back = roundTrip(pkg)
        assertEquals(600, back.gifts!![0].pricePaid)
        assertEquals(5, back.gifts[0].affinityGain)
        assertEquals(888, back.redPackets!![0].amount)
        assertEquals("pending", back.redPackets[0].status)
        assertEquals("新年快乐", back.redPackets[0].blessingText)
    }

    @Test fun currencyTransaction_idempotencyLedger_survivesRoundTrip() {
        // R2：流水台账进备份。relatedEntityId（幂等 key）+ balanceAfter（余额快照）+ 方向/品类必须全量保真——
        // 任一字段丢失/篡改 → 恢复后幂等失效或账本错位。含「==默认」字段（amount=0 的留痕扣款、note=""）锁
        // encodeDefaults=false 不静默吞。
        val pkg = BackupPackage(
            currencyTransactions = listOf(
                // 角色侧发薪：relatedEntityId 是当月幂等 key，恢复后必须存活才不会重发
                CurrencyTransactionExport(
                    uuid = "t1", timestamp = 1717000000000L, ownerTypeRaw = "character", characterUuid = "c1",
                    kindRaw = "earn", categoryRaw = "salary", amount = 3000, balanceAfter = 3000,
                    relatedEntityId = "salary_c1_202606", note = "六月工资",
                ),
                // 0 元欠租留痕（amount=0 走默认值路径）：方向 spend、note 含「欠租」须原样
                CurrencyTransactionExport(
                    uuid = "t2", timestamp = 1717100000000L, ownerTypeRaw = "character", characterUuid = "c1",
                    kindRaw = "spend", categoryRaw = "rent", amount = 0, balanceAfter = 3000,
                    relatedEntityId = "rent_c1_202606", note = "欠租 500（余额不足）",
                ),
                // 用户侧（characterUuid 默认空串、relatedEntityId 为 null）
                CurrencyTransactionExport(
                    uuid = "t3", timestamp = 1717200000000L, kindRaw = "earn", categoryRaw = "redeem",
                    amount = 100, balanceAfter = 100,
                ),
            ),
        )
        val back = roundTrip(pkg).currencyTransactions!!
        assertEquals(3, back.size)
        val t1 = back.first { it.uuid == "t1" }
        assertEquals("salary_c1_202606", t1.relatedEntityId)
        assertEquals(3000, t1.amount); assertEquals(3000, t1.balanceAfter)
        assertEquals("character", t1.ownerTypeRaw); assertEquals("c1", t1.characterUuid)
        assertEquals("salary", t1.categoryRaw)
        val t2 = back.first { it.uuid == "t2" }
        assertEquals(0, t2.amount); assertEquals("rent_c1_202606", t2.relatedEntityId)
        assertEquals("欠租 500（余额不足）", t2.note); assertEquals("spend", t2.kindRaw)
        val t3 = back.first { it.uuid == "t3" }
        assertEquals("user", t3.ownerTypeRaw); assertEquals("", t3.characterUuid)
        assertNull(t3.relatedEntityId)
    }

    // ── ② 结构 / 设置 / 媒体键往返 ──

    @Test fun appSettings_nonDefaultSetting_survivesRoundTrip() {
        // offlineImmersiveInputEnabled 默认 false；设 true → 必须原样还原（不被 encodeDefaults=false 吞）。
        val back = roundTrip(BackupPackage(appSettings = AppSettings(offlineImmersiveInputEnabled = true, moodHistoryMaxCount = 321)))
        assertEquals(true, back.appSettings!!.offlineImmersiveInputEnabled)
        assertEquals(321, back.appSettings.moodHistoryMaxCount)
    }

    @Test fun diaryEntry_authorAndNestedSocial_surviveJsonRoundTrip() {
        // R6-3②：交换日记作者归属 + 嵌套评论线程/点赞 + 顶层月度回顾整包 JSON 往返。
        // encodeDefaults=false 下用非默认值锁住：作者字段、parentCommentId、isFromUser=true 都不被吞。
        val pkg = BackupPackage(
            diaryEntries = listOf(
                DiaryEntryExport(
                    uuid = "d1", content = "TA 的信", triggerTypeRaw = "exchange",
                    authorCharacterUuid = "cA", authorNameSnapshot = "小满",
                    comments = listOf(
                        DiaryCommentExport(id = "cm1", content = "读了你的信", parentCommentId = "root1", isFromUser = true),
                    ),
                    reactions = listOf(DiaryReactionExport(id = "rx1", characterUuid = "cA", emoji = "❤️", timestamp = 5L)),
                ),
            ),
            monthlyReviews = listOf(MonthlyReviewExport(uuid = "mr1", monthStartMillis = 100L, content = "月记")),
        )
        val back = roundTrip(pkg)
        val e = back.diaryEntries!!.single()
        assertEquals("cA", e.authorCharacterUuid)          // 作者归属绝不能丢（否则恢复后 TA 的信变用户日记）
        assertEquals("小满", e.authorNameSnapshot)
        val cm = e.comments!!.single()
        assertEquals("root1", cm.parentCommentId)           // 线程不塌平
        assertEquals(true, cm.isFromUser)                   // 用户回复不被误认成角色评论
        assertEquals("❤️", e.reactions!!.single().emoji)   // 点赞随日记往返
        val review = back.monthlyReviews!!.single()          // 月度回顾顶层段
        assertEquals("mr1", review.uuid)
        assertEquals("月记", review.content)
    }

    @Test fun momentNestedReply_keepsRealUuidLinks_noIndexMapping() {
        // 安卓用真实 parentCommentUuid 关联（非 iOS 下标映射）→ 嵌套回复关系原样往返。
        val pkg = BackupPackage(
            moments = MomentsExport(
                posts = listOf(MomentPostExport(uuid = "p1", content = "帖")),
                comments = listOf(
                    MomentCommentExport(uuid = "c1", content = "顶级", postUuid = "p1"),
                    MomentCommentExport(uuid = "c2", content = "回复", postUuid = "p1", parentCommentUuid = "c1"),
                ),
            ),
        )
        val cs = roundTrip(pkg).moments!!.comments!!
        assertEquals("p1", cs[1].postUuid)
        assertEquals("c1", cs[1].parentCommentUuid)
    }

    @Test fun mediaArchiveKeys_and_embedding_surviveRoundTrip() {
        val pkg = BackupPackage(
            characters = listOf(
                CharacterBackupData(
                    character = CharacterExport(
                        uuid = "c", name = "n", creationDate = 1L,
                        avatarArchiveKey = "media/avatars/c.jpg",
                        chatWallpaperArchiveKey = "media/wallpapers/c.jpg", // chunk1b：壁纸键随角色往返
                    ),
                    conversations = listOf(
                        ConversationExport(
                            uuid = "conv", creationDate = 1L,
                            messages = listOf(
                                MessageExport(
                                    messageUUID = "m1", timestamp = 1L,
                                    audioArchiveKey = "media/audio/m1.mp3", embedding = "QUJD",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val msg = roundTrip(pkg).characters[0].conversations!![0].messages!![0]
        assertEquals("media/audio/m1.mp3", msg.audioArchiveKey)
        assertEquals("QUJD", msg.embedding)
        assertEquals("media/avatars/c.jpg", roundTrip(pkg).characters[0].character.avatarArchiveKey)
        assertEquals("media/wallpapers/c.jpg", roundTrip(pkg).characters[0].character.chatWallpaperArchiveKey)
    }
}
