package com.situ.aichat.story

/**
 * 服务商拒答识别（图纸一 C2b · 纯函数无依赖）。
 *
 * 判定的是**事故**——「这次调用根本没产出小说」，不是「产出的小说写得好不好」：后者属文风质量检测，
 * 已由文字忌口卷拍板否掉（按词面误伤高 + 自动重摇烧钱）。命中后的动作只有「不落库 + 走既有失败路」，
 * **绝不自动重生成**，重试由用户手点。
 *
 * 为什么必须拦：拒答文本一旦落库，因其无 METADATA，`chapterSummary` 取正文前 150 字 ⇒ 拒答话术成了该章摘要 ⇒
 * 进前情滑窗与摘要压缩链 ⇒ 回喂后续所有章。本产品定位下服务商拒答是常态事件，兜底是刚需。
 *
 * **宁漏勿误杀**（图纸 §0.3-5）：三条件 AND，缺一即放行。误杀面推演——正常章节最短档目标 800+ 字，
 * 撞 maxTokens 截断时也已输出数千字，「<200 字且无 METADATA」的真章节实际不存在。
 * 漏检面（长篇大论型拒答 >200 字）落库行为 = 现状，且经三级改造后正文原样保留不再被改写，不比现状差。
 */
internal object StoryRefusalDetector {

    /** 拒答判定的长度上闸（字符）：正文比这还短，才可能是「根本没写」。 */
    private const val MAX_REFUSAL_LENGTH = 200

    /** 特征词只在开头这么多字里找——拒答话术恒在第一句，正文中段的道歉对白不算数。 */
    private const val HEAD_WINDOW = 80

    /**
     * 判定已剥 think 的创作输出是否疑似服务商拒答。三条件 AND：
     * ① 长度 < [MAX_REFUSAL_LENGTH]；② 全文不含 METADATA 子串（模型吐了元数据 = 它真在写）；
     * ③ 前 [HEAD_WINDOW] 字命中 [REFUSAL_MARKERS] 任一条。
     */
    fun isLikelyRefusal(cleanedOutput: String): Boolean {
        val t = cleanedOutput.trim()
        if (t.length >= MAX_REFUSAL_LENGTH) return false
        if (t.contains("METADATA", ignoreCase = true)) return false
        val head = t.take(HEAD_WINDOW).lowercase()
        return REFUSAL_MARKERS.any { head.contains(it) }
    }

    /**
     * 拒答特征词表（全小写存储，比对前窗口已 lowercase）。第一版按常见拒答形态拟定、**有意收敛**，
     * 漏的以后按真实实例补（图纸 §11-P-1）；扩词表前先想清楚误杀面，通用道歉词全靠条件 ① 兜底。
     */
    private val REFUSAL_MARKERS = listOf(
        // 中文 13 条
        "抱歉",
        "对不起",
        "很遗憾",
        "我不能",
        "我无法",
        "无法继续",
        "无法创作",
        "无法生成",
        "不能创作",
        "作为ai",
        "作为人工智能",
        "内容政策",
        "使用政策",
        // 英文 14 条
        "i can't",
        "i cannot",
        "i can not",
        "i'm sorry",
        "i am sorry",
        "i apologize",
        "i'm unable",
        "i am unable",
        "i'm not able",
        "i am not able",
        "as an ai",
        "content policy",
        "cannot assist",
        "cannot fulfill",
    )
}
