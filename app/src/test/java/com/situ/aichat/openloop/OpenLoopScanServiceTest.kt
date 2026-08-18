package com.situ.aichat.openloop

import com.situ.aichat.R
import com.situ.aichat.data.local.entity.OpenLoopEntity
import com.situ.aichat.data.local.entity.OpenLoopStatus
import com.situ.aichat.data.local.entity.OpenLoopType
import com.situ.aichat.prompt.PromptStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 活人感一期 P2 · T1-2/3/4/5（E6/E7/E9/E10/E15）：[OpenLoopScanService] 纯逻辑——扫描提示词逐字（§4.2）、
 * 解析容错矩阵、注入选择三分支、过期清理边界。断言从 §4.2/§4.3/§3.2 规格独立反推。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenLoopScanServiceTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private fun strings() = PromptStrings(RuntimeEnvironment.getApplication())

    private fun loop(
        uuid: String,
        content: String = "内容-$uuid",
        typeRaw: String = OpenLoopType.OPEN_TOPIC,
        dueAt: Long? = null,
        createdAt: Long = 0L,
        status: String = OpenLoopStatus.OPEN,
    ) = OpenLoopEntity(
        uuid = uuid,
        conversationUuid = "conv",
        characterUuid = "char",
        content = content,
        typeRaw = typeRaw,
        dueAt = dueAt,
        createdAt = createdAt,
        statusRaw = status,
    )

    private fun millis(iso: String): Long =
        LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli()

    // ── buildScanPrompt（§4.2 逐字 + existing 省略 + 回退名） ──

    @Test fun `prompt 带 ASCII 引号头 + JSON schema + 规则行`() {
        val p = OpenLoopScanService.buildScanPrompt("凛", "小柚", "2026-07-07 20:00", emptyList(), "对话文本", ledgerPromises = emptyList())
        assertTrue(p.contains("你在帮 AI 角色「凛」维护一份\"心里惦记的事\"清单"))
        assertTrue(
            p.contains(
                "{\"loops\":[{\"content\":\"一句话概括，不超过30字，第三人称\",\"type\":\"promise_char|user_event|open_topic\"," +
                    "\"due\":\"能从对话确定具体日期就输出 yyyy-MM-dd'T'HH:mm（只有日期没有时间就用 09:00），确定不了就 null\"}]," +
                    "\"resolved\":[\"已在清单上、但对话显示已经解决或已经过去的事的 uuid\"]}",
            ),
        )
        assertTrue(p.contains("规则：一次最多提取 2 条新的；纯闲聊话题不算；拿不准的宁可不提取。"))
        assertTrue(p.endsWith("对话记录：\n对话文本"))
    }

    @Test fun `prompt 空 existing 省略整段`() {
        val p = OpenLoopScanService.buildScanPrompt("凛", "小柚", "NOW", emptyList(), "CONV", ledgerPromises = emptyList())
        assertFalse("空清单不出现已在清单上的事段", p.contains("已在清单上的事"))
        // 当前时间行后直接跟「只输出 JSON」（中间仅一空行）。
        assertTrue(p.contains("当前时间：NOW\n\n只输出 JSON"))
    }

    @Test fun `prompt 非空 existing 逐行列出`() {
        val p = OpenLoopScanService.buildScanPrompt(
            "凛", "小柚", "NOW",
            listOf(loop("u1", content = "答应帮忙改简历"), loop("u2", content = "面试结果")),
            "CONV",
            ledgerPromises = emptyList(),
        )
        assertTrue(p.contains("已在清单上的事（不要重复提取）：\n- [u1] 答应帮忙改简历\n- [u2] 面试结果\n\n只输出 JSON"))
    }

    @Test fun `prompt 空名回退默认`() {
        val p = OpenLoopScanService.buildScanPrompt("", "", "NOW", emptyList(), "CONV", ledgerPromises = emptyList())
        assertTrue(p.contains("你在帮 AI 角色「AI 角色」维护"))
        assertTrue(p.contains("2. 用户 提到的"))
    }

    // ── T1-6（记忆改造四期·§3.6-①）：账本块哨兵 + 空账本与既有哨兵逐字节一致（E10） ──

    @Test fun `prompt 非空 ledgerPromises 出账本块`() {
        val p = OpenLoopScanService.buildScanPrompt(
            "凛", "小柚", "NOW", emptyList(), "CONV",
            ledgerPromises = listOf("周末一起看展", "帮忙改简历"),
        )
        assertTrue(
            p.contains("已进约定清单的事（由约定清单单独管理，不要重复提取）：\n- 周末一起看展\n- 帮忙改简历\n\n只输出 JSON"),
        )
    }

    @Test fun `prompt 空 ledgerPromises 与既有哨兵逐字节一致`() {
        val p = OpenLoopScanService.buildScanPrompt("凛", "小柚", "NOW", emptyList(), "CONV", ledgerPromises = emptyList())
        assertFalse("空账本不出现账本段", p.contains("已进约定清单的事"))
        // existing/ledger 均空 → 当前时间行后直接跟「只输出 JSON」（中间仅一空行·与四期前逐字节一致）。
        assertTrue(p.contains("当前时间：NOW\n\n只输出 JSON"))
    }

    // ── T1-5（记忆改造四期·§3.6-②）：excludeLedgerEchoes 注入去重（E9/E10） ──

    @Test fun `excludeLedgerEchoes 等值剔除含空白差异`() {
        val loops = listOf(loop("l1", content = "周末 一起 看展"), loop("l2", content = "面试结果"))
        val out = OpenLoopScanService.excludeLedgerEchoes(loops, listOf("周末一起看展")) // 去空白后等值 l1
        assertEquals("等值 loop（含空白差异）被剔除", listOf("l2"), out.map { it.uuid })
    }

    @Test fun `excludeLedgerEchoes 无交集直通`() {
        val loops = listOf(loop("l1", content = "看牙医"), loop("l2", content = "面试结果"))
        val out = OpenLoopScanService.excludeLedgerEchoes(loops, listOf("周末一起看展"))
        assertEquals("无交集 → 原列表直通", listOf("l1", "l2"), out.map { it.uuid })
    }

    @Test fun `excludeLedgerEchoes 双空直通`() {
        val loops = listOf(loop("l1"))
        assertEquals("账本空 → 直通原列表", loops, OpenLoopScanService.excludeLedgerEchoes(loops, emptyList()))
        assertTrue("loops 空 → 直通空", OpenLoopScanService.excludeLedgerEchoes(emptyList(), listOf("x")).isEmpty())
    }

    // ── parseScanResult 容错矩阵（T1-2/3·E7） ──

    @Test fun `parse 剥 json 围栏`() {
        val r = OpenLoopScanService.parseScanResult(
            "```json\n{\"loops\":[{\"content\":\"买生日礼物\",\"type\":\"promise_char\",\"due\":null}],\"resolved\":[]}\n```",
            zone,
        )
        assertEquals(1, r.newLoops.size)
        assertEquals("买生日礼物", r.newLoops[0].content)
        assertEquals(OpenLoopType.PROMISE_CHAR, r.newLoops[0].typeRaw)
        assertNull(r.newLoops[0].dueAt)
    }

    @Test fun `parse 未知 type 归 open_topic`() {
        val r = OpenLoopScanService.parseScanResult(
            "{\"loops\":[{\"content\":\"x\",\"type\":\"weird_kind\",\"due\":\"null\"}]}", zone,
        )
        assertEquals(OpenLoopType.OPEN_TOPIC, r.newLoops[0].typeRaw)
    }

    @Test fun `parse due 日期无时间补 09点`() {
        val r = OpenLoopScanService.parseScanResult(
            "{\"loops\":[{\"content\":\"考试\",\"type\":\"user_event\",\"due\":\"2026-07-09\"}]}", zone,
        )
        val expected = LocalDate.parse("2026-07-09").atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, r.newLoops[0].dueAt)
    }

    @Test fun `parse due 带时间正常解析`() {
        val r = OpenLoopScanService.parseScanResult(
            "{\"loops\":[{\"content\":\"面试\",\"type\":\"user_event\",\"due\":\"2026-07-09T14:30\"}]}", zone,
        )
        assertEquals(millis("2026-07-09T14:30"), r.newLoops[0].dueAt)
    }

    @Test fun `parse due 坏值归 null`() {
        val r = OpenLoopScanService.parseScanResult(
            "{\"loops\":[{\"content\":\"a\",\"type\":\"open_topic\",\"due\":\"下周吧\"}]}", zone,
        )
        assertNull(r.newLoops[0].dueAt)
    }

    @Test fun `parse 空白 content 丢弃`() {
        val r = OpenLoopScanService.parseScanResult(
            "{\"loops\":[{\"content\":\"   \",\"type\":\"open_topic\"},{\"content\":\"有效\",\"type\":\"open_topic\"}]}", zone,
        )
        assertEquals(listOf("有效"), r.newLoops.map { it.content })
    }

    @Test fun `parse 超 2 条截断`() {
        val r = OpenLoopScanService.parseScanResult(
            "{\"loops\":[{\"content\":\"a\"},{\"content\":\"b\"},{\"content\":\"c\"}]}", zone,
        )
        assertEquals(listOf("a", "b"), r.newLoops.map { it.content })
    }

    @Test fun `parse resolved uuid 列表`() {
        val r = OpenLoopScanService.parseScanResult(
            "{\"loops\":[],\"resolved\":[\"u1\",\" u2 \",\"\"]}", zone,
        )
        assertEquals(listOf("u1", "u2"), r.resolvedUuids)
    }

    @Test fun `parse 整体失败抛确定性异常`() {
        assertThrows(OpenLoopScanParseException::class.java) {
            OpenLoopScanService.parseScanResult("这不是 JSON 只是一句话", zone)
        }
    }

    // ── selectLoopsForInjection 三分支（T1-4·E9/E15） ──

    @Test fun `select 有到期项优先注入且按 dueAt 升序 cap2`() {
        val now = Instant.parse("2026-07-07T12:00:00Z")
        val nowMs = now.toEpochMilli()
        val loops = listOf(
            loop("late", dueAt = nowMs - 1000, createdAt = 1),          // 到期
            loop("earlier", dueAt = nowMs - 5000, createdAt = 2),       // 更早到期
            loop("earliest", dueAt = nowMs - 9000, createdAt = 3),      // 最早到期
            loop("future", dueAt = nowMs + 10000, createdAt = 4),       // 未到期
        )
        val sel = OpenLoopScanService.selectLoopsForInjection(loops, lastAssistantTime = null, now = now, zone = zone)
        assertEquals("到期项按 dueAt 升序取前 2", listOf("earliest", "earlier"), sel.map { it.uuid })
    }

    @Test fun `select 无到期今天首轮注入最新1条`() {
        val now = Instant.parse("2026-07-07T12:00:00Z")
        val loops = listOf(loop("old", createdAt = 100), loop("new", createdAt = 500))
        // lastAssistantTime null = 今天首轮
        val sel = OpenLoopScanService.selectLoopsForInjection(loops, lastAssistantTime = null, now = now, zone = zone)
        assertEquals(listOf("new"), sel.map { it.uuid })
    }

    @Test fun `select 无到期昨天回复也算今天首轮`() {
        val now = LocalDateTime.parse("2026-07-07T08:00").atZone(zone).toInstant()
        val yesterday = LocalDateTime.parse("2026-07-06T23:00").atZone(zone).toInstant()
        val loops = listOf(loop("a", createdAt = 1), loop("b", createdAt = 2))
        val sel = OpenLoopScanService.selectLoopsForInjection(loops, lastAssistantTime = yesterday, now = now, zone = zone)
        assertEquals(listOf("b"), sel.map { it.uuid })
    }

    @Test fun `select 无到期非今天首轮返回空`() {
        val now = LocalDateTime.parse("2026-07-07T20:00").atZone(zone).toInstant()
        val earlierToday = LocalDateTime.parse("2026-07-07T09:00").atZone(zone).toInstant()
        val loops = listOf(loop("a", createdAt = 1))
        val sel = OpenLoopScanService.selectLoopsForInjection(loops, lastAssistantTime = earlierToday, now = now, zone = zone)
        assertTrue("非今天首轮 + 无到期 → 不注入（E15）", sel.isEmpty())
    }

    @Test fun `select 空 loops 返回空`() {
        val now = Instant.parse("2026-07-07T12:00:00Z")
        assertTrue(OpenLoopScanService.selectLoopsForInjection(emptyList(), null, now, zone).isEmpty())
    }

    // ── expiredLoops 边界（T1-5·E10·各差 1ms 两侧） ──

    @Test fun `expire 无dueAt 14天边界`() {
        val now = Instant.ofEpochMilli(1_000_000_000_000L)
        val nowMs = now.toEpochMilli()
        val d14 = OpenLoopScanService.NO_DUE_EXPIRY_MS
        val atBoundary = loop("edge", createdAt = nowMs - d14)      // now-created == 14d，不 >14d → 不过期
        val overBoundary = loop("over", createdAt = nowMs - d14 - 1) // >14d → 过期
        assertEquals(listOf("over"), OpenLoopScanService.expiredLoops(listOf(atBoundary, overBoundary), now).map { it.uuid })
    }

    @Test fun `expire 有dueAt 过期48h边界`() {
        val now = Instant.ofEpochMilli(1_000_000_000_000L)
        val nowMs = now.toEpochMilli()
        val grace = OpenLoopScanService.DUE_EXPIRY_GRACE_MS
        val atBoundary = loop("edge", dueAt = nowMs - grace)       // now == due+48h，不 >  → 不过期
        val overBoundary = loop("over", dueAt = nowMs - grace - 1) // now > due+48h → 过期
        assertEquals(listOf("over"), OpenLoopScanService.expiredLoops(listOf(atBoundary, overBoundary), now).map { it.uuid })
    }

    @Test fun `expire 只碰 open 行`() {
        val now = Instant.ofEpochMilli(1_000_000_000_000L)
        val old = now.toEpochMilli() - OpenLoopScanService.NO_DUE_EXPIRY_MS - 1
        val loops = listOf(
            loop("openOld", createdAt = old, status = OpenLoopStatus.OPEN),
            loop("resolvedOld", createdAt = old, status = OpenLoopStatus.RESOLVED),
            loop("expiredOld", createdAt = old, status = OpenLoopStatus.EXPIRED),
        )
        assertEquals(listOf("openOld"), OpenLoopScanService.expiredLoops(loops, now).map { it.uuid })
    }

    // ── formatInjectionBlock（§4.3·到期行 vs 普通行 + head/guide） ──

    @Test fun `format 到期项就是今天行 非到期普通行`() {
        // Robolectric 默认 locale=en → 用解析出的资源值比对（避免钉死中英文，只锁装配结构）。
        val s = strings()
        val now = Instant.parse("2026-07-07T12:00:00Z")
        val nowMs = now.toEpochMilli()
        val loops = listOf(
            loop("due", content = "看牙医", dueAt = nowMs - 1),
            loop("nodue", content = "在纠结换工作"),
        )
        val block = OpenLoopScanService.formatInjectionBlock(loops, now, s)
        assertTrue("首行=段标题", block.startsWith(s.s(R.string.pb_loop_head) + "\n"))
        assertTrue("到期项走「就是今天」行", block.contains(s.s(R.string.pb_loop_due_line, "看牙医")))
        assertTrue("非到期项走普通行", block.contains(s.s(R.string.pb_loop_line, "在纠结换工作")))
        assertTrue("末尾=指引", block.endsWith(s.s(R.string.pb_loop_guide)))
    }

    @Test fun `format 空 loops 返回空串`() {
        assertEquals("", OpenLoopScanService.formatInjectionBlock(emptyList(), Instant.now(), strings()))
    }

    // ── 活人感二期 M2 · T1-2：长线回访选择 + 注入选择扩展 + 回访行格式化（E11/E12） ──

    @Test fun `selectRevisitLoop 取最旧`() {
        // SQL 已按 resolvedAt 升序过滤好，纯函数只取首个（= 最旧）。
        val c1 = loop("r1", status = OpenLoopStatus.RESOLVED)
        val c2 = loop("r2", status = OpenLoopStatus.RESOLVED)
        assertEquals("r1", OpenLoopScanService.selectRevisitLoop(listOf(c1, c2))?.uuid)
        assertNull("空候选 → null", OpenLoopScanService.selectRevisitLoop(emptyList()))
    }

    @Test fun `select 回访项首轮_拼最新open共2_回访在前`() {
        val now = Instant.parse("2026-07-07T12:00:00Z")
        val loops = listOf(
            loop("oldOpen", createdAt = 100),
            loop("newOpen", createdAt = 500),
            loop("revisit", status = OpenLoopStatus.RESOLVED, createdAt = 50),
        )
        // 今天首轮（lastAssistantTime=null）→ [回访项, 最新 open]，回访在前，cap2。
        val sel = OpenLoopScanService.selectLoopsForInjection(loops, lastAssistantTime = null, now = now, zone = zone)
        assertEquals(listOf("revisit", "newOpen"), sel.map { it.uuid })
    }

    @Test fun `select 回访项非首轮_只注入回访本身`() {
        val now = LocalDateTime.parse("2026-07-07T20:00").atZone(zone).toInstant()
        val earlierToday = LocalDateTime.parse("2026-07-07T09:00").atZone(zone).toInstant()
        val loops = listOf(
            loop("open1", createdAt = 100),
            loop("revisit", status = OpenLoopStatus.RESOLVED, createdAt = 50),
        )
        val sel = OpenLoopScanService.selectLoopsForInjection(loops, lastAssistantTime = earlierToday, now = now, zone = zone)
        assertEquals("非首轮 + 有回访 → 只回访项（无最新 open 拼接）", listOf("revisit"), sel.map { it.uuid })
    }

    @Test fun `select 到期open项存在_回访项让位`() {
        val now = Instant.parse("2026-07-07T12:00:00Z")
        val nowMs = now.toEpochMilli()
        val loops = listOf(
            loop("due", dueAt = nowMs - 1000, createdAt = 1),
            loop("revisit", status = OpenLoopStatus.RESOLVED, createdAt = 50),
        )
        val sel = OpenLoopScanService.selectLoopsForInjection(loops, lastAssistantTime = null, now = now, zone = zone)
        assertEquals("到期 open 项优先，回访项忽略（E4 纯函数自防）", listOf("due"), sel.map { it.uuid })
    }

    @Test fun `select 回访项自带过期dueAt_也不被当到期open项`() {
        val now = Instant.parse("2026-07-07T12:00:00Z")
        val nowMs = now.toEpochMilli()
        // 回访项是 resolved user_event，可能自带早已过去的 dueAt（如面试日期）——绝不能被当「到期 open 项」塞进 due 行。
        val loops = listOf(loop("revisit", dueAt = nowMs - 99999, status = OpenLoopStatus.RESOLVED, createdAt = 50))
        val sel = OpenLoopScanService.selectLoopsForInjection(loops, lastAssistantTime = null, now = now, zone = zone)
        assertEquals("走回访分支而非到期路径", listOf("revisit"), sel.map { it.uuid })
    }

    @Test fun `select 输入无resolved项_输出与现状逐字节一致`() {
        // 全 open 输入（无回访项）：新扩展的 due statusRaw=open 过滤与回访分支均不改变现状——今天首轮取最新 1 条。
        val now = Instant.parse("2026-07-07T12:00:00Z")
        val loops = listOf(loop("a", createdAt = 100), loop("b", createdAt = 500))
        val sel = OpenLoopScanService.selectLoopsForInjection(loops, lastAssistantTime = null, now = now, zone = zone)
        assertEquals(listOf("b"), sel.map { it.uuid })
    }

    @Test fun `format 回访项走 pb_loop_revisit_line 其余各行不变`() {
        val s = strings()
        val now = Instant.parse("2026-07-07T12:00:00Z")
        val nowMs = now.toEpochMilli()
        val loops = listOf(
            loop("revisit", content = "面试结果", status = OpenLoopStatus.RESOLVED),
            loop("due", content = "看牙医", dueAt = nowMs - 1),
            loop("nodue", content = "在纠结换工作"),
        )
        val block = OpenLoopScanService.formatInjectionBlock(loops, now, s)
        assertTrue("回访项走回访行", block.contains(s.s(R.string.pb_loop_revisit_line, "面试结果")))
        assertTrue("到期项仍走「就是今天」行", block.contains(s.s(R.string.pb_loop_due_line, "看牙医")))
        assertTrue("非到期项仍走普通行", block.contains(s.s(R.string.pb_loop_line, "在纠结换工作")))
        assertTrue("首行=段标题", block.startsWith(s.s(R.string.pb_loop_head) + "\n"))
        assertTrue("末尾=指引", block.endsWith(s.s(R.string.pb_loop_guide)))
    }
}
