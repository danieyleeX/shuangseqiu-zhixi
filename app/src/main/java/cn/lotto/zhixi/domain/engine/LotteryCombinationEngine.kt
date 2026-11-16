package cn.lotto.zhixi.domain.engine

import cn.lotto.zhixi.domain.model.*
import kotlin.math.log2
import kotlin.math.max
import kotlin.random.Random

data class DetailedBacktestReport(
    val strategyName: String,
    val drawsTested: Int,
    val totalRedHits: Int,
    val totalBlueHits: Int,
    val meanHitsPerDraw: Double,
    val blueHitRate: Double,
    val atLeastOneBlueHitRate: Double,
    val hitDistribution: Map<String, Int>, // e.g. "0个号" -> count
    val brierScore: Double,
    val logLoss: Double,
    val vsRandomDiff: Double
)

object LotteryCombinationEngine {

    /**
     * 生成双色球候选组合（彻底修复蓝球固定 04 偏差）
     */
    fun generateSSQTickets(
        draws: List<Draw>,
        count: Int,
        years: Int,
        mode: String,
        blueMode: BlueBallMode = BlueBallMode.COVERAGE,
        sumRange: IntRange = 21..183,
        oddRange: IntRange = 0..6,
        bigRange: IntRange = 0..6,
        maxConsecutivePairs: Int = 5,
        candidatePoolMultiplier: Int = 6,
        seed: Long = System.nanoTime()
    ): List<Ticket> {
        require(count in 1..10)
        val data = windowDraws(draws, years)
        val rng = Random(seed)

        // 1. 蓝球引擎平滑概率估计
        val blueStats = BlueBallEngine.analyzeBlueBalls(data, years, lambda = 0.25)
        // 采样得出的本批次蓝球列表
        val sampledBlues = BlueBallEngine.sampleMultiBlue(rng, blueStats, count, blueMode)

        val resultTickets = mutableListOf<Ticket>()
        val seenKeys = mutableSetOf<String>()

        for (i in 0 until count) {
            val targetBlue = sampledBlues[i]
            var attempts = 0
            var chosenTicket: Ticket? = null

            while (attempts++ < 800) {
                val reds = RedBallEngine.sampleRedSet(rng, data, mode, sumRange, oddRange, bigRange, maxConsecutivePairs)
                val key = reds.joinToString(",") + "+" + targetBlue
                if (seenKeys.add(key)) {
                    val redScore = RedBallEngine.scoreRedSet(data, reds)
                    val blueStat = blueStats.firstOrNull { it.ball == targetBlue }
                    val blueScore = blueStat?.modelScore ?: 50.0
                    val totalScore = (redScore * 0.75 + blueScore * 0.25).toInt().coerceIn(10, 99)
                    chosenTicket = Ticket(reds, targetBlue, totalScore, "ssq", listOf(targetBlue))
                    break
                }
            }

            if (chosenTicket == null) {
                val reds = (1..33).shuffled(rng).take(6).sorted()
                val redScore = RedBallEngine.scoreRedSet(data, reds)
                val blueStat = blueStats.firstOrNull { it.ball == targetBlue }
                val totalScore = (redScore * 0.75 + (blueStat?.modelScore ?: 50.0) * 0.25).toInt().coerceIn(10, 99)
                chosenTicket = Ticket(reds, targetBlue, totalScore, "ssq", listOf(targetBlue))
            }

            resultTickets.add(chosenTicket)
        }

        return resultTickets.sortedByDescending { it.score }
    }

    /**
     * 为豆包 AI 构建蓝球多样化的候选池 (覆盖 01-16 所有蓝球)
     */
    fun buildDiverseCandidatePoolForAI(
        draws: List<Draw>,
        game: LotteryGame,
        years: Int,
        mode: String,
        sumRange: IntRange = Int.MIN_VALUE..Int.MAX_VALUE,
        oddRange: IntRange = 0..6,
        bigRange: IntRange = 0..6,
        maxConsecutivePairs: Int = 5,
        targetSize: Int = 30,
        seed: Long = System.nanoTime()
    ): List<Ticket> {
        if (game.code != "ssq") {
            return LotteryEngine.generateForGame(
                draws, targetSize, years, mode, game,
                sumRange, oddRange, bigRange, maxConsecutivePairs, candidatePoolMultiplier = 1, seed = seed
            )
        }

        val data = windowDraws(draws, years)
        val rng = Random(seed)
        val blueStats = BlueBallEngine.analyzeBlueBalls(data, years, lambda = 0.25)

        val candidates = mutableListOf<Ticket>()
        val seenKeys = mutableSetOf<String>()

        // 均衡循环每个蓝球 01-16 至少生成 1-2 组红球组合
        var blueIndex = 1
        var attempts = 0
        while (candidates.size < targetSize && attempts++ < 2500) {
            val blue = ((blueIndex++) % 16) + 1
            val reds = RedBallEngine.sampleRedSet(rng, data, mode, sumRange, oddRange, bigRange, maxConsecutivePairs)
            val key = reds.joinToString(",") + "+" + blue
            if (seenKeys.add(key)) {
                val redScore = RedBallEngine.scoreRedSet(data, reds)
                val blueScore = blueStats.firstOrNull { it.ball == blue }?.modelScore ?: 50.0
                val score = (redScore * 0.75 + blueScore * 0.25).toInt().coerceIn(10, 99)
                candidates.add(Ticket(reds, blue, score, "ssq", listOf(blue)))
            }
        }

        // 打乱后部分排序，保留多蓝球覆盖
        return candidates.shuffled(rng).take(targetSize)
    }

    /**
     * 严格 Walk-Forward 滚动历史回测 (无未来数据介入)
     */
    fun runWalkForwardBacktest(
        draws: List<Draw>,
        strategyName: String,
        testDrawsCount: Int = 50
    ): DetailedBacktestReport {
        if (draws.size < 20) {
            return DetailedBacktestReport(strategyName, 0, 0, 0, 0.0, 0.0, 0.0, emptyMap(), 0.0, 0.0, 0.0)
        }

        val sorted = draws.sortedBy { it.issue }
        val startIndex = (sorted.size - testDrawsCount).coerceAtLeast(15)
        val testSize = sorted.size - startIndex

        var totalRedHits = 0
        var totalBlueHits = 0
        val dist = mutableMapOf("0个号" to 0, "1个号" to 0, "2个号" to 0, "3个号" to 0, "4个号及以上" to 0)

        var sumBrier = 0.0
        var sumLogLoss = 0.0

        for (i in startIndex until sorted.size) {
            val historyBefore = sorted.take(i).sortedByDescending { it.issue }
            val actual = sorted[i]

            // 单期向前滚动算法预测
            val predicted = when (strategyName) {
                "完全均匀随机" -> {
                    val rng = Random(actual.issue.hashCode().toLong() + i)
                    Ticket((1..33).shuffled(rng).take(6).sorted(), rng.nextInt(1, 17), 50, "ssq")
                }
                "历史频率" -> generateSSQTickets(historyBefore, 1, 3, "热号优先", BlueBallMode.INDEPENDENT, seed = i.toLong()).first()
                "贝叶斯平滑" -> generateSSQTickets(historyBefore, 1, 3, "均衡型", BlueBallMode.INDEPENDENT, seed = i.toLong()).first()
                "近期趋势" -> generateSSQTickets(historyBefore, 1, 1, "近期趋势", BlueBallMode.INDEPENDENT, seed = i.toLong()).first()
                else -> generateSSQTickets(historyBefore, 1, 1, "AI集成", BlueBallMode.INDEPENDENT, seed = i.toLong()).first()
            }

            val rHits = predicted.reds.intersect(actual.reds.toSet()).size
            val bHit = if (predicted.blue == actual.blue) 1 else 0

            totalRedHits += rHits
            totalBlueHits += bHit

            val totalHits = rHits + bHit
            val key = when (totalHits) {
                0 -> "0个号"
                1 -> "1个号"
                2 -> "2个号"
                3 -> "3个号"
                else -> "4个号及以上"
            }
            dist[key] = (dist[key] ?: 0) + 1

            // 计算 Log Loss 与 Brier Score 评估概率预警误差
            val pBlueActual = if (bHit == 1) 1.0 else 0.0
            val pPredicted = 1.0 / 16.0
            sumBrier += (pPredicted - pBlueActual) * (pPredicted - pBlueActual)
            sumLogLoss += -log2(if (bHit == 1) pPredicted else (1.0 - pPredicted))
        }

        val meanHits = if (testSize == 0) 0.0 else (totalRedHits + totalBlueHits).toDouble() / testSize
        val blueRate = if (testSize == 0) 0.0 else totalBlueHits.toDouble() / testSize
        val randomBaselineHits = (6.0 / 33.0 * 6.0) + (1.0 / 16.0) // 约 1.15 个号
        val vsRandomDiff = meanHits - randomBaselineHits

        return DetailedBacktestReport(
            strategyName = strategyName,
            drawsTested = testSize,
            totalRedHits = totalRedHits,
            totalBlueHits = totalBlueHits,
            meanHitsPerDraw = meanHits,
            blueHitRate = blueRate,
            atLeastOneBlueHitRate = blueRate,
            hitDistribution = dist,
            brierScore = if (testSize == 0) 0.0 else sumBrier / testSize,
            logLoss = if (testSize == 0) 0.0 else sumLogLoss / testSize,
            vsRandomDiff = vsRandomDiff
        )
    }

    private fun windowDraws(draws: List<Draw>, years: Int): List<Draw> {
        if (draws.isEmpty()) return emptyList()
        val latestYear = draws.first().date.take(4).toIntOrNull() ?: return draws.take(years * 156)
        val cutoff = latestYear - years + 1
        return draws.filter { (it.date.take(4).toIntOrNull() ?: 0) >= cutoff }
    }
}
