package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.RelationshipQuality
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * T1-4（图纸 §7）：**无名分角色（milestones=空、archetypeId=null）逐字节回归钉**。
 * 基线串于 chunk1 从现行代码实跑抓取、写死为字面量；chunk5 三分支调度改造后 else 分支仍走 legacy
 * [buildRelationshipDescription]，输出必须逐字节不变（「旧输入→输出逐字节不变」PITFALLS §1e）。
 *
 * ctx 用 MockK 桩掉（buildCharacterGrowthContent 只读 5 个属性）；character 为真实 [CharacterEntity]
 * （成长访问器需真解码）；milestones 恒空 → 走无名分分支。
 */
class PromptBuilderGrowthLegacyPinTest {

    private val fixedNow = Instant.ofEpochMilli(1_700_000_000_000L)

    private fun character(q: RelationshipQuality): CharacterEntity = CharacterEntity(
        uuid = "u", name = "小雨", creationDate = 0L,
        relationshipQualityJSON = GrowthJson.encode(q),
        // personality 全默认 50（40..60 中性→性格段空）；无兴趣/阶段 → 仅关系段（+可能洞察）。
    )

    private fun render(q: RelationshipQuality): String {
        val ctx = mockk<PromptBuilder.BuildContext>()
        every { ctx.appSettings } returns AppSettings()
        every { ctx.character } returns character(q)
        every { ctx.resolvedUserName } returns "小明"
        every { ctx.now } returns fixedNow
        every { ctx.milestones } returns emptyList<MilestoneEntity>()
        return buildCharacterGrowthContent(ctx)
    }

    @Test fun `INITIAL 分数 - 逐字节基线`() {
        val out = render(RelationshipQuality())
        assertEquals(EXPECTED_INITIAL, out)
    }

    @Test fun `含洞察高分 - 逐字节基线`() {
        // 触发洞察 #2(亲近≥65&张力≥55) 与 #5(尊重≥75&默契≥75&信任≥75)
        val q = RelationshipQuality(
            familiarity = 70, trust = 80, closeness = 70, rapport = 80,
            respect = 80, funValue = 30, tension = 60, attachment = 60,
        )
        val out = render(q)
        assertEquals(EXPECTED_HIGH, out)
    }

    @Test fun `含中性静默维 - 逐字节基线`() {
        // 部分维落 40..60 中性区（legacy 静默）
        val q = RelationshipQuality(
            familiarity = 50, trust = 50, closeness = 15, rapport = 85,
            respect = 50, funValue = 10, tension = 50, attachment = 50,
        )
        val out = render(q)
        assertEquals(EXPECTED_NEUTRAL, out)
    }

    private companion object {
        // 基线串 = chunk1 从现行代码实跑抓取（改前逐字节）。chunk5 调度改造后必须仍逐字节相同。
        const val EXPECTED_INITIAL = "[Character Growth Status]\n" +
            "你和小明的互动方式：\n" +
            "- 你们还很生疏：聊天带有拘谨感，你会用敬语或完整称呼，话题停留在表面\n" +
            "- 你还在观察ta：保持礼貌但有距离感，偶尔试探性地分享无关紧要的事来看对方反应\n" +
            "- 你们情感距离很远：互动仅限于事务性交流，不会主动关心对方私事\n" +
            "- 你们缺乏默契：经常误解对方的意思，聊天容易冷场或跑题\n" +
            "- 你对ta尊重一般：基本礼貌有但不会特别在意对方的想法和边界\n" +
            "- 你们互动有些平淡：偶尔有趣但大部分时候聊天比较平\n" +
            "- 你们相处毫无压力：完全放松自在，没有需要小心翼翼的话题\n" +
            "- 你对ta没有依恋：ta不回消息你不会在意，各自有各自的生活"

        const val EXPECTED_HIGH = "[Character Growth Status]\n" +
            "你和小明的互动方式：\n" +
            "- 你们比较熟了：清楚对方的喜好和习惯，聊天不用刻意找话题，偶尔能接上对方的梗\n" +
            "- 你比较信任ta：愿意主动分享心情和烦恼，ta说错话时你倾向于善意理解\n" +
            "- 你们比较亲近：会自然地关心对方生活细节，分享有趣的事时第一个想到对方\n" +
            "- 你们比较有默契：经常能接住对方的话，聊天节奏合拍\n" +
            "- 你比较尊重ta：认真对待对方说的话，不会随意否定对方的想法\n" +
            "- 你们互动有些平淡：偶尔有趣但大部分时候聊天比较平\n" +
            "你们的关系像老朋友一样稳固可靠，但互动缺乏新鲜感。你可以偶尔主动制造一些小惊喜或聊一些出其不意的话题来打破平淡。\n" +
            "你和小明的关系很深但也很复杂——亲近中带着未解决的摩擦。你们的对话可能突然从温馨变得尖锐，但这恰恰说明你们足够在乎彼此。\n" +
            "你和小明之间有一种深层的理解和尊重。你们的交流已经超越了普通的聊天，是真正的心灵对话。"

        const val EXPECTED_NEUTRAL = "[Character Growth Status]\n" +
            "你和小明的互动方式：\n" +
            "- 你们情感距离很远：互动仅限于事务性交流，不会主动关心对方私事\n" +
            "- 你们默契极佳：一个眼神一个字就懂对方要说什么，聊天有强烈的心有灵犀感\n" +
            "- 你们互动很沉闷：聊天缺乏活力，你不太会主动制造话题或惊喜"
    }
}
