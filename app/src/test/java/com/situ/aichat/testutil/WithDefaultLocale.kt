package com.situ.aichat.testutil

import java.util.Locale

/**
 * 在临时替换的 JVM 默认 Locale 下执行断言块，结束后恢复原值（防污染同 JVM 的其他测试）。
 * 用途：锁「机器路径字符串与设备语言无关」类不变量（2026-07-12 性能线程专项：钱路幂等 key / 宠物资产路径）。
 * 是否真能在 JVM 层咬住由 K1 红跑实证（JVM=CLDR、Android=ICU，ar 的 %d 数字本地化两边同源）。
 */
fun withDefaultLocale(locale: Locale, block: () -> Unit) {
    val previous = Locale.getDefault()
    Locale.setDefault(locale)
    try {
        block()
    } finally {
        Locale.setDefault(previous)
    }
}
