package com.situ.aichat.tts.pricing

/**
 * TTS monthly-cost estimator (1:1 iOS `TTSCostEstimator`). Projects the last-7-days character count
 * to a month (×30/7), then converts with the model's pay-as-you-go unit price.
 *
 * MiniMax official PAYG prices (verified 2026-04):
 * - HD series (speech-*-hd):       $100 / 1M characters
 * - Turbo series (speech-*-turbo): $60  / 1M characters
 * - speech-01 series:              not published → null
 * These are reference prices; the UI footer notes the real bill may differ.
 */
data class TtsCostEstimate(
    val actualCharactersLast7Days: Int,
    val projectedMonthlyCharacters: Int,
    /** Projected monthly cost in USD; null when the unit price is unknown. */
    val projectedMonthlyUSD: Double?,
    /** Reference unit price (USD per 1M chars); null when the model's price isn't published. */
    val unitPriceUSDPerMillion: Double?,
    val daysWithData: Int,
    val modelID: String,
)

object TtsCostEstimator {

    /**
     * Reference PAYG unit price (USD per 1M chars). IMPORTANT: the `speech-01` check is FIRST and
     * returns null — `speech-01-turbo`/`speech-01-hd` are NOT $60/$100, they are unpublished.
     */
    fun unitPriceUSDPerMillion(modelID: String): Double? {
        val lower = modelID.lowercase()
        if (lower.contains("speech-01")) return null
        if (lower.contains("turbo")) return 60.0
        if (lower.contains("hd")) return 100.0
        return null
    }

    fun estimate(snapshot: TtsUsageSnapshot, modelID: String): TtsCostEstimate {
        val unitPrice = unitPriceUSDPerMillion(modelID)
        // 7-day rolling window → monthly projection: x * 30 / 7 (truncated toward zero, like iOS Int()).
        val projectedMonthly = (snapshot.charactersLast7Days * 30.0 / 7.0).toInt()
        val projectedUSD = unitPrice?.let { projectedMonthly / 1_000_000.0 * it }
        return TtsCostEstimate(
            actualCharactersLast7Days = snapshot.charactersLast7Days,
            projectedMonthlyCharacters = projectedMonthly,
            projectedMonthlyUSD = projectedUSD,
            unitPriceUSDPerMillion = unitPrice,
            daysWithData = snapshot.daysWithData,
            modelID = modelID,
        )
    }
}
