package com.situ.aichat.data.backup

import com.situ.aichat.data.local.entity.ConversationEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 场内前情提要三列（记忆改造二期·部件⑤）的备份往返保真（T2-8·图纸 §7·E10）。
 *
 * 三列（inSceneRecapText / inSceneRecapSessionKey / inSceneRecapUntilMillis）随 [ConversationEntity] ↔
 * [ConversationExport] 映射穿越——任一列被漏拷/错位/类型走样，往返断言立刻变红；旧备份包（无此三字段）
 * 经 @Serializable 默认值兜底为「等价无提要」（'' / '' / 0），不崩、不误填。
 */
class ConversationRecapBackupTest {

    // 与 BackupService 同款 Json 配置（导入侧宽容旧包缺字段）。
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = true
    }

    @Test fun recapFields_surviveEntityRoundTrip() {
        val e = ConversationEntity(
            uuid = "conv1",
            title = "小满",
            characterUuid = "cOLD",
            creationDate = 1_700_000_000_000L,
            inSceneRecapText = "两人聊起周末的展览，她说想去；情绪从拘谨转为放松。",
            inSceneRecapSessionKey = "call:1700000001234",
            inSceneRecapUntilMillis = 1_700_000_050_000L,
        )
        val back = e.toExport(messages = emptyList()).toEntity(characterUuid = "cNEW")
        assertEquals("两人聊起周末的展览，她说想去；情绪从拘谨转为放松。", back.inSceneRecapText)
        assertEquals("call:1700000001234", back.inSceneRecapSessionKey)
        assertEquals(1_700_000_050_000L, back.inSceneRecapUntilMillis)
        // 身份：characterUuid 取导入侧参数（重新挂载），其余快照原样。
        assertEquals("conv1", back.uuid)
        assertEquals("cNEW", back.characterUuid)
    }

    @Test fun oldBackupWithoutRecapFields_defaultsToEmpty() {
        // 旧备份包 JSON 无这三字段 → 反序列化取 @Serializable 默认值 → toEntity 等价「无前情提要」。
        val oldJson = """{"uuid":"conv2","title":"旧包会话","creationDate":100}"""
        val export = json.decodeFromString(ConversationExport.serializer(), oldJson)
        val entity = export.toEntity(characterUuid = "cX")
        assertEquals("", entity.inSceneRecapText)
        assertEquals("", entity.inSceneRecapSessionKey)
        assertEquals(0L, entity.inSceneRecapUntilMillis)
    }
}
