package com.situ.aichat.world

import kotlin.random.Random

/**
 * 世界确定性种子派生（契约 `FABLE5_WORLD_SYSTEM_PROPOSAL.md` §7 / W2 图纸 §3.2）：世界一切「随机」都从
 * 世界种子确定性派生 → 可重放、零 token（LLM 只在真互动那一下花·§7）。
 *
 * **算法与常量锁死 = 世界的「物理常数」**（图纸 §9 禁改）：splitmix64 / FNV-1a-64 的每个常量与运算顺序、
 * 两个 [derive] 的组合方式一个字不许变——金标向量测试（`WorldSeedsTest`）看门，改一位 = 全世界穿越。
 */
object WorldSeeds {

    /** splitmix64（常量锁死·金标测试看门——改一位=全世界穿越）。 */
    fun splitmix64(x: Long): Long {
        var z = x + 0x9E3779B97F4A7C15uL.toLong()
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
        return z xor (z ushr 31)
    }

    /** FNV-1a 64（UTF-8 字节·offset 0xcbf29ce484222325·prime 0x100000001b3）。 */
    fun fnv1a64(s: String): Long {
        var hash = 0xCBF29CE484222325uL.toLong()
        val prime = 0x100000001B3uL.toLong()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            hash = (hash xor (b.toLong() and 0xFF)) * prime
        }
        return hash
    }

    /** 盐链派生：acc = root；每个盐 acc = splitmix64(acc xor fnv1a64(salt))。 */
    fun derive(root: Long, vararg salts: String): Long {
        var acc = root
        for (salt in salts) {
            acc = splitmix64(acc xor fnv1a64(salt))
        }
        return acc
    }

    /** 数值派生（epochDay 等）：splitmix64(splitmix64(root xor fnv1a64(salt)) xor n)。 */
    fun derive(root: Long, salt: String, n: Long): Long =
        splitmix64(splitmix64(root xor fnv1a64(salt)) xor n)

    /** 种子 Random：kotlin.random.Random(seed)。 */
    fun randomOf(seed: Long): Random = Random(seed)
}
