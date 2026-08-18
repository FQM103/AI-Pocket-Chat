package com.situ.aichat.ui.pet

/**
 * 宠物粒子特效的性能分级（1:1 iOS `PetVisualPerformance`）：按无障碍减少动效 / 省电模式自动选粒子数 + 帧率。
 */
data class PetVisualPerformance(
    val allowsParticles: Boolean,
    /** 粒子帧间隔（秒），越小越流畅。 */
    val particleFrameInterval: Double,
    val ambientParticleCount: Int,
    val feedParticleCount: Int,
    val cleanParticleCount: Int,
    val playParticleCount: Int,
    val confettiParticleCount: Int,
) {
    companion object {
        fun current(reduceMotion: Boolean, lowPowerMode: Boolean): PetVisualPerformance = when {
            // 无障碍：减少动效 → 完全禁用粒子
            reduceMotion -> PetVisualPerformance(false, 1.0 / 12.0, 0, 0, 0, 0, 0)
            // 省电模式：降帧率 + 减半粒子
            lowPowerMode -> PetVisualPerformance(true, 1.0 / 12.0, 3, 6, 6, 6, 12)
            // 正常：完整效果
            else -> PetVisualPerformance(true, 1.0 / 18.0, 5, 10, 10, 10, 25)
        }
    }
}
