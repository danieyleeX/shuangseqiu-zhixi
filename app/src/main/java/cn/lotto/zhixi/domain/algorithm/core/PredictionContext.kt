package cn.lotto.zhixi.domain.algorithm.core

import cn.lotto.zhixi.domain.model.Ticket

data class PredictionContext(
    val gameCode: String,
    val targetIssue: String,
    val cutoffIssue: String,
    val cutoffDate: String,
    val historicalDrawsCount: Int,
    val algorithmVersion: String = "2.1.0-Ensemble",
    val mode: String,
    val seed: Long = System.nanoTime()
)

data class PredictionResult(
    val context: PredictionContext,
    val tickets: List<Ticket>,
    val blueProbabilities: DoubleArray,
    val reasoning: String,
    val isFallbackTriggered: Boolean = false,
    val fallbackReason: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PredictionResult
        return context == other.context && tickets == other.tickets && blueProbabilities.contentEquals(other.blueProbabilities)
    }

    override fun hashCode(): Int {
        var result = context.hashCode()
        result = 31 * result + tickets.hashCode()
        result = 31 * result + blueProbabilities.contentHashCode()
        return result
    }
}
