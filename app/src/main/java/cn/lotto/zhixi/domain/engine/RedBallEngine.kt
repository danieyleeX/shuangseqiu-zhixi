package cn.lotto.zhixi.domain.engine

import cn.lotto.zhixi.domain.model.Draw
import cn.lotto.zhixi.domain.model.GameDraw
import cn.lotto.zhixi.domain.model.LotteryGame
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
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
    val PRIMES_33 = setOf(2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31)

    fun frequency(draws: List<Draw>): IntArray = IntArray(34).also { f -> draws.forEach { d -> d.reds.forEach { f[it]++ } } }

    fun misses(draws: List<Draw>): IntArray = IntArray(34) { n ->
        if (n == 0) 0 else draws.indexOfFirst { n in it.reds }.let { if (it < 0) draws.size else it }
    }

    /**
     * 构建红球两两共现频次矩阵
     */
    fun buildCoOccurrenceMatrix(draws: List<Draw>): Array<IntArray> {
        val matrix = Array(34) { IntArray(34) }
        draws.forEach { draw ->
            val reds = draw.reds
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
     * 计算红球6号码组合的正则化联合对数似然得分 (Joint PMI Log-Likelihood)
     */
    fun calculateJointLogLikelihood(
        reds: List<Int>,
        draws: List<Draw>,
        coMatrix: Array<IntArray>,
        alpha: Double = 0.08
    ): Double {
        if (draws.isEmpty() || reds.size != 6) return 0.0
        val N = draws.size.toDouble()
        val freq = frequency(draws)

        var singleLogSum = 0.0
        for (r in reds) {
            val pSingle = (freq[r] + 1.0) / (N + 33.0)
            singleLogSum += ln(pSingle)
        }

        var pmiSum = 0.0
        val numPairs = 15.0
        for (i in reds.indices) {
            for (j in i + 1 until reds.size) {
                val a = reds[i]
                val b = reds[j]
                val pPair = (coMatrix[a][b] + 1.0) / (N + 528.0) // 528 = 33*32/2
                val pA = (freq[a] + 1.0) / (N + 33.0)
                val pB = (freq[b] + 1.0) / (N + 33.0)
                val pmi = ln((pPair + 1e-12) / (pA * pB + 1e-12))
                pmiSum += pmi
            }
        }

        return singleLogSum + (alpha * pmiSum / numPairs)
    }

    fun sampleRedSet(
        rng: Random,
        draws: List<Draw>,
        mode: String,
        sumRange: IntRange,
        oddRange: IntRange,
        bigRange: IntRange,
        maxConsecutivePairs: Int
    ): List<Int> {
        val redFreq = frequency(draws)
        val recentFreq = frequency(draws.take(30))
        val miss = misses(draws)
        val maxF = redFreq.maxOrNull()?.coerceAtLeast(1) ?: 1

        val heatWeight = when (mode) {
            "热号优先" -> .65
            "冷热搭配" -> .40
            "均衡型" -> .30
            "近期趋势" -> .22
            "遗漏反转" -> .12
            "AI集成" -> .32
            else -> .45
        }
        val recentWeight = when (mode) { "近期趋势" -> .60; "冷热搭配", "AI集成" -> .20; else -> .08 }

        val sampled = (1..33).map { n ->
            val heat = (redFreq[n].toDouble() / maxF).coerceAtLeast(.02)
            val recent = recentFreq[n].toDouble() / (recentFreq.maxOrNull()?.coerceAtLeast(1) ?: 1)
            val overdue = (miss[n].coerceAtMost(50) / 50.0)
            val balanced = 1.0 - abs(n - 17) / 17.0
            val weight = when (mode) {
                "热号优先" -> heat * heatWeight + recent * recentWeight + .12
                "冷热搭配" -> heat * heatWeight + overdue * .32 + recent * recentWeight + .06
                "均衡型" -> balanced * .22 + heat * heatWeight + .10
                "近期趋势" -> recent * recentWeight + heat * heatWeight + overdue * .08 + .04
                "遗漏反转" -> overdue * .70 + heat * heatWeight + recent * .06 + .04
                "分区均衡" -> heat * heatWeight + overdue * .16 + recent * .08 + .10
                "AI集成" -> heat * .32 + recent * .22 + overdue * .22 + balanced * .14 + .10
                else -> heat * heatWeight + overdue * .20 + balanced * .10 + recent * recentWeight + .06
            }
            n to (-ln(rng.nextDouble().coerceAtLeast(1e-12)) / weight)
        }.sortedBy { it.second }.take(6).map { it.first }.sorted()

        val sum = sampled.sum()
        val odd = sampled.count { it % 2 == 1 }
        val big = sampled.count { it >= 17 }
        val runs = sampled.zipWithNext().count { (a, b) -> b == a + 1 }
        val valid = sum in sumRange && odd in oddRange && big in bigRange && runs <= maxConsecutivePairs
        if (valid && (mode != "分区均衡" || zoneCheck(sampled))) {
            return sampled
        }

        return (1..33).shuffled(rng).take(6).sorted()
    }

    private fun zoneCheck(reds: List<Int>): Boolean {
        val z1 = reds.count { it in 1..11 }
        val z2 = reds.count { it in 12..22 }
        val z3 = reds.count { it in 23..33 }
        return z1 in 1..3 && z2 in 1..3 && z3 in 1..3
    }

    fun scoreRedSet(draws: List<Draw>, reds: List<Int>): Double {
        if (draws.isEmpty()) return 50.0
        val f = frequency(draws)
        val miss = misses(draws)
        val maxF = f.maxOrNull()?.coerceAtLeast(1) ?: 1

        val freqScore = reds.map { f[it].toDouble() / maxF }.average() * 100.0
        val missScore = reds.map { miss[it].coerceAtMost(30) / 30.0 }.average() * 100.0
        val sumScore = (100.0 - abs(reds.sum() - 102) * 1.2).coerceIn(0.0, 100.0)
        val odd = reds.count { it % 2 == 1 }
        val span = reds.last() - reds.first()
        val balanceScore = (100.0 - abs(odd - 3) * 16.0 - abs(span - 25) * 1.2).coerceIn(0.0, 100.0)
        val consecutive = reds.zipWithNext().count { (a, b) -> b == a + 1 }
        val consecutiveScore = when (consecutive) { 1, 2 -> 100.0; 0 -> 75.0; else -> max(0.0, 100.0 - consecutive * 25.0) }

        return (freqScore * .30 + missScore * .18 + sumScore * .22 + balanceScore * .18 + consecutiveScore * .12)
    }
}
