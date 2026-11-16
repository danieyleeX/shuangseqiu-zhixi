package cn.lotto.zhixi.domain.algorithm.core

data class LotteryAlgorithmConfig(
    val version: String = "2.1.0-Ensemble",
    val defaultLambda: Double = 0.25,
    val maxBlueBalls: Int = 16,
    val maxRedBalls: Int = 33,
    val redCount: Int = 6,
    val blueCount: Int = 1,
    val randomBaselineProb: Double = 1.0 / 16.0
)
