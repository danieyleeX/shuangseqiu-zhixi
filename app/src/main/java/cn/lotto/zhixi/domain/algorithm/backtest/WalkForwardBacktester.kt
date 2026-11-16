package cn.lotto.zhixi.domain.algorithm.backtest

import cn.lotto.zhixi.domain.algorithm.fusion.EnsembleEngine
import cn.lotto.zhixi.domain.algorithm.fusion.ProbabilityCalibrator
import cn.lotto.zhixi.domain.engine.BlueBallMode
import cn.lotto.zhixi.domain.engine.LotteryCombinationEngine
import cn.lotto.zhixi.domain.model.Draw
import cn.lotto.zhixi.domain.model.Ticket
import kotlin.math.ln
import kotlin.random.Random

data class DrawBacktestDetail(
    val targetIssue: String,
    val cutoffIssue: String,
    val actualReds: List<Int>,
    val actualBlue: Int,
    val strategyName: String,
    val predReds: List<Int>,
    val predBlue: Int,
    val redHits: Int,
    val blueHit: Int,
    val combinedHits: Int,
    val brierScore: Double,
    val logLoss: Double,
    val blueProbabilities: DoubleArray
) {
    fun toCsvLine(): String {
        val probsStr = (1..16).joinToString(",") { "%.4f".format(blueProbabilities.getOrNull(it) ?: 0.0) }
        return "$targetIssue,$cutoffIssue,\"${actualReds.joinToString("-")}\",$actualBlue,\"$strategyName\",\"${predReds.joinToString("-")}\",$predBlue,$redHits,$blueHit,$combinedHits,${"%.4f".format(brierScore)},${"%.4f".format(logLoss)},$probsStr"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DrawBacktestDetail
        return targetIssue == other.targetIssue && cutoffIssue == other.cutoffIssue &&
                actualReds == other.actualReds && actualBlue == other.actualBlue &&
                strategyName == other.strategyName && predReds == other.predReds &&
                predBlue == other.predBlue && redHits == other.redHits &&
                blueHit == other.blueHit && combinedHits == other.combinedHits &&
                brierScore == other.brierScore && logLoss == other.logLoss &&
                blueProbabilities.contentEquals(other.blueProbabilities)
    }

    override fun hashCode(): Int {
        var result = targetIssue.hashCode()
        result = 31 * result + cutoffIssue.hashCode()
        result = 31 * result + actualReds.hashCode()
        result = 31 * result + actualBlue
        result = 31 * result + strategyName.hashCode()
        result = 31 * result + predReds.hashCode()
        result = 31 * result + predBlue
        result = 31 * result + redHits
        result = 31 * result + blueHit
        result = 31 * result + combinedHits
        result = 31 * result + brierScore.hashCode()
        result = 31 * result + logLoss.hashCode()
        result = 31 * result + blueProbabilities.contentHashCode()
        return result
    }
}

data class DetailedBacktestReport(
    val strategyName: String,
    val drawsTested: Int,
    val totalRedHits: Int,
    val totalBlueHits: Int,
    val meanRedHitsPerDraw: Double,
    val blueHitRate: Double,
    val meanCombinedHitsPerDraw: Double,
    val hitDistributionRed: Map<Int, Int>,
    val hitDistributionCombined: Map<Int, Int>,
    val brierScore: Double,
    val logLossNatural: Double,
    val logLossBits: Double,
    val vsRandomDiffRed: Double,
    val vsRandomDiffBlue: Double,
    val isOutperformingRandom: Boolean,
    val details: List<DrawBacktestDetail>
) {
    fun exportToCsv(): String {
        val sb = StringBuilder()
        sb.append("TargetIssue,CutoffIssue,ActualReds,ActualBlue,Strategy,PredReds,PredBlue,RedHits,BlueHit,CombinedHits,BrierScore,LogLoss,")
        sb.append((1..16).joinToString(",") { "P_Blue%02d".format(it) })
        sb.append("\n")
        details.forEach { sb.append(it.toCsvLine()).append("\n") }
        return sb.toString()
    }
}

object WalkForwardBacktester {

    const val RED_RANDOM_EXPECTED = 36.0 / 33.0 // 1.090909...
    const val BLUE_RANDOM_EXPECTED = 1.0 / 16.0 // 0.0625 (6.25%)
    val UNIFORM_LOG_LOSS_NATURAL = ln(16.0) // 2.7725887...
    val UNIFORM_BRIER_SCORE = 240.0 / 256.0 // 0.9375

    fun runWalkForwardBacktest(
        draws: List<Draw>,
        strategyName: String,
        testDrawsCount: Int = 50,
        lambda: Double = 0.25
    ): DetailedBacktestReport {
        if (draws.size < 20) {
            return DetailedBacktestReport(
                strategyName, 0, 0, 0, 0.0, 0.0, 0.0,
                emptyMap(), emptyMap(), 0.0, 0.0, 0.0, 0.0, 0.0, false, emptyList()
            )
        }

        val sorted = draws.sortedBy { it.issue }
        val startIndex = (sorted.size - testDrawsCount).coerceAtLeast(15)
        val details = mutableListOf<DrawBacktestDetail>()

        var totalRedHits = 0
        var totalBlueHits = 0

        val redDist = mutableMapOf<Int, Int>()
        val combDist = mutableMapOf<Int, Int>()

        var sumBrier = 0.0
        var sumLogLossNat = 0.0

        for (i in startIndex until sorted.size) {
            val historyBefore = sorted.take(i).sortedByDescending { it.issue }
            val actual = sorted[i]
            val cutoffIssue = historyBefore.firstOrNull()?.issue ?: ""

            val predictedBlueProbs = EnsembleEngine.calculateEnsembleBlueProbabilities(historyBefore)
            val calibratedProbs = ProbabilityCalibrator.calibrateBlueProbabilities(predictedBlueProbs, lambda)

            val predictedTicket = when (strategyName) {
                "均匀随机基准" -> {
                    val rng = Random(actual.issue.hashCode().toLong() + i)
                    Ticket((1..33).shuffled(rng).take(6).sorted(), rng.nextInt(1, 17), 50, "ssq")
                }
                "历史频率" -> LotteryCombinationEngine.generateSSQTickets(historyBefore, 1, 3, "热号优先", BlueBallMode.INDEPENDENT, seed = i.toLong()).first()
                "贝叶斯平滑" -> LotteryCombinationEngine.generateSSQTickets(historyBefore, 1, 3, "均衡型", BlueBallMode.INDEPENDENT, seed = i.toLong()).first()
                "近期趋势" -> LotteryCombinationEngine.generateSSQTickets(historyBefore, 1, 1, "近期趋势", BlueBallMode.INDEPENDENT, seed = i.toLong()).first()
                else -> LotteryCombinationEngine.generateSSQTickets(historyBefore, 1, 1, "AI集成", BlueBallMode.INDEPENDENT, seed = i.toLong()).first()
            }

            val rHits = predictedTicket.reds.intersect(actual.reds.toSet()).size
            val bHit = if (predictedTicket.blue == actual.blue) 1 else 0
            val combined = rHits + bHit

            totalRedHits += rHits
            totalBlueHits += bHit

            redDist[rHits] = (redDist[rHits] ?: 0) + 1
            combDist[combined] = (combDist[combined] ?: 0) + 1

            // 1. 16分类 Brier Score: sum((p[i] - y[i])^2)
            var brierSum = 0.0
            for (b in 1..16) {
                val p = calibratedProbs[b]
                val y = if (actual.blue == b) 1.0 else 0.0
                brierSum += (p - y) * (p - y)
            }
            sumBrier += brierSum

            // 2. 自然对数 Log Loss: -ln(max(p_actual, 1e-12))
            val pActual = (calibratedProbs.getOrNull(actual.blue) ?: (1.0 / 16.0)).coerceAtLeast(1e-12)
            val logLossNat = -ln(pActual)
            sumLogLossNat += logLossNat

            details.add(
                DrawBacktestDetail(
                    targetIssue = actual.issue,
                    cutoffIssue = cutoffIssue,
                    actualReds = actual.reds,
                    actualBlue = actual.blue,
                    strategyName = strategyName,
                    predReds = predictedTicket.reds,
                    predBlue = predictedTicket.blue,
                    redHits = rHits,
                    blueHit = bHit,
                    combinedHits = combined,
                    brierScore = brierSum,
                    logLoss = logLossNat,
                    blueProbabilities = calibratedProbs
                )
            )
        }

        val testSize = details.size
        val meanRedHits = if (testSize == 0) 0.0 else totalRedHits.toDouble() / testSize
        val blueHitRate = if (testSize == 0) 0.0 else totalBlueHits.toDouble() / testSize
        val meanCombinedHits = if (testSize == 0) 0.0 else (totalRedHits + totalBlueHits).toDouble() / testSize

        val meanBrier = if (testSize == 0) 0.0 else sumBrier / testSize
        val meanLogLossNat = if (testSize == 0) 0.0 else sumLogLossNat / testSize
        val meanLogLossBits = meanLogLossNat / ln(2.0)

        val vsRandomRed = meanRedHits - RED_RANDOM_EXPECTED
        val vsRandomBlue = blueHitRate - BLUE_RANDOM_EXPECTED
        val isOutperforming = vsRandomRed > 0.02 && vsRandomBlue > 0.005

        return DetailedBacktestReport(
            strategyName = strategyName,
            drawsTested = testSize,
            totalRedHits = totalRedHits,
            totalBlueHits = totalBlueHits,
            meanRedHitsPerDraw = meanRedHits,
            blueHitRate = blueHitRate,
            meanCombinedHitsPerDraw = meanCombinedHits,
            hitDistributionRed = redDist,
            hitDistributionCombined = combDist,
            brierScore = meanBrier,
            logLossNatural = meanLogLossNat,
            logLossBits = meanLogLossBits,
            vsRandomDiffRed = vsRandomRed,
            vsRandomDiffBlue = vsRandomBlue,
            isOutperformingRandom = isOutperforming,
            details = details
        )
    }

    /**
     * 参数敏感性评估 (Lambda 概率混淆网格搜索)
     */
    fun evaluateLambdaSensitivity(draws: List<Draw>, lambdas: List<Double> = listOf(0.0, 0.1, 0.25, 0.5, 1.0)): Map<Double, DetailedBacktestReport> {
        return lambdas.associateWith { l ->
            runWalkForwardBacktest(draws, "Lambda Sensitivity (λ=$l)", testDrawsCount = 50, lambda = l)
        }
    }

    /**
     * 消融实验 (Ablation Study)
     */
    fun runAblationStudy(draws: List<Draw>): Map<String, DetailedBacktestReport> {
        val strategies = listOf(
            "均匀随机基准",
            "历史频率",
            "贝叶斯平滑",
            "近期趋势",
            "多因子集成"
        )
        return strategies.associateWith { strat ->
            runWalkForwardBacktest(draws, strat, testDrawsCount = 50)
        }
    }
}
