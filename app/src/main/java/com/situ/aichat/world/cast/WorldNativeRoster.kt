package com.situ.aichat.world.cast

import com.situ.aichat.world.WorldIds

/**
 * 官方原住民花名册（W6 图纸 §2/§4.3·图纸 §9 禁改）：聚合 20 位 def（[WorldNativeCastHome] 9 + [WorldNativeCastFar] 11）
 * + 出厂关系网 20 条边。花名册是**静态常量**（slug 恒定）；未来版本扩充 = 追加 def（`ensureSeeded` 天然兼容新增）。
 *
 * 出厂边规则（§4.3·[WorldNativeRosterTest] 不变量钉死）：`closeness ≥35` 的边 [WorldFactoryEdge.types] 必含「朋友」
 * （防 W4 引擎里程碑误发「处成了朋友」）；本表无 ≥70 边（密友留给玩家亲手养成）；色彩全落 W4 `BASE_COLORS`（恋爱色不用）；
 * 20 人构成**单一连通分量**（从 su_wan 出发 BFS 全可达·决策 26 引荐链越走越远·含 #19/#20 邮差桥边）。
 */
object WorldNativeRoster {

    /** 官方 20 位原住民（声明序 = §4.2 ①–⑳：上半 9 + 下半 11）·**静态常量·图纸 §9 禁改**。 */
    val OFFICIAL: List<WorldNativeDef> = WorldNativeCastHome.ALL + WorldNativeCastFar.ALL

    private val OFFICIAL_BY_SLUG: Map<String, WorldNativeDef> = OFFICIAL.associateBy { it.slug }

    /**
     * 用户自建居民 def（战役 B·图纸 §3.2 双源合流）：运行期由 `WorldResidentService.loadIntoRoster` / `refreshRoster`
     * **全量替换**。`@Volatile` + 不可变列表 = 线程安全（读侧每次现查、无锁）。
     */
    @Volatile
    private var userDefs: List<WorldNativeDef> = emptyList()

    /** 全量替换用户居民 def（图纸 §3.2·唯一改这一处让所有消费点零改动自动吃到用户居民）。 */
    fun registerUserDefs(defs: List<WorldNativeDef>) {
        userDefs = defs
    }

    /** 全部居民 = 官方 20 位 + 用户自建（合流视图·官方在前·`ensureSeeded` 播种/上图剪影/星图皆读此）。 */
    val ALL: List<WorldNativeDef> get() = OFFICIAL + userDefs

    /** 按 slug 取 def（官方优先·再查用户·未知 → null·图纸 §3.2「每次现查·50 上限无性能问题」）。 */
    fun bySlug(slug: String): WorldNativeDef? =
        OFFICIAL_BY_SLUG[slug] ?: userDefs.firstOrNull { it.slug == slug }

    /** 按 nativeId（`native:<slug>`）取 def（非原住民 id / 未知 → null）。 */
    fun byNativeId(nativeId: String): WorldNativeDef? =
        if (nativeId.startsWith(WorldIds.NATIVE_PREFIX)) bySlug(nativeId.removePrefix(WorldIds.NATIVE_PREFIX)) else null

    /** 城市解析（§4.6·委托 [WorldNativeDef.cityNameOf]）：把 def 在 seed 图集里解析成城名（查无 → 「远方」）。 */
    fun cityNameOf(def: WorldNativeDef, seed: Long): String = WorldNativeDef.cityNameOf(def.cityId, seed)

    /** 某 slug 参与的全部出厂边（声明序·出厂边落地遍历用·含方向 slugA/slugB）。 */
    fun factoryEdgesOf(slug: String): List<WorldFactoryEdge> =
        FACTORY_EDGES.filter { it.slugA == slug || it.slugB == slug }

    /** 某 slug 的出厂边对端 def（顺序 = 边表声明序）——引荐候选遍历入口。 */
    fun factoryNeighborsOf(slug: String): List<WorldNativeDef> =
        FACTORY_EDGES.mapNotNull { edge ->
            when (slug) {
                edge.slugA -> bySlug(edge.slugB)
                edge.slugB -> bySlug(edge.slugA)
                else -> null
            }
        }

    /**
     * 出厂关系网（20 条·§4.3 逐字锁死·声明序）。每条无向声明、双向落地：A→B 取 `*AB`、B→A 取 `*BA`。
     * #3/#8 色彩按 §4.3 修正值（欣赏→敬重/投缘·「欣赏」不在 `BASE_COLORS`）；#19/#20 = 邮差桥边（并三圈为一）。
     */
    val FACTORY_EDGES: List<WorldFactoryEdge> = listOf(
        WorldFactoryEdge("su_wan", "lin_moyu", listOf("相识", "邻里", "朋友"),
            42, 50, "护着", 38, 45, "敬重", "同一条街的店开了五年，谁忙不过来另一个就搭把手"),
        WorldFactoryEdge("su_wan", "ming_qian", listOf("相识", "朋友"),
            40, 46, "投缘", 44, 48, "敬重", "拾光咖啡馆的茶一直是明前家茶园供的，一来二去处成了朋友"),
        WorldFactoryEdge("su_wan", "yan_zhen", listOf("相识", "朋友"),
            38, 44, "敬重", 36, 40, "投缘", "店里的杯盘全出自严真之手，苏晚说客人夸杯子比夸咖啡多"),
        WorldFactoryEdge("su_wan", "you_xin", listOf("相识", "朋友"),
            36, 40, "惦记", 40, 42, "投缘", "游信每回进云野镇，第一杯咖啡永远赊在拾光的账上"),
        WorldFactoryEdge("lin_moyu", "lu_wangxing", listOf("相识", "笔友", "朋友"),
            48, 55, "投缘", 48, 55, "惦记", "通了三年信的笔友，聊诗也聊星星，谁也没见过谁"),
        WorldFactoryEdge("zhou_baichuan", "a_luo", listOf("相识", "远亲", "朋友"),
            58, 62, "护着", 60, 64, "敬重", "阿螺是他远房侄女，从小在渡口的船板上跑大"),
        WorldFactoryEdge("zhou_baichuan", "deng_bo", listOf("相识", "旧识", "朋友"),
            52, 60, "惦记", 50, 62, "惦记", "年轻时在同一条南线货船上跑过三年，一个回了渡口一个上了灯塔"),
        WorldFactoryEdge("wen_qing", "chi_yumian", listOf("相识", "同好", "朋友"),
            44, 42, "投缘", 42, 40, "投缘", "一次手作市集上互相看对了眼的两双手，此后互寄画和伞"),
        WorldFactoryEdge("wen_qing", "mu_xing", listOf("相识", "同好"),
            32, 34, "好奇", 30, 32, "敬重", "隔着半个星球互评作品——她画光，他拍光"),
        WorldFactoryEdge("yan_zhen", "gu_yaoshan", listOf("相识", "师徒", "朋友"),
            66, 68, "敬重", 62, 66, "护着", "十六岁拜进窑门的关门弟子，学满十年自立门户"),
        WorldFactoryEdge("yan_zhen", "shi_yu", listOf("相识", "同好"),
            34, 38, "敬重", 34, 38, "敬重", "一个刻石一个塑陶，每年手艺节都要切磋一场"),
        WorldFactoryEdge("a_luo", "jiang_hai", listOf("相识", "邻里", "朋友"),
            46, 52, "敬重", 42, 48, "护着", "阿螺那条小渔船大修小补都是江海的手笔"),
        WorldFactoryEdge("a_luo", "deng_bo", listOf("相识", "旧识", "朋友"),
            40, 44, "惦记", 46, 50, "惦记", "涨潮时她在滩头唱歌，落潮时灯塔用灯语回她——忘年的交情"),
        WorldFactoryEdge("mu_xing", "han_susu", listOf("相识", "朋友"),
            44, 48, "感激", 40, 44, "护着", "在温泉驿一住三个雪季的老房客，极光季的房间永远给他留着"),
        WorldFactoryEdge("bai_zhi", "tang_jiming", listOf("相识", "邻里", "朋友"),
            46, 48, "投缘", 44, 46, "敬重", "谷口的蜜和谷里的药材常年搭着卖，账都懒得分"),
        WorldFactoryEdge("bai_zhi", "han_susu", listOf("相识", "旧识"),
            34, 40, "敬重", 34, 42, "感激", "极北的药材一半经白芷的手——雪原风寒，全靠她的方子"),
        WorldFactoryEdge("ming_qian", "zhang_zhuqing", listOf("相识", "旧识", "朋友"),
            48, 46, "敬重", 40, 44, "护着", "打小坐在他的竹椅上听戏长大，茶篓年年换新"),
        WorldFactoryEdge("lu_wangxing", "shen_zhou", listOf("相识", "朋友"),
            56, 58, "惦记", 46, 52, "投缘", "青梅竹马——一个管天上的星，一个管海上的船"),
        WorldFactoryEdge("you_xin", "han_susu", listOf("相识", "旧识", "朋友"),
            38, 44, "感激", 42, 46, "护着", "跑了十几年极北邮路的落脚点——再冷的雪夜，温泉驿都给邮差留着一间房"),
        WorldFactoryEdge("you_xin", "deng_bo", listOf("相识", "旧识", "朋友"),
            36, 42, "敬重", 40, 48, "感激", "荒角一年到头的信全靠游信一趟趟送上灯塔，灯伯的回信永远只有一句「灯亮着」"),
    )
}
