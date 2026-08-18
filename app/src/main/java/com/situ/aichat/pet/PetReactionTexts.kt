package com.situ.aichat.pet

import kotlin.random.Random

/**
 * 宠物照顾后的反应文案（1:1 iOS `PetReactionTexts`）：喂食/清洁/玩耍/治疗/寻找后弹一句个性化气泡。
 * 5 性格 × 5 操作 × 4 条 = 100 条本地文案库（不调 LLM）。`random` 注入便于确定性单测。
 */
object PetReactionTexts {

    /** 反应动作类型（独立于照顾操作枚举，避免耦合），1:1 iOS `ReactionAction`。 */
    enum class ReactionAction { FEED, CLEAN, PLAY, TREAT, SEARCH }

    /** 随机返回一条反应文案；查不到（理论不会）回 "…"。1:1 iOS `randomReaction`。 */
    fun randomReaction(
        personality: PetPersonalityType,
        action: ReactionAction,
        random: Random = Random.Default,
    ): String {
        val texts = reactions[personality]?.get(action)
        return texts?.randomOrNull(random) ?: "…"
    }

    internal val reactions: Map<PetPersonalityType, Map<ReactionAction, List<String>>> = mapOf(
        PetPersonalityType.LIVELY to mapOf(
            ReactionAction.FEED to listOf("好吃好吃！再来一份！", "吃饱啦～出去玩吧！", "嗷呜～真香！", "吃完还想吃！"),
            ReactionAction.CLEAN to listOf("哇！香香的！", "洗完澡好清爽～", "水好舒服！", "我是最干净的！"),
            ReactionAction.PLAY to listOf("太好玩了！再来！", "耶耶耶！好开心！", "哈哈哈停不下来！", "玩累了…才怪！"),
            ReactionAction.TREAT to listOf("药药好苦…但我好了！", "嘿嘿，我又活蹦乱跳啦！", "谢谢你照顾我！", "生病也挡不住我！"),
            ReactionAction.SEARCH to listOf("我回来啦！想我没！", "外面太好玩了！", "下次带我一起！", "嘿嘿，被你找到了！"),
        ),
        PetPersonalityType.LAZY to mapOf(
            ReactionAction.FEED to listOf("嗯…吃完了…继续睡…", "还不错…打个哈欠", "饱了…好困…", "吃完不想动了…"),
            ReactionAction.CLEAN to listOf("嗯…舒服…", "洗完可以继续躺了吧", "好暖…要睡着了…", "别搓了…我困…"),
            ReactionAction.PLAY to listOf("好累…休息一下…", "玩一会就好…", "我躺着玩不行吗…", "…算了挺好玩的"),
            ReactionAction.TREAT to listOf("药好苦…让我睡会儿…", "吃完药更困了…", "嗯…好多了…zzz", "别吵…我在恢复…"),
            ReactionAction.SEARCH to listOf("回来了…好累…", "外面好吵…还是家里好…", "走了好远…让我躺会儿…", "…嗯，我回来了"),
        ),
        PetPersonalityType.CLINGY to mapOf(
            ReactionAction.FEED to listOf("谢谢你喂我！最喜欢你了！", "你喂的最好吃！", "能一直这样就好了", "喂我的时候不要走开嘛"),
            ReactionAction.CLEAN to listOf("你帮我洗澡好开心", "抱紧…不要放开", "能多洗一会吗", "洗完也要陪我哦"),
            ReactionAction.PLAY to listOf("和你玩最开心！", "不要停不要停！", "再多陪我一会嘛", "你是不是要走了…才没有"),
            ReactionAction.TREAT to listOf("你在就不怕了…", "谢谢你一直陪着我", "有你在我好安心", "不要离开我好不好"),
            ReactionAction.SEARCH to listOf("终于找到你了！", "我好想你…", "不要再让我一个人了", "我以后哪也不去了…"),
        ),
        PetPersonalityType.INDEPENDENT to mapOf(
            ReactionAction.FEED to listOf("嗯。", "还行。", "…谢了", "正好饿了"),
            ReactionAction.CLEAN to listOf("…随便吧", "算你主动", "嗯，可以", "别弄太久"),
            ReactionAction.PLAY to listOf("偶尔玩玩也不错", "就这一次", "…还行吧", "我自己也能玩"),
            ReactionAction.TREAT to listOf("不用担心，小事", "我没那么脆弱", "…好了", "不用大惊小怪"),
            ReactionAction.SEARCH to listOf("我只是出去走走", "别紧张", "…我会回来的", "自由真好"),
        ),
        PetPersonalityType.TIMID to mapOf(
            ReactionAction.FEED to listOf("…谢谢你（小声）", "好…好好吃…", "我可以吃吗…真的吗", "…你对我真好"),
            ReactionAction.CLEAN to listOf("轻一点…", "水不会太烫吧…", "…其实挺舒服的", "谢谢…洗干净了"),
            ReactionAction.PLAY to listOf("我…我试试…", "好像挺好玩的…", "不会弄坏吧…", "…嘿嘿（偷偷开心）"),
            ReactionAction.TREAT to listOf("好苦…但我忍住了…", "我会乖乖吃药的…", "…谢谢你", "我…我没事了…"),
            ReactionAction.SEARCH to listOf("对不起…我迷路了…", "外面好可怕…", "我再也不乱跑了…", "…终于回家了"),
        ),
    )
}
