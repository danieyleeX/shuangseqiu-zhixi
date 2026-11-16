
package cn.lotto.zhixi.domain.engine

import cn.lotto.zhixi.domain.model.Draw
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tanh
import kotlin.random.Random

data class RedBallStat(
    val ball: Int,
    val totalFreq: Int,
    val recent30Freq: Int,
    val currentOmission: Int,
    val isPrime: Boolean,
    val zone: Int,
    val laplaceProbability: Double
)

object RedBallEngine {

    val PRIMES_33 = setOf(
        2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31
    )

    /**
     * 统计红球历史出现频次。
     */
    fun frequency(draws: List<Draw>): IntArray {
        val result = IntArray(34)

        draws.forEach { draw ->
            draw.reds.distinct()
                .filter { it in 1..33 }
                .forEach { result[it]++ }
        }

        return result
    }

    /**
     * 计算当前遗漏。
     *
     * 约定：draws 最新一期在前。
     */
    fun misses(draws: List<Draw>): IntArray {
        val result = IntArray(34)

        for (n in 1..33) {
            val index = draws.indexOfFirst { n in it.reds }

            result[n] = if (index < 0) {
                draws.size
            } else {
                index
            }
        }

        return result
    }

    /**
     * 构建红球两两共现频次矩阵。
     */
    fun buildCoOccurrenceMatrix(
        draws: List<Draw>
    ): Array<IntArray> {

        val matrix = Array(34) { IntArray(34) }

        draws.forEach { draw ->

            val reds = draw.reds
                .filter { it in 1..33 }
                .distinct()
                .sorted()

            for (i in reds.indices) {
                for (j in i + 1 until reds.size) {

                    val a = reds[i]
                    val b = reds[j]

                    matrix[a][b]++
                    matrix[b][a]++
                }
            }
        }

        return matrix
    }

    /**
     * 计算六个红球的正则化联合对数似然评分。
     *
     * 使用历史单球频率和两两共现关系。
     *
     * 注意：
     * 这是历史统计评分，不代表真实中奖概率。
     */
    fun calculateJointLogLikelihood(
        reds: List<Int>,
        draws: List<Draw>,
        coMatrix: Array<IntArray>,
        alpha: Double = 0.08
    ): Double {

        if (
            draws.isEmpty() ||
            !isValidRedSet(reds) ||
            coMatrix.size < 34
        ) {
            return 0.0
        }

        val n = draws.size.toDouble()
        val freq = frequency(draws)

        var singleLogSum = 0.0

        for (red in reds) {
            val pSingle =
                (freq[red] + 0.5) / (n + 1.0)

            singleLogSum += ln(pSingle.coerceAtLeast(1e-12))
        }

        val averagePmi = calculateAveragePmi(
            reds = reds,
            drawsCount = n,
            freq = freq,
            coMatrix = coMatrix
        )

        return singleLogSum + alpha * averagePmi
    }

    /**
     * 计算六个红球内部的平均 PMI。
     *
     * 双色球每期开奖固定选出6个红球，
     * 因此单球独立基准需要考虑固定抽取数量。
     */
    private fun calculateAveragePmi(
        reds: List<Int>,
        drawsCount: Double,
        freq: IntArray,
        coMatrix: Array<IntArray>
    ): Double {

        if (drawsCount <= 0.0 || !isValidRedSet(reds)) {
            return 0.0
        }

        val priorStrength = 5.0
        val pairCorrection = 5.0 / 6.0

        var pmiSum = 0.0
        var pairCount = 0

        for (i in reds.indices) {
            for (j in i + 1 until reds.size) {

                val a = reds[i]
                val b = reds[j]

                if (
                    a !in 1..33 ||
                    b !in 1..33 ||
                    a >= coMatrix.size ||
                    b >= coMatrix[a].size
                ) {
                    continue
                }

                val pA = (freq[a] + 0.5) /
                        (drawsCount + 1.0)

                val pB = (freq[b] + 0.5) /
                        (drawsCount + 1.0)

                val expectedPair = (
                        pA * pB * pairCorrection
                        ).coerceAtLeast(1e-12)

                val observedCount = coMatrix[a][b].toDouble()

                // 使用独立基准作为先验，避免小样本下
                // 偶然共现造成过大的 PMI。
                val smoothedPair = (
                        observedCount +
                                expectedPair * priorStrength
                        ) / (drawsCount + priorStrength)

                val pmi = ln(
                    smoothedPair.coerceAtLeast(1e-12) /
                            expectedPair
                )

                pmiSum += pmi
                pairCount++
            }
        }

        return if (pairCount == 0) {
            0.0
        } else {
            pmiSum / pairCount
        }
    }

    /**
     * 加权无放回采样红球。
     *
     * 只有满足约束的号码组合才会返回。
     * 如果尝试次数耗尽，不再返回违反约束的随机组合。
     */
    fun sampleRedSet(
        rng: Random,
        draws: List<Draw>,
        mode: String,
        sumRange: IntRange,
        oddRange: IntRange,
        bigRange: IntRange,
        maxConsecutivePairs: Int
    ): List<Int> {

        require(!sumRange.isEmpty()) {
            "和值范围不能为空"
        }

        require(!oddRange.isEmpty()) {
            "奇数范围不能为空"
        }

        require(!bigRange.isEmpty()) {
            "大号范围不能为空"
        }

        require(maxConsecutivePairs in 0..5) {
            "最大连号数必须在0到5之间"
        }

        require(oddRange.first >= 0 && oddRange.last <= 6) {
            "奇数范围必须在0到6之间"
        }

        require(bigRange.first >= 0 && bigRange.last <= 6) {
            "大号范围必须在0到6之间"
        }

        val redFreq = frequency(draws)
        val recentFreq = frequency(draws.take(30))
        val miss = misses(draws)

        val maxF = redFreq.maxOrNull()?.coerceAtLeast(1) ?: 1
        val maxRecent = recentFreq.maxOrNull()?.coerceAtLeast(1) ?: 1

        val heatWeight = when (mode) {
            "热号优先" -> 0.65
            "冷热搭配" -> 0.40
            "均衡型" -> 0.30
            "近期趋势" -> 0.22
            "遗漏反转" -> 0.12
            "AI集成" -> 0.32
            else -> 0.45
        }

        val recentWeight = when (mode) {
            "近期趋势" -> 0.60
            "冷热搭配", "AI集成" -> 0.20
            else -> 0.08
        }

        val weights = (1..33).associateWith { n ->

            val heat = (
                    redFreq[n].toDouble() / maxF
                    ).coerceAtLeast(0.02)

            val recent = (
                    recentFreq[n].toDouble() / maxRecent
                    ).coerceAtLeast(0.02)

            val overdue = (
                    miss[n].coerceAtMost(50) / 50.0
                    )

            val balanced = 1.0 - abs(n - 17) / 17.0

            val weight = when (mode) {

                "热号优先" ->
                    heat * heatWeight +
                            recent * recentWeight + 0.12

                "冷热搭配" ->
                    heat * heatWeight +
                            overdue * 0.32 +
                            recent * recentWeight + 0.06

                "均衡型" ->
                    balanced * 0.22 +
                            heat * heatWeight + 0.10

                "近期趋势" ->
                    recent * recentWeight +
                            heat * heatWeight +
                            overdue * 0.08 + 0.04

                "遗漏反转" ->
                    overdue * 0.70 +
                            heat * heatWeight +
                            recent * 0.06 + 0.04

                "分区均衡" ->
                    heat * heatWeight +
                            overdue * 0.16 +
                            recent * 0.08 + 0.10

                "AI集成" ->
                    heat * 0.32 +
                            recent * 0.22 +
                            overdue * 0.22 +
                            balanced * 0.14 + 0.10

                else ->
                    heat * heatWeight +
                            overdue * 0.20 +
                            balanced * 0.10 +
                            recent * recentWeight + 0.06
            }

            weight.coerceAtLeast(0.001)
        }

        // 多次采样，直到得到符合全部约束的组合。
        repeat(3000) {

            val sampled = weights.map { (number, weight) ->

                val randomValue = rng.nextDouble()
                    .coerceAtLeast(1e-12)

                val key = -ln(randomValue) / weight

                number to key

            }.sortedBy { it.second }
                .take(6)
                .map { it.first }
                .sorted()

            if (
                isValidCombination(
                    reds = sampled,
                    sumRange = sumRange,
                    oddRange = oddRange,
                    bigRange = bigRange,
                    maxConsecutivePairs = maxConsecutivePairs,
                    requireZoneBalance = mode == "分区均衡"
                )
            ) {
                return sampled
            }
        }

        throw IllegalStateException(
            "红球采样失败：当前约束下未找到有效组合，请检查和值、奇偶、大小及连号范围。"
        )
    }

    /**
     * 检查红球组合是否满足全部约束。
     */
    private fun isValidCombination(
        reds: List<Int>,
        sumRange: IntRange,
        oddRange: IntRange,
        bigRange: IntRange,
        maxConsecutivePairs: Int,
        requireZoneBalance: Boolean
    ): Boolean {

        if (!isValidRedSet(reds)) {
            return false
        }

        val sum = reds.sum()

        val odd = reds.count { it % 2 == 1 }

        val big = reds.count { it >= 17 }

        val consecutivePairs = reds.zipWithNext()
            .count { (a, b) -> b == a + 1 }

        if (sum !in sumRange) return false
        if (odd !in oddRange) return false
        if (big !in bigRange) return false

        if (consecutivePairs > maxConsecutivePairs) {
            return false
        }

        if (requireZoneBalance && !zoneCheck(reds)) {
            return false
        }

        return true
    }

    /**
     * 检查红球是否合法。
     */
    private fun isValidRedSet(reds: List<Int>): Boolean {

        return reds.size == 6 &&
                reds.all { it in 1..33 } &&
                reds.distinct().size == 6
    }

    /**
     * 分区均衡检查。
     *
     * 01-11、12-22、23-33。
     */
    private fun zoneCheck(reds: List<Int>): Boolean {

        val z1 = reds.count { it in 1..11 }
        val z2 = reds.count { it in 12..22 }
        val z3 = reds.count { it in 23..33 }

        return z1 in 1..3 &&
                z2 in 1..3 &&
                z3 in 1..3
    }

    /**
     * 红球综合结构评分。
     *
     * 评分范围：0-100。
     * 不是中奖概率，也不是命中率预测。
     */
    fun scoreRedSet(
        draws: List<Draw>,
        reds: List<Int>
    ): Double {

        if (!isValidRedSet(reds)) {
            return 0.0
        }

        if (draws.isEmpty()) {
            return 50.0
        }

        val sortedReds = reds.sorted()

        val freq = frequency(draws)
        val recentFreq = frequency(draws.take(30))
        val miss = misses(draws)

        val maxF = freq.maxOrNull()?.coerceAtLeast(1) ?: 1
        val maxRecent = recentFreq.maxOrNull()?.coerceAtLeast(1) ?: 1

        // 历史频率评分。
        val freqScore = sortedReds.map {
            freq[it].toDouble() / maxF
        }.average() * 100.0

        // 近期频率评分。
        val recentScore = sortedReds.map {
            recentFreq[it].toDouble() / maxRecent
        }.average() * 100.0

        // 遗漏评分仅作为较低权重的结构指标。
        val missScore = sortedReds.map {
            miss[it].coerceAtMost(30) / 30.0
        }.average() * 100.0

        // 和值结构评分，102是六个红球的理论平均和值。
        val sumScore = (
                100.0 - abs(sortedReds.sum() - 102) * 1.2
                ).coerceIn(0.0, 100.0)

        // 奇偶与跨度结构评分。
        val odd = sortedReds.count { it % 2 == 1 }
        val span = sortedReds.last() - sortedReds.first()

        val balanceScore = (
                100.0 -
                        abs(odd - 3) * 16.0 -
                        abs(span - 25) * 1.2
                ).coerceIn(0.0, 100.0)

        // 连号结构评分。
        val consecutive = sortedReds.zipWithNext()
            .count { (a, b) -> b == a + 1 }

        val consecutiveScore = when (consecutive) {
            0 -> 75.0
            1, 2 -> 100.0
            else -> max(0.0, 100.0 - consecutive * 25.0)
        }

        // PMI两两共现评分，真正接入综合评分。
        val coMatrix = buildCoOccurrenceMatrix(draws)

        val averagePmi = calculateAveragePmi(
            reds = sortedReds,
            drawsCount = draws.size.toDouble(),
            freq = freq,
            coMatrix = coMatrix
        )

        // tanh将PMI映射到稳定的0-100分区间。
        val pmiScore = (
                50.0 + tanh(averagePmi) * 50.0
                ).coerceIn(0.0, 100.0)

        return (
                freqScore * 0.20 +
                        recentScore * 0.10 +
                        missScore * 0.05 +
                        sumScore * 0.25 +
                        balanceScore * 0.20 +
                        consecutiveScore * 0.10 +
                        pmiScore * 0.10
                ).coerceIn(0.0, 100.0)
    }
}