package com.situ.aichat.prompt.memory

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.DiaryCommentEntity
import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.local.entity.MomentCommentEntity
import com.situ.aichat.data.local.entity.MomentLikeEntity
import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.MomentRepository
import com.situ.aichat.util.StringListJson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 消化素材收集看门（记忆改造一期·图纸 §3.5 / T2-4）。用**真 in-memory Room**（Robolectric·T2 主力）让三路的实际 SQL
 * 谓词（软删过滤 / exchange+isDraft / LIMIT 2 / (water, rangeEnd] 窗）一并被验（较纯 MockK 更强·图纸「(MockK)」为档位建议）。
 * 断言从图纸 §3.5/§5 独立反推：三路选择/上限/水位/行格式逐字节 + markDigested 写值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryDigestMaterialServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var service: MemoryDigestMaterialService
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now = at(2026, 7, 10, 12, 0)
    private val cid = "c1"

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        service = MemoryDigestMaterialService(
            MomentRepository(db.momentDao()), db.offlineMeetingMemoryDao(), db.diaryDao(), db.characterDao(),
        )
        db.characterDao().upsert(CharacterEntity(uuid = cid, name = "团子", creationDate = 0L))
    }

    @After fun tearDown() { db.close() }

    private suspend fun setWatermark(v: Long) =
        db.characterDao().updateMomentsDigestWatermark(cid, v)

    private fun settings(inject: Int = 3) = AppSettings().copy(meetingMemoryInjectCount = inject)

    private fun meeting(
        uuid: String, started: Long, kind: String = "meeting", digested: Long? = null,
        loc: String = "公园", activity: String = "散步", summary: String = "聊了很久。",
        highlights: List<String> = emptyList(), mood: String = "",
    ) = OfflineMeetingMemoryEntity(
        uuid = uuid, characterUuid = cid, kindRaw = kind, startedAtMillis = started, endedAtMillis = started,
        location = loc, activity = activity, moodRaw = mood, summary = summary,
        highlightsJson = StringListJson.encode(highlights).ifEmpty { "[]" }, promisesJson = "[]",
        createdAtMillis = started, updatedAtMillis = started, digestedAtMillis = digested,
    )

    // ── A. 见面选择 ──

    @Test fun meetings_selection_excludesLatestInjectCount_onlyMeeting_undigested_cap2_oldestFirst() = runBlocking {
        db.offlineMeetingMemoryDao().upsertAll(
            listOf(
                meeting("m1", at(2026, 6, 1, 10, 0)),                 // 最旧·候选
                meeting("m2", at(2026, 6, 2, 10, 0)),                 // 候选
                meeting("m3", at(2026, 6, 3, 10, 0)),                 // 候选（但上限 2 → 不取）
                meeting("mDig", at(2026, 6, 4, 10, 0), digested = 999), // 已消化 → 排除
                meeting("mLeg", at(2026, 5, 1, 10, 0), kind = "legacy"), // 非 meeting → 排除
                // 最新 3 条 meeting（injectCount=3）受保护：m4/m5/m6。
                meeting("m4", at(2026, 6, 5, 10, 0)),
                meeting("m5", at(2026, 6, 6, 10, 0)),
                meeting("m6", at(2026, 6, 7, 10, 0)),
            ),
        )
        val b = service.collect(cid, "小明", settings(inject = 3), now, zone)
        assertEquals("最旧 2 条未消化非保护", listOf("m1", "m2"), b.meetingUuids)
    }

    @Test fun meetings_lineFormat_byteExact() = runBlocking {
        db.offlineMeetingMemoryDao().upsert(
            meeting(
                "m1", at(2026, 6, 1, 10, 0), loc = "海边", activity = "看日落", summary = "很放松。",
                highlights = listOf("你的笑", "那阵风"), mood = "warm",
            ),
        )
        val b = service.collect(cid, "小明", settings(inject = 0), now, zone) // inject=0 → 单条见面不受保护
        assertTrue(
            b.text.contains(
                "[2026-06-01] （见面）你们当天在海边见面（看日落），留下的记录：很放松。 难忘：你的笑；那阵风。 当时的气氛：温暖。",
            ),
        )
    }

    @Test fun meetings_emptyLocationAndNoHighlightsNoMood() = runBlocking {
        db.offlineMeetingMemoryDao().upsert(
            meeting("m1", at(2026, 6, 1, 10, 0), loc = "", summary = "随便走走。", mood = "unknown"),
        )
        val b = service.collect(cid, "小明", settings(inject = 0), now, zone)
        assertTrue(b.text.contains("[2026-06-01] （见面）你们当天在某地见面（散步），留下的记录：随便走走。"))
        assertTrue("无难忘/气氛段", !b.text.contains("难忘：") && !b.text.contains("当时的气氛："))
    }

    // ── B. 朋友圈水位 / 过滤 / 上限 / 格式 ──

    private suspend fun post(
        uuid: String, ts: Long, author: String, content: String = "内容", soft: Boolean = false,
        images: Boolean = false,
    ) = db.momentDao().insertPost(
        MomentPostEntity(
            uuid = uuid, content = content, timestamp = ts, authorTypeRaw = author,
            characterUuid = if (author == "character") cid else null, isSoftDeleted = soft,
            imagePathsJson = if (images) """["/x.jpg"]""" else "",
        ),
    )

    private suspend fun like(postUuid: String, author: String, ts: Long) = db.momentDao().insertLike(
        MomentLikeEntity(timestamp = ts, authorTypeRaw = author, characterUuid = if (author == "character") cid else null, postUuid = postUuid),
    )

    private suspend fun comment(uuid: String, postUuid: String, author: String, content: String, ts: Long) =
        db.momentDao().insertComment(
            MomentCommentEntity(uuid = uuid, content = content, timestamp = ts, authorTypeRaw = author, characterUuid = if (author == "character") cid else null, postUuid = postUuid),
        )

    @Test fun moments_watermarkZero_startsAtNow7d_windowNow6d_e11() = runBlocking {
        // 水位 0 → 起步 now−7d（7/3 12:00），rangeEnd = now−6d（7/4 12:00）。
        post("pIn", at(2026, 7, 4, 8, 0), "character")       // 窗内 → 收
        post("pOld", at(2026, 7, 2, 8, 0), "character")      // now−7d 之前 → 不收（绝不深挖）
        post("pNew", at(2026, 7, 6, 8, 0), "character")      // rangeEnd 之后 → 不收
        val b = service.collect(cid, "小明", settings(), now, zone)
        assertTrue(b.text.contains("你发了动态") && b.text.contains("[2026-07-04]"))
        assertTrue("窗前动态不收", !b.text.contains("[2026-07-02]"))
        assertTrue("窗后动态不收", !b.text.contains("[2026-07-06]"))
        assertEquals("未满 8 → 水位=rangeEnd(now−6d)", at(2026, 7, 4, 12, 0), b.momentsWatermarkAdvanceTo)
    }

    @Test fun moments_softDeletedExcluded_and_noInteractionUserPostSkipped() = runBlocking {
        setWatermark(at(2026, 7, 4, 0, 0))
        post("pSoft", at(2026, 7, 4, 6, 0), "character", soft = true)  // 软删 → SQL 排除
        post("pUserNo", at(2026, 7, 4, 7, 0), "user")                  // 用户帖无互动 → 跳过
        post("pUserYes", at(2026, 7, 4, 8, 0), "user")                 // 用户帖有角色互动 → 收
        like("pUserYes", "character", at(2026, 7, 4, 8, 30))
        post("pChar", at(2026, 7, 4, 9, 0), "character")               // 角色帖 → 收
        val b = service.collect(cid, "小明", settings(), now, zone)
        assertEquals("软删排除 + 无互动用户帖跳过 → 只收 2 行", 2, b.lineCount)
        assertTrue("有互动用户帖收", b.text.contains("小明发了动态：“内容” ← 你点赞了"))
        assertTrue("角色帖收", b.text.contains("你发了动态：“内容”"))
    }

    @Test fun moments_cap8_earlyStop_watermarkIsLastCheckedTimestamp() = runBlocking {
        setWatermark(at(2026, 7, 4, 0, 0))
        for (i in 0 until 10) post("p$i", at(2026, 7, 4, 1, i), "character") // 10 条角色帖·1:00..1:09
        val b = service.collect(cid, "小明", settings(), now, zone)
        assertEquals("上限 8", 8, b.lineCount)
        assertEquals("提前停 → 水位=第 8 条(index7)时间戳", at(2026, 7, 4, 1, 7), b.momentsWatermarkAdvanceTo)
    }

    @Test fun moments_lineFormat_charAndUserPost_byteExact() = runBlocking {
        setWatermark(at(2026, 7, 4, 0, 0))
        post("pChar", at(2026, 7, 4, 9, 0), "character", content = "今天天气真好")
        like("pChar", "user", at(2026, 7, 4, 9, 5))
        comment("cmC", "pChar", "user", "羡慕", at(2026, 7, 4, 9, 10))
        post("pUser", at(2026, 7, 4, 10, 0), "user", content = "求安慰", images = true)
        like("pUser", "character", at(2026, 7, 4, 10, 5))
        comment("cmU", "pUser", "character", "抱抱", at(2026, 7, 4, 10, 10))
        val b = service.collect(cid, "小明", settings(), now, zone)
        assertTrue(b.text.contains("[2026-07-04] （朋友圈）你发了动态：“今天天气真好” ← 小明点赞了；小明评论说：“羡慕”"))
        assertTrue(b.text.contains("[2026-07-04] （朋友圈）小明发了动态（附带图片）：“求安慰” ← 你点赞了；你评论说：“抱抱”"))
    }

    @Test fun moments_emptyContentPlaceholder() = runBlocking {
        setWatermark(at(2026, 7, 4, 0, 0))
        post("pChar", at(2026, 7, 4, 9, 0), "character", content = "")
        val b = service.collect(cid, "小明", settings(), now, zone)
        assertTrue(b.text.contains("你发了动态：“（无文字，只有图）”"))
    }

    // ── C. 交换日记 ──

    private suspend fun diary(
        uuid: String, ts: Long, content: String, author: String? = cid, trigger: String = "exchange",
        draft: Boolean = false, digested: Long? = null, moodText: String? = null, moodEmoji: String? = null,
    ) = db.diaryDao().upsertEntry(
        DiaryEntryEntity(
            uuid = uuid, content = content, timestamp = ts, triggerTypeRaw = trigger, isDraft = draft,
            authorCharacterUuid = author, digestedAtMillis = digested, moodText = moodText, moodEmoji = moodEmoji,
        ),
    )

    @Test fun diary_predicate_exchange24hDraft_cap2_oldestFirst_e18() = runBlocking {
        diary("dOld1", at(2026, 7, 1, 10, 0), "信一")                       // 候选（最旧）
        diary("dOld2", at(2026, 7, 2, 10, 0), "信二")                       // 候选
        diary("dOld3", at(2026, 7, 3, 10, 0), "信三")                       // 候选（上限 2 → 不取）
        diary("dDraft", at(2026, 7, 1, 11, 0), "草稿信", draft = true)       // 草稿 → 排除
        diary("dUser", at(2026, 7, 1, 12, 0), "用户日记", author = null, trigger = "auto_draft") // 非交换 → 排除
        diary("dRecent", now - 60_000, "刚写的信")                          // <24h → 暂不消化（E18）
        val b = service.collect(cid, "小明", settings(), now, zone)
        assertEquals("exchange/非草稿/≤24h 前·最旧 2", listOf("dOld1", "dOld2"), b.diaryUuids)
    }

    @Test fun diary_lineFormat_withMoodAndUserReplies_byteExact() = runBlocking {
        diary("d1", at(2026, 7, 1, 10, 0), "今天想你了，写下这些。", moodText = "温柔")
        db.diaryDao().insertComment(
            DiaryCommentEntity(id = "r1", entryUuid = "d1", content = "我也想你", timestamp = at(2026, 7, 1, 11, 0), isFromUser = true),
        )
        db.diaryDao().insertComment(
            DiaryCommentEntity(id = "r2", entryUuid = "d1", content = "明天见", timestamp = at(2026, 7, 1, 12, 0), isFromUser = true),
        )
        db.diaryDao().insertComment(
            DiaryCommentEntity(id = "rc", entryUuid = "d1", content = "角色回复不算", timestamp = at(2026, 7, 1, 13, 0), characterUuid = cid, isFromUser = false),
        )
        val b = service.collect(cid, "小明", settings(), now, zone)
        assertTrue(
            b.text.contains(
                "[2026-07-01] （交换日记）你以笔友身份给小明写了一封信（心情：温柔），信里写道：“今天想你了，写下这些。”；小明回复说：“我也想你”；小明回复说：“明天见”",
            ),
        )
    }

    @Test fun diary_moodEmojiFallback_whenNoMoodText() = runBlocking {
        diary("d1", at(2026, 7, 1, 10, 0), "内容", moodText = null, moodEmoji = "🌙")
        val b = service.collect(cid, "小明", settings(), now, zone)
        assertTrue(b.text.contains("写了一封信（心情：🌙），"))
    }

    // ── 头行 / 空 / markDigested ──

    @Test fun header_present_whenAnyMaterial_and_allEmptyReturnsBlank() = runBlocking {
        val empty = service.collect(cid, "小明", settings(), now, zone)
        assertEquals("三路皆空 → text 为空", "", empty.text)

        db.offlineMeetingMemoryDao().upsert(meeting("m1", at(2026, 6, 1, 10, 0)))
        val b = service.collect(cid, "小明", settings(inject = 0), now, zone)
        assertTrue(
            b.text.startsWith(
                "[以下为同期的非聊天素材（朋友圈动态 / 交换日记 / 见面档案），请把其中值得记住的信息一并合并进记忆，与聊天内容同等对待]\n",
            ),
        )
    }

    @Test fun markDigested_writesMeetingDiaryFlags_andMomentsWatermark() = runBlocking {
        db.offlineMeetingMemoryDao().upsert(meeting("m1", at(2026, 6, 1, 10, 0)))
        diary("d1", at(2026, 7, 1, 10, 0), "信")
        setWatermark(at(2026, 7, 4, 0, 0))
        post("pChar", at(2026, 7, 4, 9, 0), "character")
        val b = service.collect(cid, "小明", settings(inject = 0), now, zone)

        service.markDigested(cid, b, now)

        assertEquals(now, db.offlineMeetingMemoryDao().findByUuid("m1")!!.digestedAtMillis)
        assertEquals(now, db.diaryDao().getEntry("d1")!!.digestedAtMillis)
        assertEquals(at(2026, 7, 4, 12, 0), db.characterDao().getByUuid(cid)!!.momentsDigestedUntilMillis)
    }
}
