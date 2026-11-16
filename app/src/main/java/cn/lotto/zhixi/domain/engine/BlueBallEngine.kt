package cn.lotto.zhixi.domain.engine

import cn.lotto.zhixi.domain.model.Draw
import cn.lotto.zhixi.domain.model.GameDraw
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

data class BlueBallStat(
    val ball: Int,
    val totalFreq: Int,
    val freq30: Int,
    val freq50: Int,
    val freq100: Int,
    val freq1Yr: Int,
    val freq3Yr: Int,
    val freq5Yr: Int,
    val currentOmission: Int,
    val maxOmission: Int,
    val meanOmission: Double,
    val laplaceProbability: Double,
    val finalProbability: Double,
    val modelScore: Double
)

enum class BlueBallMode {
    INDEPENDENT, // 模式A：独立概率采样模式
    COVERAGE     // 模式B：多样性覆盖模式
}

object BlueBallEngine {
    private const val TOTAL_BALLS = 16

    /**
     * 对全量历史开奖数据分析 01-16 蓝球各项特征
     */
    fun analyzeBlueBalls(draws: List<Draw>, windowYears: Int = 1, lambda: Double = 0.25): List<BlueBallStat> {
        if (draws.isEmpty()) {
            val uniformP = 1.0 / TOTAL_BALLS
            return (1..TOTAL_BALLS).map { b ->
                BlueBallStat(b, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, uniformP, uniformP, 50.0)
            }
        }

        val totalN = draws.size
        val data30 = draws.take(30)
        val data50 = draws.take(50)
        val data100 = draws.take(100)
        val data1Yr = windowDraws(draws, 1)
        val data3Yr = windowDraws(draws, 3)
        val data5Yr = windowDraws(draws, 5)

        val totalFreqMap = IntArray(TOTAL_BALLS + 1)
        draws.forEach { totalFreqMap[it.blue]++ }

        val f30 = IntArray(TOTAL_BALLS + 1).also { f -> data30.forEach { f[it.blue]++ } }
        val f50 = IntArray(TOTAL_BALLS + 1).also { f -> data50.forEach { f[it.blue]++ } }
        val f100 = IntArray(TOTAL_BALLS + 1).also { f -> data100.forEach { f[it.blue]++ } }
        val f1Yr = IntArray(TOTAL_BALLS + 1).also { f -> data1Yr.forEach { f[it.blue]++ } }
        val f3Yr = IntArray(TOTAL_BALLS + 1).also { f -> data3Yr.forEach { f[it.blue]++ } }
        val f5Yr = IntArray(TOTAL_BALLS + 1).also { f -> data5Yr.forEach { f[it.blue]++ } }

        // 计算当前遗漏、最大遗漏、平均遗漏
        val currentOmissions = IntArray(TOTAL_BALLS + 1) { b ->
            draws.indexOfFirst { it.blue == b }.let { if (it < 0) totalN else it }
        }

        val maxOmissions = IntArray(TOTAL_BALLS + 1)
        val meanOmissions = DoubleArray(TOTAL_BALLS + 1)
        for (b in 1..TOTAL_BALLS) {
            var maxOm = 0
            var currentRun = 0
            var count = 0
            // 按从旧到新遍历计算间隔
            for (d in draws.reversed()) {
                if (d.blue == b) {
                    maxOm = max(maxOm, currentRun)
                    currentRun = 0
                    count++
                } else {
                    currentRun++
                }
            }
            maxOm = max(maxOm, currentRun)
            maxOmissions[b] = maxOm
            meanOmissions[b] = if (count == 0) totalN.toDouble() else totalN.toDouble() / count
        }

        // 挑选分析窗口 N
        val targetWindow = windowDraws(draws, windowYears)
        val N = targetWindow.size.coerceAtLeast(1)
        val windowFreq = IntArray(TOTAL_BALLS + 1).also { f -> targetWindow.forEach { f[it.blue]++ } }

        // 1. 贝叶斯/拉普拉斯平滑估计：P_laplace(i) = (count(i) + 1) / (N + 16)
        val laplaceP = DoubleArray(TOTAL_BALLS + 1)
        for (b in 1..TOTAL_BALLS) {
            laplaceP[b] = (windowFreq[b] + 1.0) / (N + TOTAL_BALLS.toDouble())
        }

        // 2. 概率分布校准（混合均匀分布）：P_final(i) = (1 - lambda) / 16 + lambda * P_model(i)
        val finalP = DoubleArray(TOTAL_BALLS + 1)
        val clampedLambda = lambda.coerceIn(0.0, 0.5)
        for (b in 1..TOTAL_BALLS) {
            finalP[b] = (1.0 - clampedLambda) / TOTAL_BALLS.toDouble() + clampedLambda * laplaceP[b]
        }

        val maxP = finalP.maxOrNull() ?: (1.0 / TOTAL_BALLS)
        return (1..TOTAL_BALLS).map { b ->
            val modelScore = (finalP[b] / maxP * 100.0).coerceIn(10.0, 100.0)
            BlueBallStat(
                ball = b,
                totalFreq = totalFreqMap[b],
                freq30 = f30[b],
                freq50 = f50[b],
                freq100 = f100[b],
                freq1Yr = f1Yr[b],
                freq3Yr = f3Yr[b],
                freq5Yr = f5Yr[b],
                currentOmission = currentOmissions[b],
                maxOmission = maxOmissions[b],
                meanOmission = meanOmissions[b],
                laplaceProbability = laplaceP[b],
                finalProbability = finalP[b],
                modelScore = modelScore
            )
        }
    }

    /**
     * 轮盘赌/概率加权采样单只蓝球
     */
    fun sampleSingleBlue(rng: Random, stats: List<BlueBallStat>): Int {
        if (stats.isEmpty()) return rng.nextInt(1, TOTAL_BALLS + 1)
        val totalWeight = stats.sumOf { it.finalProbability }
        var target = rng.nextDouble() * totalWeight
        for (s in stats) {
            target -= s.finalProbability
            if (target <= 0) return s.ball
        }
        return stats.last().ball
    }

    /**
     * 多组蓝球选择：模式 A (独立推荐) 或 模式 B (覆盖模式)
     */
    fun sampleMultiBlue(rng: Random, stats: List<BlueBallStat>, count: Int, mode: BlueBallMode): List<Int> {
        require(count in 1..10)
        if (mode == BlueBallMode.INDEPENDENT) {
            return List(count) { sampleSingleBlue(rng, stats) }
        }

        // 模式 B：蓝球覆盖模式 (尽力覆盖不同蓝球，同时保留概率权重)
        val pool = stats.sortedByDescending { it.finalProbability }.map { it.ball }.toMutableList()
        pool.shuffle(rng)
        val result = mutableListOf<Int>()
        for (i in 0 until count) {
            val next = if (i < pool.size) pool[i] else sampleSingleBlue(rng, stats)
            result.add(next)
        }
        return result
    }

    private fun windowDraws(draws: List<Draw>, years: Int): List<Draw> {
        if (draws.isEmpty()) return emptyList()
        val latestYear = draws.first().date.take(4).toIntOrNull() ?: return draws.take(years * 156)
        val cutoff = latestYear - years + 1
        return draws.filter { (it.date.take(4).toIntOrNull() ?: 0) >= cutoff }
    }
}
