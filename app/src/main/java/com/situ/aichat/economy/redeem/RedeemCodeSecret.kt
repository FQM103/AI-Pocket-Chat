package com.situ.aichat.economy.redeem

import android.util.Base64

/**
 * 兑换码签名密钥（14.6c·1:1 iOS `RedeemCodeSecret`）。
 *
 * ## ⚠️ 安全工作流（与 iOS 完全一致，且对公开仓库尤为重要）
 *
 * 1. 本文件**进 git 仓库，但默认值为空** → 所有兑换码判 `INVALID_CODE`，等价「功能禁用」。
 *    本 app 经 GitHub 公开分发，**绝不能把真 secret 提交进仓库**（否则任何人都能伪造码刷币）。
 * 2. 本机配置：把下面 [base64] 改成你自己的 secret（与 iOS `tools/setup_secret.command` 生成的**同一个**
 *    base64，这样两端验同一批码），然后执行：
 *    `git update-index --skip-worktree app/src/main/java/com/situ/aichat/economy/redeem/RedeemCodeSecret.kt`
 *    让 git 忽略本地改动 → 真 secret 永不被 push。
 * 3. 生成兑换码：用 iOS 的 `tools/生成兑换码.command`（同一 secret），产出的 `AIC-XXXXX-XXXXX-XXXXX` 两端通用。
 *
 * 安全性：secret = base64 解码后的 32 字节随机数；HMAC-SHA256 取前 40 bit → 单码伪造概率 1/1.1 万亿。
 * 即便二进制被反编译拿到 secret，也只能生成有效码，无法让已过期/已用的码复活、无法伪造档外金额。
 */
object RedeemCodeSecret {
    /** 空字符串 = 未配置（功能禁用）。本机配置时替换为与 iOS 一致的 base64 secret，并 skip-worktree。 */
    private const val base64 = ""

    /** base64 解码后的密钥字节；空/非法 → 空数组（服务层据此把所有码判 INVALID_CODE）。 */
    val value: ByteArray by lazy {
        if (base64.isEmpty()) ByteArray(0)
        else runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrDefault(ByteArray(0))
    }
}
