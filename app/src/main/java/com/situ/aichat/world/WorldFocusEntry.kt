package com.situ.aichat.world

/**
 * 聊天 → 世界的一次性聚焦信箱（W13 图纸 §3.6·镜像 [WorldDebugEntry] 的 set/take）：聊天状态行胶囊点击时
 * [set] 一个 focusSpec（`town:<cityId>` / `interior:<cityId>:<placeId>`），[com.situ.aichat.ui.world.WorldViewModel]
 * bootstrap 完成后 [take] 消费一次（取即清·单值·@Volatile 跨线程可见）。非法 spec 由 `parseDebugScene` 验真安全落空。
 *
 * 与 [WorldDebugEntry] 分工：debug 直达仅 `BuildConfig.DEBUG`；本信箱是生产功能（聊天页跳世界看 TA 在哪）。
 */
object WorldFocusEntry {

    @Volatile
    private var pending: String? = null

    /** 存一次聚焦意图（聊天状态行点击时写）。 */
    fun set(spec: String) {
        pending = spec
    }

    /** 取并清（消费一次·再取为 null）。 */
    fun take(): String? = pending.also { pending = null }
}
