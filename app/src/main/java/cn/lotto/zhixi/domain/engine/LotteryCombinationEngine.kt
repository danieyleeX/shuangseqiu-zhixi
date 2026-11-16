package cn.lotto.zhixi.domain.engine

import cn.lotto.zhixi.domain.model.*
import kotlin.math.log2
import kotlin.random.Random

data class MonteCarloOptimizationResult(
    val totalRunsRequested: Int,
    val uniqueCandidatesGenerated: Int,
    val optimalTicket: Ticket,
    val executionTimeMs: Long
)

object LotteryCombinationEngine {

    /**
     * 生成双色球候选组合（基于 PMI 联合似然、软最大值贝叶斯概率及约束重复采样）
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

        val coMatrix = RedBallEngine.buildCoOccurrenceMatrix(data)
        val blueStats = BlueBallEngine.analyzeBlueBalls(data, years, lambda = 0.25)
        val sampledBlues = BlueBallEngine.sampleMultiBlue(rng, blueStats, count, blueMode)

        val resultTickets = mutableListOf<Ticket>()
        val seenKeys = mutableSetOf<String>()

        for (i in 0 until count) {
            val targetBlue = sampledBlues[i]
            var chosenTicket: Ticket? = null

            // 在约束允许范围内反复尝试采样红球组合
            val reds = RedBallEngine.sampleRedSet(
                rng, data, mode, sumRange, oddRange, bigRange, maxConsecutivePairs, maxAttempts = 150
            )

            if (reds != null) {
                val key = reds.joinToString(",") + "+" + targetBlue
                if (seenKeys.add(key)) {
                    val redScoreDetail = RedBallEngine.scoreRedSetDetail(data, reds, coMatrix)
                    val blueStat = blueStats.firstOrNull { it.ball == targetBlue }
                    val blueScore = blueStat?.modelScore ?: 50.0
                    val totalScore = (redScoreDetail.totalRedScore * 0.75 + blueScore * 0.25).toInt().coerceIn(10, 99)
                    chosenTicket = Ticket(reds, targetBlue, totalScore, "ssq", listOf(targetBlue))
                }
            }

            if (chosenTicket == null) {
                // 若受限太严，再次尝试无极端规则限制的合法无重复随机红球
                val fallbackReds = (1..33).shuffled(rng).take(6).sorted()
                val redScoreDetail = RedBallEngine.scoreRedSetDetail(data, fallbackReds, coMatrix)
                val blueStat = blueStats.firstOrNull { it.ball == targetBlue }
                val totalScore = (redScoreDetail.totalRedScore * 0.75 + (blueStat?.modelScore ?: 50.0) * 0.25).toInt().coerceIn(10, 99)
                chosenTicket = Ticket(fallbackReds, targetBlue, totalScore, "ssq", listOf(targetBlue))
                seenKeys.add(fallbackReds.joinToString(",") + "+" + targetBlue)
            }

            resultTickets.add(chosenTicket)
        }

        return resultTickets.sortedByDescending { it.score }
    }

    /**
     * 真正执行 N 次（如 1000 次）蒙特卡洛仿真计算，统计唯一候选并精选出全盘最佳推荐号码
     */
    fun runMonteCarloOptimization(
        draws: List<Draw>,
        gameDraws: List<GameDraw>,
        game: LotteryGame,
        runs: Int = 1000,
        seed: Long = System.nanoTime()
    ): MonteCarloOptimizationResult {
        val t0 = System.currentTimeMillis()
        if (game.code != "ssq") {
            val tickets = LotteryEngine.generateForGame(draws, 1, 1, "AI集成", game, candidatePoolMultiplier = runs, seed = seed)
            val best = tickets.firstOrNull() ?: error("通用彩种蒙特卡洛候选生成失败")
            return MonteCarloOptimizationResult(runs, tickets.size, best, System.currentTimeMillis() - t0)
        }

        val data = windowDraws(draws, 1)
        val rng = Random(seed)
        val coMatrix = RedBallEngine.buildCoOccurrenceMatrix(data)
        val blueStats = BlueBallEngine.analyzeBlueBalls(data, 1, lambda = 0.25)

        val candidateMap = LinkedHashMap<String, Ticket>()
        var attempts = 0
        val maxAttempts = runs * 3

        while (candidateMap.size < runs && attempts++ < maxAttempts) {
            val blue = BlueBallEngine.sampleSingleBlue(rng, blueStats)
            val reds = RedBallEngine.sampleRedSet(rng, data, "AI集成", 21..183, 0..6, 0..6, 5, maxAttempts = 10) ?: continue
            val key = reds.joinToString(",") + "+" + blue
            if (!candidateMap.containsKey(key)) {
                val redDetail = RedBallEngine.scoreRedSetDetail(data, reds, coMatrix)
                val blueScore = blueStats.firstOrNull { it.ball == blue }?.modelScore ?: 50.0
                val totalScore = (redDetail.totalRedScore * 0.75 + blueScore * 0.25).toInt().coerceIn(10, 99)
                candidateMap[key] = Ticket(reds, blue, totalScore, "ssq", listOf(blue))
            }
        }

        require(candidateMap.isNotEmpty()) { "蒙特卡洛 1000 次采样由于规则限制无法生成有效候选" }

        val bestTicket = candidateMap.values.maxByOrNull { it.score }!!
        val elapsed = System.currentTimeMillis() - t0

        return MonteCarloOptimizationResult(
            totalRunsRequested = runs,
            uniqueCandidatesGenerated = candidateMap.size,
            optimalTicket = bestTicket,
            executionTimeMs = elapsed
        )
    }

    /**
     * 为主页“本期推荐号码”获取 1000 次蒙特卡洛计算出的最佳候选
     */
    fun findOptimalMonteCarloCandidate(
        draws: List<Draw>,
        gameDraws: List<GameDraw>,
        game: LotteryGame,
        runs: Int = 1000,
        seed: Long = System.nanoTime()
    ): Ticket {
        return runCatching {
            runMonteCarloOptimization(draws, gameDraws, game, runs, seed).optimalTicket
        }.getOrElse {
            if (game.code == "ssq") {
                generateSSQTickets(draws, 1, 1, "AI集成", seed = seed).first()
            } else {
                LotteryEngine.generateForGame(draws, 1, 1, "AI集成", game, seed = seed).first()
            }
        }
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
        val coMatrix = RedBallEngine.buildCoOccurrenceMatrix(data)
        val blueStats = BlueBallEngine.analyzeBlueBalls(data, years, lambda = 0.25)

        val candidates = mutableListOf<Ticket>()
        val seenKeys = mutableSetOf<String>()

        var blueIndex = 1
        var attempts = 0
        while (candidates.size < targetSize && attempts++ < 2500) {
            val blue = ((blueIndex++) % 16) + 1
            val reds = RedBallEngine.sampleRedSet(rng, data, mode, sumRange, oddRange, bigRange, maxConsecutivePairs, maxAttempts = 10) ?: continue
            val key = reds.joinToString(",") + "+" + blue
            if (seenKeys.add(key)) {
                val redDetail = RedBallEngine.scoreRedSetDetail(data, reds, coMatrix)
                val blueScore = blueStats.firstOrNull { it.ball == blue }?.modelScore ?: 50.0
                val score = (redDetail.totalRedScore * 0.75 + blueScore * 0.25).toInt().coerceIn(10, 99)
                candidates.add(Ticket(reds, blue, score, "ssq", listOf(blue)))
            }
        }

        return candidates.shuffled(rng).take(targetSize)
    }

    private fun windowDraws(draws: List<Draw>, years: Int): List<Draw> {
        if (draws.isEmpty()) return emptyList()
        val latestYear = draws.first().date.take(4).toIntOrNull() ?: return draws.take(years * 156)
        val cutoff = latestYear - years + 1
        return draws.filter { (it.date.take(4).toIntOrNull() ?: 0) >= cutoff }
    }
}
