package com.situ.aichat.world.link

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.WorldMemoryDao
import com.situ.aichat.data.local.dao.WorldSocialDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldMemoryEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEventEntity
import com.situ.aichat.util.StringListJson
import com.situ.aichat.world.WorldSeeds
import com.situ.aichat.world.social.WorldRelationshipBeats as Beats
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 双视角世界记忆**抄写员**（W5 图纸 §3.3 / §4.2 / 契约 §9【核心】·决策 24 双视角+分级）：把世界里的「大事」
 * （初识/拌嘴/和好/里程碑）按当事各方视角、用各自口吻写成一条持久记忆入 `world_memory`（模板零 LLM）。
 *
 * **记忆分级**（契约「小事不进记忆库」）：只抄 [MEMORY_KINDS]（大事）——小事（结伴/互助/惦记/渐远）不进记忆库，
 * 活在关系事件表里由 W4 结痂压缩自然淡化。**幂等**：uuid 种子派生 `world:mem:{事件uuid}:{视角uuid}`（禁改），
 * 落前先查（[WorldMemoryDao.getByUuid]）防同窗重跑虚增计数。角色查无（删角/未入世）→ 跳过该视角。
 *
 * 抄写员**只在回前台通行证里跑**（[WorldLinkRunner]），绝不在聊天回合路径上。
 */
@Singleton
class WorldMemoryScribe @Inject constructor(
    private val socialDao: WorldSocialDao,
    private val characterDao: CharacterDao,
    private val memoryDao: WorldMemoryDao,
) {

    /** 主动方（actorId 视角）/ 对象方（targetId 视角·语气更收着·呼应 W4 不对称折减）。 */
    private enum class Perspective { INITIATOR, RECIPIENT }

    /**
     * 抄写 [fromMs] 起（happenedAt ≥ fromMs）的大事关系事件为双视角记忆，返回**本次新写条数**（幂等门跳过者不计）。
     */
    suspend fun scribeSince(fromMs: Long): Int {
        var written = 0
        for (event in socialDao.eventsSince(fromMs)) {
            if (event.kindRaw !in MEMORY_KINDS) continue
            // 两视角都需「主体存在 + 对方名字」——任一角色查无则整事件跳过（写不出含对方名的记忆）。
            val actor = characterDao.getByUuid(event.actorId) ?: continue
            val target = characterDao.getByUuid(event.targetId) ?: continue
            written += scribeOne(event, subject = actor, other = target, perspective = Perspective.INITIATOR)
            written += scribeOne(event, subject = target, other = actor, perspective = Perspective.RECIPIENT)
        }
        return written
    }

    /** 写单条视角记忆（幂等门命中或已存在 → 返回 0）。 */
    private suspend fun scribeOne(
        event: WorldRelationshipEventEntity,
        subject: CharacterEntity,
        other: CharacterEntity,
        perspective: Perspective,
    ): Int {
        val uuid = UUID.nameUUIDFromBytes("world:mem:${event.uuid}:${subject.uuid}".toByteArray()).toString()
        if (memoryDao.getByUuid(uuid) != null) return 0 // 幂等门（防同窗重跑/回看重叠虚增）
        // 有符号取模转 0/1（锁死·图纸 §4.2/§9）：((fnv % 2) + 2) % 2。
        val variant = (((WorldSeeds.fnv1a64(event.uuid + ":" + subject.uuid) % 2) + 2) % 2).toInt()
        val content = TEMPLATES.getValue(event.kindRaw).getValue(perspective)[variant].replace("{other}", other.name)
        memoryDao.upsert(
            WorldMemoryEntity(
                uuid = uuid,
                characterUuid = subject.uuid,
                otherIdsJson = StringListJson.encode(listOf(other.uuid)),
                kindRaw = event.kindRaw,
                content = content,
                happenedAt = event.happenedAt,
                sourceUuid = event.uuid,
                createdAt = event.settledAt, // = 源事件 settledAt（禁真时钟·图纸 §3.1）
            ),
        )
        return 1
    }

    companion object {
        /** 大事四种（= 契约「大事」·§9 禁改·与 [TEMPLATES] 键一致）。 */
        val MEMORY_KINDS = setOf(Beats.FIRST_MEET, Beats.QUARREL_START, Beats.QUARREL_MEND, Beats.MILESTONE)

        /**
         * 16 句双视角记忆模板（图纸 §4.2·逐字锁死·{other}=对方名·variant 0/1）。主动方=事件 actorId 视角、
         * 对象方=targetId 视角（对象方语气更收着，呼应 W4 不对称折减）。
         */
        private val TEMPLATES: Map<String, Map<Perspective, List<String>>> = mapOf(
            Beats.FIRST_MEET to mapOf(
                Perspective.INITIATOR to listOf(
                    "你在城里认识了{other}，一见如故，聊得很投机",
                    "你和{other}就这么认识了，感觉遇上了合拍的人",
                ),
                Perspective.RECIPIENT to listOf(
                    "{other}主动和你搭了话，你们就此认识，印象还不错",
                    "你认识了{other}，虽然刚见面，却觉得有点投缘",
                ),
            ),
            Beats.QUARREL_START to mapOf(
                Perspective.INITIATOR to listOf(
                    "你和{other}拌了嘴，谁也没先低头，心里堵得慌",
                    "你跟{other}闹得不太愉快，想起来还是有点气",
                ),
                Perspective.RECIPIENT to listOf(
                    "{other}那天的话让你不太舒服，你有些在意",
                    "你和{other}起了点争执，心里有点别扭",
                ),
            ),
            Beats.QUARREL_MEND to mapOf(
                Perspective.INITIATOR to listOf(
                    "你和{other}把话说开了，别扭烟消云散，反而更近了些",
                    "你和{other}和好了，心里一块石头落了地",
                ),
                Perspective.RECIPIENT to listOf(
                    "{other}和你把话说开了，你也就顺势放下了",
                    "你和{other}重归于好，这段别扭总算翻篇了",
                ),
            ),
            Beats.MILESTONE to mapOf(
                Perspective.INITIATOR to listOf(
                    "你和{other}的交情又进了一步，这份情谊你挺珍惜",
                    "不知不觉，{other}成了你在这座城里重要的朋友",
                ),
                Perspective.RECIPIENT to listOf(
                    "你把{other}当成了真正的朋友，虽然嘴上不一定说",
                    "和{other}的关系越来越铁，你心里清楚",
                ),
            ),
        )
    }
}
