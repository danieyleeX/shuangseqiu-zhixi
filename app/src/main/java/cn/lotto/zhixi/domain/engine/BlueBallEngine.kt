package cn.lotto.zhixi.domain.engine

import cn.lotto.zhixi.domain.model.Draw
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
    INDEPENDENT,
    COVERAGE
}

object BlueBallEngine {

    private const val TOTAL_BALLS = 16

    /**
     * 分析蓝球历史数据。
     *
     * 约定：draws 最新一期在前。
     *
     * 注意：
     * 历史统计仅用于展示和分析，
     * 不参与下面的均匀随机抽样。
     */
    fun analyzeBlueBalls(
        draws: List<Draw>,
        windowYears: Int = 1,
        lambda: Double = 0.25
    ): List<BlueBallStat> {

        val years = windowYears.coerceAtLeast(1)

        val clampedLambda = lambda
            .takeIf { it.isFinite() }
            ?.coerceIn(0.0, 0.5)
            ?: 0.25

        if (draws.isEmpty()) {
            val uniformP = 1.0 / TOTAL_BALLS

            return (1..TOTAL_BALLS).map { ball ->
                BlueBallStat(
                    ball = ball,
                    totalFreq = 0,
                    freq30 = 0,
                    freq50 = 0,
                    freq100 = 0,
                    freq1Yr = 0,
                    freq3Yr = 0,
                    freq5Yr = 0,
                    currentOmission = 0,
                    maxOmission = 0,
                    meanOmission = 0.0,
                    laplaceProbability = uniformP,
                    finalProbability = uniformP,
                    modelScore = 50.0
                )
            }
        }

        // 统一过滤非法蓝球数据。
        val validDraws = draws.filter {
            it.blue in 1..TOTAL_BALLS
        }

        if (validDraws.isEmpty()) {
            val uniformP = 1.0 / TOTAL_BALLS

            return (1..TOTAL_BALLS).map { ball ->
                BlueBallStat(
                    ball = ball,
                    totalFreq = 0,
                    freq30 = 0,
                    freq50 = 0,
                    freq100 = 0,
                    freq1Yr = 0,
                    freq3Yr = 0,
                    freq5Yr = 0,
                    currentOmission = 0,
                    maxOmission = 0,
                    meanOmission = 0.0,
                    laplaceProbability = uniformP,
                    finalProbability = uniformP,
                    modelScore = 50.0
                )
            }
        }

        val totalN = validDraws.size

        val data30 = validDraws.take(30)
        val data50 = validDraws.take(50)
        val data100 = validDraws.take(100)

        val data1Yr = windowDraws(validDraws, 1)
        val data3Yr = windowDraws(validDraws, 3)
        val data5Yr = windowDraws(validDraws, 5)

        val totalFreqMap = countFrequency(validDraws)
        val f30 = countFrequency(data30)
        val f50 = countFrequency(data50)
        val f100 = countFrequency(data100)
        val f1Yr = countFrequency(data1Yr)
        val f3Yr = countFrequency(data3Yr)
        val f5Yr = countFrequency(data5Yr)

        // 当前遗漏：最新一期在前。
        val currentOmissions = IntArray(TOTAL_BALLS + 1)

        for (ball in 1..TOTAL_BALLS) {
            val index = validDraws.indexOfFirst {
                it.blue == ball
            }

            currentOmissions[ball] =
                if (index < 0) totalN else index
        }

        // 最大遗漏和平均遗漏。
        val maxOmissions = IntArray(TOTAL_BALLS + 1)
        val meanOmissions = DoubleArray(TOTAL_BALLS + 1)

        for (ball in 1..TOTAL_BALLS) {

            var maxGap = 0
            var currentGap = 0
            var occurrenceCount = 0
            var totalGap = 0
            var previousOccurrence = -1

            // 从旧到新计算间隔。
            for (i in validDraws.indices.reversed()) {

                if (validDraws[i].blue == ball) {

                    maxGap = max(maxGap, currentGap)

                    if (previousOccurrence >= 0) {
                        totalGap += currentGap
                    }

                    currentGap = 0
                    occurrenceCount++
                    previousOccurrence = i

                } else {
                    currentGap++
                }
            }

            maxGap = max(maxGap, currentGap)

            maxOmissions[ball] = maxGap

            meanOmissions[ball] =
                if (occurrenceCount <= 1) {
                    totalN.toDouble()
                } else {
                    totalGap.toDouble() /
                            (occurrenceCount - 1)
                }
        }

        // 根据年份选择统计窗口。
        val targetWindow = windowDraws(validDraws, years)

        val windowN = targetWindow.size
        val windowFreq = countFrequency(targetWindow)

        // Laplace平滑概率。
        val laplaceP = DoubleArray(TOTAL_BALLS + 1)

        val denominator = windowN + TOTAL_BALLS.toDouble()

        for (ball in 1..TOTAL_BALLS) {
            laplaceP[ball] =
                (windowFreq[ball] + 1.0) / denominator
        }

        // 混合均匀概率，限制历史数据影响。
        // 该概率只用于统计展示，不参与随机抽样。
        val finalP = DoubleArray(TOTAL_BALLS + 1)

        for (ball in 1..TOTAL_BALLS) {
            finalP[ball] =
                (1.0 - clampedLambda) / TOTAL_BALLS +
                        clampedLambda * laplaceP[ball]
        }

        val maxP = (1..TOTAL_BALLS)
            .maxOf { finalP[it] }
            .coerceAtLeast(1e-12)

        return (1..TOTAL_BALLS).map { ball ->

            val modelScore = (
                    finalP[ball] / maxP * 100.0
                    ).coerceIn(0.0, 100.0)

            BlueBallStat(
                ball = ball,
                totalFreq = totalFreqMap[ball],
                freq30 = f30[ball],
                freq50 = f50[ball],
                freq100 = f100[ball],
                freq1Yr = f1Yr[ball],
                freq3Yr = f3Yr[ball],
                freq5Yr = f5Yr[ball],
                currentOmission = currentOmissions[ball],
                maxOmission = maxOmissions[ball],
                meanOmission = meanOmissions[ball],
                laplaceProbability = laplaceP[ball],
                finalProbability = finalP[ball],
                modelScore = modelScore
            )
        }
    }

    /**
     * 统计蓝球出现次数。
     */
    private fun countFrequency(
        draws: List<Draw>
    ): IntArray {

        val result = IntArray(TOTAL_BALLS + 1)

        draws.forEach { draw ->
            if (draw.blue in 1..TOTAL_BALLS) {
                result[draw.blue]++
            }
        }

        return result
    }

    /**
     * 均匀随机抽取一个蓝球。
     *
     * 1～16每个号码的抽取概率均为1/16。
     *
     * stats参数保留，避免影响现有调用方。
     * 历史统计不参与本次抽样。
     */
    fun sampleSingleBlue(
        rng: Random,
        stats: List<BlueBallStat>
    ): Int {

        return rng.nextInt(1, TOTAL_BALLS + 1)
    }

    /**
     * 多组蓝球抽样。
     *
     * INDEPENDENT：
     * 每次独立均匀随机，允许重复。
     *
     * COVERAGE：
     * 1～16随机打乱后无放回抽样，
     * 保证本批次内蓝球不重复。
     */
    fun sampleMultiBlue(
        rng: Random,
        stats: List<BlueBallStat>,
        count: Int,
        mode: BlueBallMode
    ): List<Int> {

        require(count in 1..TOTAL_BALLS) {
            "蓝球抽取数量必须在1到16之间"
        }

        return when (mode) {

            BlueBallMode.INDEPENDENT -> {
                List(count) {
                    rng.nextInt(1, TOTAL_BALLS + 1)
                }
            }

            BlueBallMode.COVERAGE -> {
                (1..TOTAL_BALLS)
                    .shuffled(rng)
                    .take(count)
            }
        }
    }

    /**
     * 根据年份筛选开奖数据。
     *
     * 假设draws最新一期在前。
     */
    private fun windowDraws(
        draws: List<Draw>,
        years: Int
    ): List<Draw> {

        if (draws.isEmpty()) {
            return emptyList()
        }

        val safeYears = years.coerceAtLeast(1)

        val latestYear = draws.first()
            .date.take(4)
            .toIntOrNull()

        if (latestYear == null) {
            return draws.take(safeYears * 156)
        }

        val cutoff = latestYear - safeYears + 1

        return draws.filter { draw ->
            val year = draw.date
                .take(4)
                .toIntOrNull()

            year != null && year >= cutoff
        }
    }
}