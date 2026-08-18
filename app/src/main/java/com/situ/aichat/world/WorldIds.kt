package com.situ.aichat.world

/**
 * 世界系统 ID 常量与约定（契约 `FABLE5_WORLD_SYSTEM_PROPOSAL.md` §5 / W1 图纸 §3）。
 *
 * 世界里的「参与者」是**混合域**：用户（[USER_ID]）、正式角色（角色 uuid）、原住民（`native:<slug>`）。
 * 关系边 / 事件 / 旅行的两端都可能是这三者之一——故用前缀区分原住民、用固定串标识用户，
 * 而非各建一张表。删除清理走仓库层事务（关系表**不设 FK**，跨这三种域无法用 Room FK 表达）。
 */
object WorldIds {
    /** 用户在世界里的固定身份串（区别于任何角色 uuid / 原住民 id）。 */
    const val USER_ID = "user"

    /** 家乡城 cityId（默认住址 = 和用户同城·契约 §6「默认同城 = 家乡云野镇」）。 */
    const val HOME_CITY_ID = "city_yunye"

    /** 原住民 id 前缀（花名册在 W3 代码内定义；本块不含任何 slug 常量）。 */
    const val NATIVE_PREFIX = "native:"

    /** 组装原住民 id：`native:<slug>`。 */
    fun nativeId(slug: String): String = NATIVE_PREFIX + slug

    /** 判定某 id 是否为原住民（备份幽灵过滤 / 清理时区分域用）。 */
    fun isNative(id: String): Boolean = id.startsWith(NATIVE_PREFIX)

    /**
     * 无向对键：把两端排序后拼成 `min|max`，令 A↔B 无论方向都落同一 pairKey
     * （关系事件流水按 pairKey 聚合两人之间的历史，与有向边的 fromId/toId 分工）。
     */
    fun pairKey(a: String, b: String): String = if (a <= b) "$a|$b" else "$b|$a"

    /** 旅行方式五值（[WorldTravelEntity].modeRaw 取值域；旅行公式/费用在 W7）。 */
    object TravelModes {
        const val WALK = "walk"
        const val BIKE = "bike"
        const val CAR = "car"
        const val TRAIN = "train"
        const val PLANE = "plane"
    }
}
