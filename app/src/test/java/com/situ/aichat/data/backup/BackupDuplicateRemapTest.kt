package com.situ.aichat.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 13.6b-2b「创建副本」私有子树 uuid 重映射纯函数单测（[remapCharacterSubtree]）。
 *
 * 锁三件最易出错的事：① 整条私有子树主键全部换新 + FK 全部重指向新父键（**安卓比 iOS 多做这步**：Room 强制主键
 * 唯一，照搬 iOS 复用原 uuid 会 REPLACE 掉原角色的消息/💰钱包 = 丢数据丢钱）；② quotedMessageUUID 按角色内全量
 * 旧→新表重映射（含跨会话引用，未知目标保留原值）；③ **💰角色钱包余额快照逐字拷贝、不钳位**，只换主键/外键
 * （复用 [CharacterWalletExport.toEntity]，零新增钱算路径）。
 *
 * 注入的 [nextUuid] 产生确定性序列 `n1,n2,…`：函数先给全部消息分配新 uuid（n1..n4），再给会话（n5,n6），
 * 再里程碑/宠物/💰钱包/日程+事件/通知模板——故消息/会话编号可精确断言。
 */
class BackupDuplicateRemapTest {

    private fun sampleCharacterData(
        wallet: CharacterWalletExport = CharacterWalletExport(
            uuid = "w1", coinBalance = 777, totalEarned = 1000, totalSpent = 223, monthlySalary = 500,
        ),
    ) = CharacterBackupData(
        character = CharacterExport(uuid = "CHAR", name = "小美", creationDate = 100L),
        conversations = listOf(
            ConversationExport(
                uuid = "c1", creationDate = 0L,
                messages = listOf(
                    MessageExport(messageUUID = "m1", timestamp = 1L),
                    MessageExport(messageUUID = "m2", timestamp = 2L, quotedMessageUUID = "m1"),
                ),
            ),
            ConversationExport(
                uuid = "c2", creationDate = 0L,
                messages = listOf(
                    MessageExport(messageUUID = "m3", timestamp = 3L, quotedMessageUUID = "m1"), // 跨会话引用
                    MessageExport(messageUUID = "m4", timestamp = 4L, quotedMessageUUID = "ghost"), // 未知目标
                ),
            ),
        ),
        milestones = listOf(MilestoneExport(establishedDate = 10L)),
        pet = CharacterPetExport(uuid = "pet1", name = "球球", hunger = 30),
        wallet = wallet,
        schedules = listOf(
            ScheduleExport(
                uuid = "s1", date = 0L,
                events = listOf(
                    ScheduleEventExport(uuid = "e1", startTime = 0L, endTime = 1L),
                    ScheduleEventExport(uuid = "e2", startTime = 1L, endTime = 2L),
                ),
            ),
        ),
        notificationTemplates = listOf(NotificationTemplateExport(id = "t1", content = "hi")),
    )

    private fun remap(cd: CharacterBackupData): RemappedSubtree {
        var i = 0
        return remapCharacterSubtree(cd, newCharUuid = "NEWCHAR", newPathByKey = emptyMap(), nameSuffix = "（副本）") {
            i++; "n$i"
        }
    }

    @Test fun duplicate_remapsAllPrivatePrimaryKeys_andRethreadsForeignKeys() {
        val r = remap(sampleCharacterData())

        assertEquals("NEWCHAR", r.character.uuid)

        // 会话：n5,n6（4 条消息 n1..n4 之后），全部挂到新角色。
        assertEquals(listOf("n5", "n6"), r.conversations.map { it.uuid })
        assertTrue(r.conversations.all { it.characterUuid == "NEWCHAR" })

        // 消息：messageUUID n1..n4；conversationUuid 重指向新会话。
        assertEquals(listOf("n1", "n2", "n3", "n4"), r.messages.map { it.messageUUID })
        assertEquals(listOf("n5", "n5", "n6", "n6"), r.messages.map { it.conversationUuid })

        // 里程碑 / 宠物 / 日程+事件 / 通知模板：主键全新 + FK 指向新角色 / 新日程。
        assertEquals(1, r.milestones.size)
        assertEquals("NEWCHAR", r.milestones[0].characterUuid)
        assertTrue(r.milestones[0].uuid !in setOf("CHAR"))

        assertEquals("NEWCHAR", r.pet!!.characterUuid)
        assertNotEquals("pet1", r.pet.uuid)

        assertEquals("NEWCHAR", r.schedules[0].characterUuid)
        assertNotEquals("s1", r.schedules[0].uuid)
        val newSchedUuid = r.schedules[0].uuid
        assertEquals(2, r.scheduleEvents.size)
        assertTrue(r.scheduleEvents.all { it.scheduleUuid == newSchedUuid })
        assertTrue(r.scheduleEvents.none { it.uuid == "e1" || it.uuid == "e2" })

        assertEquals("NEWCHAR", r.notificationTemplates[0].characterId)
        assertNotEquals("t1", r.notificationTemplates[0].id)
    }

    @Test fun duplicate_remapsQuotedMessageUuid_crossConversation_preservesUnknownTarget() {
        val r = remap(sampleCharacterData())
        // m2 引用 m1（同会话）→ m1 的新 uuid n1。
        assertEquals("n1", r.messages[1].quotedMessageUUID)
        // m3 引用 m1（跨会话）→ 仍重映射到 n1。
        assertEquals("n1", r.messages[2].quotedMessageUUID)
        // m4 引用未知 ghost → 原样保留。
        assertEquals("ghost", r.messages[3].quotedMessageUUID)
    }

    @Test fun duplicate_characterWallet_snapshotVerbatim_freshUuid_newCharLink() {
        val r = remap(sampleCharacterData())
        val w = r.wallet!!
        // 💰 余额三字段 + 月薪逐字拷贝（绝对快照，不重算/不重放）。
        assertEquals(777, w.coinBalance)
        assertEquals(1000, w.totalEarned)
        assertEquals(223, w.totalSpent)
        assertEquals(500, w.monthlySalary)
        // 仅换主键/外键：新角色钱包，不复用原 uuid（否则 REPLACE 掉原角色钱包 = 丢钱）。
        assertEquals("NEWCHAR", w.characterUuid)
        assertNotEquals("w1", w.uuid)
    }

    @Test fun duplicate_characterWallet_negativeBalance_notClamped() {
        // 角色钱包刻意不钳位（与用户钱包 max(0) 不同 = iOS）：负余额原样穿过。
        val r = remap(sampleCharacterData(wallet = CharacterWalletExport(uuid = "w1", coinBalance = -50)))
        assertEquals(-50, r.wallet!!.coinBalance)
    }

    @Test fun duplicate_appendsNameSuffix() {
        assertEquals("小美（副本）", remap(sampleCharacterData()).character.name)
    }

    @Test fun duplicate_remapsChatWallpaperPath_viaNewPathByKey() {
        // chunk1b：副本角色的壁纸路径按「zip 键 → 新落盘路径」表重映射（与头像同源 newPathByKey）。
        val cd = CharacterBackupData(
            character = CharacterExport(
                uuid = "CHAR", name = "小美", creationDate = 100L,
                chatWallpaperArchiveKey = "media/wallpapers/CHAR.jpg",
            ),
        )
        val r = remapCharacterSubtree(
            cd,
            newCharUuid = "NEWCHAR",
            newPathByKey = mapOf("media/wallpapers/CHAR.jpg" to "/files/wallpapers/new.jpg"),
            nameSuffix = "（副本）",
        ) { "n" }
        assertEquals("/files/wallpapers/new.jpg", r.character.chatWallpaperPath)
    }

    @Test fun duplicate_freshUuids_neverCollideWithOriginals_allUnique() {
        val r = remap(sampleCharacterData())
        val originals = setOf("CHAR", "c1", "c2", "m1", "m2", "m3", "m4", "pet1", "w1", "s1", "e1", "e2", "t1")
        val news = buildList {
            add(r.character.uuid)
            addAll(r.conversations.map { it.uuid })
            addAll(r.messages.map { it.messageUUID })
            addAll(r.milestones.map { it.uuid })
            r.pet?.let { add(it.uuid) }
            r.wallet?.let { add(it.uuid) }
            addAll(r.schedules.map { it.uuid })
            addAll(r.scheduleEvents.map { it.uuid })
            addAll(r.notificationTemplates.map { it.id })
        }
        assertTrue("no remapped key may equal an original", news.none { it in originals })
        assertEquals("all remapped keys unique", news.size, news.toSet().size)
    }
}
