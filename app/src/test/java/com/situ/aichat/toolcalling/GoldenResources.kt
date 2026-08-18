package com.situ.aichat.toolcalling

/**
 * Phase 0 黄金资源加载器：从 `src/test/resources/golden/toolcalling/` 读冻结字节。
 *
 * 这些 golden 是「动任何工具调用代码前」对当前装配的字节快照（由一次性的 dump 生成·见 Phase 0 实现说明），
 * 用作 ①④ 重构 / ③ 截断阀的安全网——重构后装配/序列化须字节不变（除非有意改并同步更新 golden）。
 */
object GoldenResources {
    fun read(name: String): String =
        GoldenResources::class.java.getResourceAsStream("/golden/toolcalling/$name")
            ?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("缺少 golden 资源：/golden/toolcalling/$name（先跑 dump 生成？）")
}
