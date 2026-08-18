package com.situ.aichat.data.backup

import com.situ.aichat.data.local.entity.CharacterWalletEntity
import com.situ.aichat.data.local.entity.CurrencyTransactionEntity
import com.situ.aichat.data.local.entity.DiaryCommentEntity
import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.local.entity.DiaryReactionEntity
import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.data.local.entity.MonthlyReviewEntity
import com.situ.aichat.data.local.entity.RedPacketRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 💰 钱路翻译函数（Entity → Export → Entity）往返保真测试。
 *
 * 这些映射函数在文件瘦身刀1（[com.situ.aichat.data.backup] 的 toExport 抽进 BackupExportMappers.kt）+ 刀2
 * （toEntity 抽进 BackupImportMappers.kt）中跨文件搬家。本测试是搬家后的**长期回归网**：任何一个钱字段被漏拷 /
 * 错位 / 类型走样，往返断言立刻变红。断言值从「绝对快照原样往返」语义独立反推（备份恢复 = 余额快照覆盖，绝不重算）。
 *
 * 覆盖钱实体：角色钱包（余额/月薪/好感度）、礼物（成交价/好感）、红包（托管金额/状态）、流水台账
 * （金额/余额快照/幂等 key）。用户钱包 Export→Entity 由 restoreUserWallet 内联构建（非映射函数），见
 * [BackupRoundTripTest] 的 DTO 往返与真机备份恢复覆盖。
 */
class BackupMapperRoundTripTest {

    @Test fun characterWallet_allMoneyFields_surviveEntityRoundTrip() {
        val e = CharacterWalletEntity(
            uuid = "w1",
            characterUuid = "cOLD",
            createdAt = 1_700_000_000_000L,
            coinBalance = 0,            // 0 ≠ 实体默认也是 0；配合非默认字段锁全量拷贝
            totalEarned = 12345,
            totalSpent = 6789,
            monthlySalary = 8888,
            salaryInferred = true,
            salaryDay = 3,
            lastSalaryDate = 1_700_100_000_000L,
            lastEconomicScanDate = 1_700_200_000_000L,
            lastProactiveGiftDate = 1_700_300_000_000L,
            affinityFromUser = 42,
            affinityToUser = 7,
        )
        val back = e.toExport().toEntity(characterUuid = "cNEW")
        // 余额三件套 + 月薪：绝对快照逐字穿越。
        assertEquals(0, back.coinBalance)
        assertEquals(12345, back.totalEarned)
        assertEquals(6789, back.totalSpent)
        assertEquals(8888, back.monthlySalary)
        // 发薪元数据（恢复后据此判「本月是否已发」，丢失会重发工资）。
        assertEquals(true, back.salaryInferred)
        assertEquals(3, back.salaryDay)
        assertEquals(1_700_100_000_000L, back.lastSalaryDate)
        assertEquals(1_700_200_000_000L, back.lastEconomicScanDate)
        assertEquals(1_700_300_000_000L, back.lastProactiveGiftDate)
        // 好感度双向。
        assertEquals(42, back.affinityFromUser)
        assertEquals(7, back.affinityToUser)
        // 身份：uuid/createdAt 随快照走；characterUuid 取导入侧参数（重新挂载到目标角色）。
        assertEquals("w1", back.uuid)
        assertEquals(1_700_000_000_000L, back.createdAt)
        assertEquals("cNEW", back.characterUuid)
    }

    @Test fun characterWallet_negativeBalance_notClamped() {
        // 角色钱包刻意不钳位（与用户钱包 max(0) 不同 = iOS）：负余额原样穿过映射。
        val back = CharacterWalletEntity(uuid = "w", coinBalance = -50).toExport().toEntity(characterUuid = "c")
        assertEquals(-50, back.coinBalance)
    }

    @Test fun giftRecord_priceAndAffinity_surviveEntityRoundTrip() {
        val e = GiftRecordEntity(
            uuid = "g1",
            pricePaid = 600,
            isDIY = true,
            diyTitle = "手写贺卡",
            affinityGain = 5,
            relationshipImpactJSON = """{"closeness":5}""",
        )
        val back = e.toExport(diyImageArchiveKey = null).toEntity(newPathByKey = emptyMap())
        assertEquals(600, back.pricePaid)          // 成交价快照
        assertEquals(5, back.affinityGain)
        assertEquals(true, back.isDIY)
        assertEquals("手写贺卡", back.diyTitle)
        assertEquals("""{"closeness":5}""", back.relationshipImpactJSON)
        assertEquals("g1", back.uuid)
    }

    @Test fun redPacket_amountAndStatus_surviveEntityRoundTrip() {
        val e = RedPacketRecordEntity(
            uuid = "r1",
            amount = 888,
            status = "pending",
            blessingText = "新年快乐",
            senderType = "user",
            receiverType = "character",
        )
        val back = e.toExport().toEntity()
        assertEquals(888, back.amount)             // 托管金额快照
        assertEquals("pending", back.status)
        assertEquals("新年快乐", back.blessingText)
        assertEquals("user", back.senderType)
        assertEquals("character", back.receiverType)
        assertEquals("r1", back.uuid)
    }

    @Test fun meetingAppointment_allFields_surviveEntityRoundTrip() {
        // 约定无钱路，但同样「绝对快照原样往返」：任一字段漏拷/错位/类型走样，断言立刻变红。
        val e = MeetingAppointmentEntity(
            uuid = "m1",
            characterUuid = "cOLD",
            conversationUuid = "conv1",
            status = "confirmed",
            proposedBy = "user",
            source = "tool",
            scheduledAt = 1_800_000_000_000L,
            timeGranularity = "dayOnly",
            rawWhenText = "周末下午",
            location = "猫咖",
            activity = "撸猫喝下午茶",
            invitationText = "周六一起去猫咖好不好～",
            tensionHint = "她最近有点累",
            hiddenTensionSeed = "其实她想多陪陪你",
            createdAt = 1_700_000_000_000L,
            confirmedAt = 1_700_000_500_000L,
            outcomeAt = null,
            honoredSessionId = null,
            lastReminderScheduledAt = 1_799_999_000_000L,
        )
        val back = e.toExport().toEntity()
        // 关联 + 状态机
        assertEquals("m1", back.uuid)
        assertEquals("cOLD", back.characterUuid)
        assertEquals("conv1", back.conversationUuid)
        assertEquals("confirmed", back.status)
        assertEquals("user", back.proposedBy)
        assertEquals("tool", back.source)
        // 时间 + 内容
        assertEquals(1_800_000_000_000L, back.scheduledAt)
        assertEquals("dayOnly", back.timeGranularity)
        assertEquals("周末下午", back.rawWhenText)
        assertEquals("猫咖", back.location)
        assertEquals("撸猫喝下午茶", back.activity)
        assertEquals("周六一起去猫咖好不好～", back.invitationText)
        assertEquals("她最近有点累", back.tensionHint)
        assertEquals("其实她想多陪陪你", back.hiddenTensionSeed)
        // 生命周期时间戳（含 nullable 取 null 分支）
        assertEquals(1_700_000_000_000L, back.createdAt)
        assertEquals(1_700_000_500_000L, back.confirmedAt)
        assertNull(back.outcomeAt)
        assertNull(back.honoredSessionId)
        assertEquals(1_799_999_000_000L, back.lastReminderScheduledAt)
    }

    @Test fun meetingAppointment_terminalNullableFields_populated_survive() {
        // 赴约 honored 分支：outcomeAt / honoredSessionId 有值时也原样穿越（覆盖 nullable 取非 null 分支）。
        val back = MeetingAppointmentEntity(
            uuid = "m2",
            status = "honored",
            outcomeAt = 1_800_100_000_000L,
            honoredSessionId = "sess-xyz",
        ).toExport().toEntity()
        assertEquals("honored", back.status)
        assertEquals(1_800_100_000_000L, back.outcomeAt)
        assertEquals("sess-xyz", back.honoredSessionId)
    }

    @Test fun currencyTransaction_ledgerFields_surviveEntityRoundTrip() {
        // R2 幂等台账：relatedEntityId（当月发薪幂等 key）+ balanceAfter（余额快照）+ 方向/品类全量保真。
        val e = CurrencyTransactionEntity(
            uuid = "t1",
            timestamp = 1_717_000_000_000L,
            ownerTypeRaw = "character",
            characterUuid = "c1",
            kindRaw = "earn",
            categoryRaw = "salary",
            amount = 3000,
            balanceAfter = 3000,
            relatedEntityId = "salary_c1_202606",
            note = "六月工资",
        )
        val back = e.toExport().toEntity()
        assertEquals(3000, back.amount)
        assertEquals(3000, back.balanceAfter)
        assertEquals("salary_c1_202606", back.relatedEntityId)
        assertEquals("character", back.ownerTypeRaw)
        assertEquals("c1", back.characterUuid)
        assertEquals("earn", back.kindRaw)
        assertEquals("salary", back.categoryRaw)
        assertEquals("六月工资", back.note)
        assertEquals("t1", back.uuid)
    }

    // ── 日记（R6-3②·非钱路，但备份缺字段=数据损坏：作者归属丢失→TA 的信永久变用户日记）──

    @Test fun diaryEntry_authorAndSnapshot_surviveEntityRoundTrip() {
        // 交换日记：authorCharacterUuid 缺失曾致「恢复后 TA 的信永久变用户日记」——本测试守住它随备份往返。
        val e = DiaryEntryEntity(
            uuid = "d1", content = "TA 的信", timestamp = 100L, imagePathsJson = "",
            moodEmoji = "🌤", visibilityRaw = "openToAI", triggerTypeRaw = "exchange",
            authorCharacterUuid = "cA", authorNameSnapshot = "小满",
        )
        val back = e.toExport(imageArchiveKeys = null, comments = null, reactions = null).toEntity(imagePathsJson = "")
        assertEquals("cA", back.authorCharacterUuid)      // 作者归属绝对不能丢
        assertEquals("小满", back.authorNameSnapshot)      // 作者名快照（角色被删仍可署名）
        assertEquals("exchange", back.triggerTypeRaw)
        assertEquals("openToAI", back.visibilityRaw)
        assertEquals("🌤", back.moodEmoji)
        assertEquals("TA 的信", back.content)
        assertEquals("d1", back.uuid)
    }

    @Test fun diaryEntry_userDiary_nullAuthorSurvives() {
        // 用户日记：作者字段 null 分支原样穿越（不被误填）。
        val back = DiaryEntryEntity(uuid = "d2", content = "我的", imagePathsJson = "")
            .toExport(null, null, null).toEntity("")
        assertNull(back.authorCharacterUuid)
        assertNull(back.authorNameSnapshot)
    }

    @Test fun diaryComment_threadFields_surviveEntityRoundTrip() {
        // 回复线程：parentCommentId + isFromUser 缺失曾致「恢复后线程塌平/用户回复显示成角色评论」。
        val userReply = DiaryCommentEntity(
            id = "cm1", entryUuid = "d1", content = "读了你的信", timestamp = 200L,
            characterUuid = null, parentCommentId = "root1", isFromUser = true,
        )
        val back = userReply.toExport().toEntity(entryUuid = "d1")
        assertEquals("root1", back.parentCommentId)       // 线程归属
        assertTrue("用户回复标记须存活", back.isFromUser)
        assertNull(back.characterUuid)
        assertEquals("d1", back.entryUuid)
        assertEquals("cm1", back.id)

        // 角色顶层评论：默认分支（parent=null / isFromUser=false）也原样。
        val charRoot = DiaryCommentEntity(id = "cm2", entryUuid = "d1", content = "评论", characterUuid = "cA")
        val backRoot = charRoot.toExport().toEntity("d1")
        assertNull(backRoot.parentCommentId)
        assertFalse(backRoot.isFromUser)
        assertEquals("cA", backRoot.characterUuid)
    }

    @Test fun diaryReaction_surviveEntityRoundTrip() {
        val e = DiaryReactionEntity(id = "rx1", entryUuid = "d1", characterUuid = "cA", emoji = "❤️", timestamp = 300L)
        val back = e.toExport().toEntity(entryUuid = "d1")
        assertEquals("rx1", back.id)
        assertEquals("d1", back.entryUuid)
        assertEquals("cA", back.characterUuid)
        assertEquals("❤️", back.emoji)
        assertEquals(300L, back.timestamp)
    }

    @Test fun monthlyReview_surviveEntityRoundTrip() {
        val e = MonthlyReviewEntity(
            uuid = "mr1", monthStartMillis = 1_717_200_000_000L, content = "这个月…",
            moodCountsJson = """{"🌤":3}""", generatedAt = 1_717_300_000_000L,
        )
        val back = e.toExport().toEntity()
        assertEquals("mr1", back.uuid)
        assertEquals(1_717_200_000_000L, back.monthStartMillis)
        assertEquals("这个月…", back.content)
        assertEquals("""{"🌤":3}""", back.moodCountsJson)
        assertEquals(1_717_300_000_000L, back.generatedAt)
    }

    @Test fun currencyTransaction_zeroAmountRentMarker_survives() {
        // 0 元欠租留痕（amount=0 走默认值路径）：方向/note 须原样不被吞。
        val back = CurrencyTransactionEntity(
            uuid = "t2", kindRaw = "spend", categoryRaw = "rent",
            amount = 0, balanceAfter = 3000, relatedEntityId = "rent_c1_202606", note = "欠租 500（余额不足）",
        ).toExport().toEntity()
        assertEquals(0, back.amount)
        assertEquals("spend", back.kindRaw)
        assertEquals("rent_c1_202606", back.relatedEntityId)
        assertEquals("欠租 500（余额不足）", back.note)
    }
}
