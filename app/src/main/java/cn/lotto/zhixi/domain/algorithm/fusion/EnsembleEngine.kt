package cn.lotto.zhixi.domain.algorithm.fusion

import cn.lotto.zhixi.domain.engine.BlueBallEngine
import cn.lotto.zhixi.domain.engine.BlueBallStat
import cn.lotto.zhixi.domain.model.Draw

object EnsembleEngine {

    data class ModelWeights(
        val wUniform: Double = 0.40,
        val wLongTerm: Double = 0.20,
        val wShortTerm: Double = 0.20,
        val wBayesian: Double = 0.20
    )

    fun calculateEnsembleBlueProbabilities(draws: List<Draw>, weights: ModelWeights = ModelWeights()): DoubleArray {
        val size = 16
        val rawProbs = DoubleArray(size + 1)

        val stats = BlueBallEngine.analyzeBlueBalls(draws, 1, lambda = 0.25)
        val statsLong = BlueBallEngine.analyzeBlueBalls(draws, 5, lambda = 0.25)

        val uniformP = 1.0 / size.toDouble()

        for (b in 1..size) {
            val longP = statsLong.firstOrNull { it.ball == b }?.laplaceProbability ?: uniformP
            val shortP = stats.firstOrNull { it.ball == b }?.laplaceProbability ?: uniformP
            val bayesP = stats.firstOrNull { it.ball == b }?.finalProbability ?: uniformP

            val pEnsemble = weights.wUniform * uniformP +
                    weights.wLongTerm * longP +
                    weights.wShortTerm * shortP +
                    weights.wBayesian * bayesP

            rawProbs[b] = pEnsemble
        }

        return ProbabilityCalibrator.calibrateBlueProbabilities(rawProbs, lambda = 0.25)
    }
}
