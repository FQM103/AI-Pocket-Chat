package com.situ.aichat.story

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 灵动岛卷一 T1-1：[StoryProgressModel] 的映射 / 钳位 / 节流判定。
 *
 * 三处显示（药丸 / 阅读器遮罩 / 书架卡片）共用本 object 换算，故这里锁死的每个值都是**用户可见口径**。
 * 断言一律从图纸 §3.1 规格**独立反推**、锁定文案**重新打字为字面量**（绝不引用实现常量——
 * 否则实现打错字测试跟着一起错，等于零保护）。
 */
class StoryProgressModelTest {

    private companion object {
        /** 浮点比较容差：段权重是 0.15/0.60 这类十进制小数，二进制不精确表示。 */
        const val EPS = 1e-9
    }

    // ── segIndex：5 相 → 4 段（归档与完成同占末段）──

    @Test
    fun segIndex_五相映射到四段_归档与完成同占末段() {
        assertEquals(0, StoryProgressModel.segIndex(StoryGenPhase.PREPARING))
        assertEquals(1, StoryProgressModel.segIndex(StoryGenPhase.WRITING))
        assertEquals(2, StoryProgressModel.segIndex(StoryGenPhase.FINALIZING))
        assertEquals(3, StoryProgressModel.segIndex(StoryGenPhase.ARCHIVING))
        assertEquals(3, StoryProgressModel.segIndex(StoryGenPhase.DONE))
    }

    @Test
    fun 段权重与段起点_逐值锁定() {
        assertArrayEquals(intArrayOf(15, 60, 17, 8), StoryProgressModel.SEGMENT_WEIGHTS)
        assertEquals(0.0, StoryProgressModel.SEG_START[0], EPS)
        assertEquals(0.15, StoryProgressModel.SEG_START[1], EPS)
        assertEquals(0.75, StoryProgressModel.SEG_START[2], EPS)
        assertEquals(0.92, StoryProgressModel.SEG_START[3], EPS)
        // 四段权重之和 = 100（否则末段填满 ≠ 进度 1.0）。
        assertEquals(100, StoryProgressModel.SEGMENT_WEIGHTS.sum())
    }

    // ── overall：五相精确值 ──

    @Test
    fun overall_五相精确值() {
        assertEquals(0.0, StoryProgressModel.overall(StoryGenPhase.PREPARING, 0.0), EPS)
        assertEquals(0.15, StoryProgressModel.overall(StoryGenPhase.WRITING, 0.0), EPS)
        assertEquals(0.75, StoryProgressModel.overall(StoryGenPhase.FINALIZING, 0.0), EPS)
        assertEquals(0.92, StoryProgressModel.overall(StoryGenPhase.ARCHIVING, 0.0), EPS)
        assertEquals(1.0, StoryProgressModel.overall(StoryGenPhase.DONE, 0.0), EPS)
    }

    @Test
    fun overall_撰写段随字数连续推进_公式为起点加六成乘比例() {
        // 规格：0.15 + 0.60 × f。半程 → 0.45；四分之一 → 0.30。
        assertEquals(0.45, StoryProgressModel.overall(StoryGenPhase.WRITING, 0.5), EPS)
        assertEquals(0.30, StoryProgressModel.overall(StoryGenPhase.WRITING, 0.25), EPS)
        assertEquals(0.75, StoryProgressModel.overall(StoryGenPhase.WRITING, 1.0), EPS)
    }

    @Test
    fun overall_撰写段满格恰好等于整理段起点_段间无缝无跳() {
        // 撰写写满 = 0.15+0.60 = 0.75 = FINALIZING 起点：LLM 超写钳停段尾后由 FINALIZING 无缝接管。
        assertEquals(
            StoryProgressModel.overall(StoryGenPhase.FINALIZING, 0.0),
            StoryProgressModel.overall(StoryGenPhase.WRITING, 1.0),
            EPS,
        )
    }

    @Test
    fun overall_E4_超写钳到段尾不越界() {
        assertEquals(0.75, StoryProgressModel.overall(StoryGenPhase.WRITING, 1.5), EPS)
        assertEquals(0.75, StoryProgressModel.overall(StoryGenPhase.WRITING, 99.0), EPS)
    }

    @Test
    fun overall_负比例钳到段首_不倒退() {
        assertEquals(0.15, StoryProgressModel.overall(StoryGenPhase.WRITING, -0.3), EPS)
    }

    @Test
    fun overall_非撰写相忽略字数比例() {
        // 只有撰写段吃 writingFraction，其余相是定值台阶（思考模型静默期诚实停住，不假爬）。
        assertEquals(0.0, StoryProgressModel.overall(StoryGenPhase.PREPARING, 0.9), EPS)
        assertEquals(0.75, StoryProgressModel.overall(StoryGenPhase.FINALIZING, 0.9), EPS)
        assertEquals(0.92, StoryProgressModel.overall(StoryGenPhase.ARCHIVING, 0.9), EPS)
        assertEquals(1.0, StoryProgressModel.overall(StoryGenPhase.DONE, 0.9), EPS)
    }

    @Test
    fun overall_五相单调不减() {
        val ordered = listOf(
            StoryProgressModel.overall(StoryGenPhase.PREPARING, 0.0),
            StoryProgressModel.overall(StoryGenPhase.WRITING, 0.0),
            StoryProgressModel.overall(StoryGenPhase.WRITING, 1.0),
            StoryProgressModel.overall(StoryGenPhase.FINALIZING, 0.0),
            StoryProgressModel.overall(StoryGenPhase.ARCHIVING, 0.0),
            StoryProgressModel.overall(StoryGenPhase.DONE, 0.0),
        )
        ordered.zipWithNext { a, b -> assertTrue("进度不得倒退：$a → $b", b >= a) }
    }

    // ── phaseLabel / shortLabel：逐字锁定（含中文省略号 U+2026）──

    @Test
    fun phaseLabel_四个进行相逐字锁定() {
        assertEquals("正在构思剧情…", StoryProgressModel.phaseLabel(StoryGenPhase.PREPARING, 1))
        assertEquals("正在撰写正文…", StoryProgressModel.phaseLabel(StoryGenPhase.WRITING, 1))
        assertEquals("正在整理成章…", StoryProgressModel.phaseLabel(StoryGenPhase.FINALIZING, 1))
        assertEquals("正在记下这段故事…", StoryProgressModel.phaseLabel(StoryGenPhase.ARCHIVING, 1))
    }

    @Test
    fun phaseLabel_省略号是中文一字U2026_不是三个英文句点() {
        val label = StoryProgressModel.phaseLabel(StoryGenPhase.PREPARING, 1)
        assertTrue("须以 U+2026 单字省略号收尾", label.endsWith('…'))
        assertFalse("绝不用三个英文句点", label.contains("..."))
        assertEquals("正在构思剧情".length + 1, label.length)
    }

    @Test
    fun phaseLabel_完成相带章号() {
        assertEquals("第 1 章写好了", StoryProgressModel.phaseLabel(StoryGenPhase.DONE, 1))
        assertEquals("第 7 章写好了", StoryProgressModel.phaseLabel(StoryGenPhase.DONE, 7))
        assertEquals("第 12 章写好了", StoryProgressModel.phaseLabel(StoryGenPhase.DONE, 12))
    }

    @Test
    fun shortLabel_五相各两字_逐字锁定() {
        assertEquals("构思", StoryProgressModel.shortLabel(StoryGenPhase.PREPARING))
        assertEquals("撰写", StoryProgressModel.shortLabel(StoryGenPhase.WRITING))
        assertEquals("整理", StoryProgressModel.shortLabel(StoryGenPhase.FINALIZING))
        assertEquals("归档", StoryProgressModel.shortLabel(StoryGenPhase.ARCHIVING))
        assertEquals("完成", StoryProgressModel.shortLabel(StoryGenPhase.DONE))
    }

    @Test
    fun shortLabel_恒两字_药丸短文案位容不下更多() {
        StoryGenPhase.entries.forEach {
            assertEquals("$it 的短词须恰两字", 2, StoryProgressModel.shortLabel(it).length)
        }
    }

    // ── expectedChars：E8 脏数据兜底 ──

    @Test
    fun expectedChars_E8_脏数据兜底两千() {
        assertEquals(2000, StoryProgressModel.expectedChars(0))
        assertEquals(2000, StoryProgressModel.expectedChars(-1))
        assertEquals(2000, StoryProgressModel.expectedChars(Int.MIN_VALUE))
    }

    @Test
    fun expectedChars_正常值原样透传() {
        assertEquals(1500, StoryProgressModel.expectedChars(1500))
        assertEquals(1, StoryProgressModel.expectedChars(1))
    }

    // ── shouldPushToPill：节流闸三分支 ──

    @Test
    fun shouldPushToPill_阶段变必推_无视时间与增量() {
        // 阶段跳变是用户最该立刻看到的事件：两道闸全不满足也必须推。
        assertTrue(
            StoryProgressModel.shouldPushToPill(
                lastOverall = 0.15, lastPhase = StoryGenPhase.PREPARING, lastAtMillis = 1_000L,
                newOverall = 0.15, newPhase = StoryGenPhase.WRITING, nowMillis = 1_000L,
            ),
        )
    }

    @Test
    fun shouldPushToPill_同阶段_双闸全满才推() {
        assertTrue(
            StoryProgressModel.shouldPushToPill(
                lastOverall = 0.20, lastPhase = StoryGenPhase.WRITING, lastAtMillis = 1_000L,
                newOverall = 0.25, newPhase = StoryGenPhase.WRITING, nowMillis = 3_000L,
            ),
        )
    }

    @Test
    fun shouldPushToPill_同阶段_时间不够不推() {
        assertFalse(
            StoryProgressModel.shouldPushToPill(
                lastOverall = 0.20, lastPhase = StoryGenPhase.WRITING, lastAtMillis = 1_000L,
                newOverall = 0.50, newPhase = StoryGenPhase.WRITING, nowMillis = 2_999L,
            ),
        )
    }

    @Test
    fun shouldPushToPill_同阶段_增量不够不推() {
        assertFalse(
            StoryProgressModel.shouldPushToPill(
                lastOverall = 0.20, lastPhase = StoryGenPhase.WRITING, lastAtMillis = 1_000L,
                newOverall = 0.209, newPhase = StoryGenPhase.WRITING, nowMillis = 60_000L,
            ),
        )
    }

    @Test
    fun shouldPushToPill_边界恰好满足_2000ms与0点01都取等号成立() {
        // ±1 精度：恰 2000ms / 恰 0.01 属「满足」，差 1ms 即不推。
        // 取 0.0→0.01 而非 0.20→0.21：后者的双精度差是 0.00999999999999998（< 0.01），
        // 是十进制字面量的二进制不精确所致、非规格边界。0.0→0.01 的差恰为 0.01 的机器表示，可精确压边界。
        assertTrue(
            StoryProgressModel.shouldPushToPill(
                lastOverall = 0.0, lastPhase = StoryGenPhase.WRITING, lastAtMillis = 0L,
                newOverall = 0.01, newPhase = StoryGenPhase.WRITING, nowMillis = 2_000L,
            ),
        )
        assertFalse(
            StoryProgressModel.shouldPushToPill(
                lastOverall = 0.0, lastPhase = StoryGenPhase.WRITING, lastAtMillis = 0L,
                newOverall = 0.01, newPhase = StoryGenPhase.WRITING, nowMillis = 1_999L,
            ),
        )
    }

    @Test
    fun shouldPushToPill_增量闸恰在0点01之下不推() {
        assertFalse(
            StoryProgressModel.shouldPushToPill(
                lastOverall = 0.0, lastPhase = StoryGenPhase.WRITING, lastAtMillis = 0L,
                newOverall = 0.0099, newPhase = StoryGenPhase.WRITING, nowMillis = 60_000L,
            ),
        )
    }

    @Test
    fun shouldPushToPill_进度倒退不推() {
        assertFalse(
            StoryProgressModel.shouldPushToPill(
                lastOverall = 0.50, lastPhase = StoryGenPhase.WRITING, lastAtMillis = 0L,
                newOverall = 0.40, newPhase = StoryGenPhase.WRITING, nowMillis = 60_000L,
            ),
        )
    }

    /**
     * 身份变（换书/换章）必推——**这一条正是上一条「进度倒退不推」的解药**：
     * 领跑那本书失败/被取消被移出映射后，接棒者进度必然更低（lead 取最高一路），若恰好同阶段，
     * 「涨 ≥0.01」恒假 ⇒ 药丸永远挂着那本已经不在生成的书（连深链都指向旧书）。
     */
    @Test
    fun shouldPushToPill_身份变必推_无视时间与倒退的进度() {
        assertTrue(
            StoryProgressModel.shouldPushToPill(
                lastOverall = 0.50, lastPhase = StoryGenPhase.WRITING, lastAtMillis = 0L,
                newOverall = 0.20, newPhase = StoryGenPhase.WRITING, nowMillis = 0L,
                identityChanged = true,
            ),
        )
    }

    @Test
    fun shouldPushToPill_身份未变_仍照吃双闸() {
        // 新参默认 false，既有调用行为一字不变。
        assertFalse(
            StoryProgressModel.shouldPushToPill(
                lastOverall = 0.20, lastPhase = StoryGenPhase.WRITING, lastAtMillis = 1_000L,
                newOverall = 0.50, newPhase = StoryGenPhase.WRITING, nowMillis = 2_999L,
                identityChanged = false,
            ),
        )
    }
}
