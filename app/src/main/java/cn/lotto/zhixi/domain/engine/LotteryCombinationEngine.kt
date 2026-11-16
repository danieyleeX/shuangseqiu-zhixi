
package cn.lotto.zhixi.domain.engine

import cn.lotto.zhixi.domain.model.*
import android.util.Log
import kotlin.math.ln
import kotlin.random.Random

data class DetailedBacktestReport(
    val strategyName: String,
    val drawsTested: Int,
    val totalRedHits: Int,
    val totalBlueHits: Int,
    val meanHitsPerDraw: Double,
    val blueHitRate: Double,
    val atLeastOneBlueHitRate: Double,
    val hitDistribution: Map<String, Int>,
    val brierScore: Double,
    val logLoss: Double,
    val vsRandomDiff: Double
)

object LotteryCombinationEngine {

    /**
     * 生成双色球候选组合。
     *
     * candidatePoolMultiplier 真正参与候选池生成。
     *
     * 蓝球：
     * INDEPENDENT：每组独立均匀随机。
     * COVERAGE：每批随机洗牌，尽量均匀覆盖16个号码。
     *
     * 最终候选排序只依据红球评分。
     * 蓝球历史频率和模型评分不参与候选筛选。
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

        require(count in 1..10) {
            "双色球单次推荐数量必须在1到10之间"
        }

        val safeYears = years.coerceAtLeast(1)
        val safeMultiplier = candidatePoolMultiplier.coerceIn(1, 10000)

        val targetPoolSize = (count.toLong() * safeMultiplier)
            .coerceAtMost(10000L)
            .toInt()

        val data = windowDraws(draws, safeYears)
        val rng = Random(seed)

        // 蓝球统计仅保留给其他分析展示逻辑使用。
        // 本函数的蓝球生成不依赖历史统计。
        val sampledBlues = buildBlueCandidateSequence(
            rng = rng,
            count = targetPoolSize,
            mode = blueMode
        )

        val candidates = mutableListOf<Ticket>()
        val redScores = mutableMapOf<String, Double>()
        val seenKeys = mutableSetOf<String>()

        val maxAttempts = (targetPoolSize * 8)
            .coerceAtMost(80000)

        var attempts = 0

        while (
            candidates.size < targetPoolSize &&
            attempts < maxAttempts
        ) {
            attempts++

            // 只有成功生成并加入候选池后，才推进蓝球序列。
            val blue = sampledBlues[candidates.size]

            val reds = try {
                RedBallEngine.sampleRedSet(
                    rng = rng,
                    draws = data,
                    mode = mode,
                    sumRange = sumRange,
                    oddRange = oddRange,
                    bigRange = bigRange,
                    maxConsecutivePairs = maxConsecutivePairs
                )
            } catch (e: IllegalStateException) {
                Log.w(
                    "LOTTERY_BLUE_DIAG",
                    "红球约束无法生成合法组合，停止候选生成",
                    e
                )
                break
            }

            val key = reds.sorted().joinToString(",") + "+" + blue

            if (!seenKeys.add(key)) {
                continue
            }

            val redScore = RedBallEngine.scoreRedSet(
                data,
                reds
            )

            // 最终评分只使用红球评分。
            // 蓝球不参与排序，避免历史模型影响蓝球选择。
            val ticketScore = redScore
                .toInt()
                .coerceIn(10, 99)

            val ticket = Ticket(
                reds,
                blue,
                ticketScore,
                "ssq",
                listOf(blue)
            )

            candidates.add(ticket)
            redScores[key] = redScore
        }

        // 统计候选池中各蓝球的出现次数。
        val candidateDistribution = (1..16).joinToString(" | ") { ball ->
            "$ball:${candidates.count { it.blue == ball }}"
        }

        Log.d(
            "LOTTERY_BLUE_DIAG",
            "候选生成完成：目标=$targetPoolSize，实际=${candidates.size}，尝试=$attempts"
        )

        Log.d(
            "LOTTERY_BLUE_DIAG",
            "均匀随机蓝球候选池分布：$candidateDistribution"
        )

        if (candidates.isEmpty()) {
            return emptyList()
        }

        // 不按蓝球分组，不为任何蓝球单独保留最佳候选。
        // 所有候选统一按红球评分排序。
        // 分数相同的候选随机打乱，避免固定顺序偏差。
        val selected = candidates
            .shuffled(rng)
            .sortedByDescending { ticket ->
                val key = ticket.reds.sorted().joinToString(",") +
                        "+" + ticket.blue

                redScores[key] ?: Double.NEGATIVE_INFINITY
            }
            .take(count)

        Log.d(
            "LOTTERY_BLUE_DIAG",
            "最终选中蓝球：${selected.joinToString { it.blue.toString() }}"
        )

        return selected
    }

    /**
     * 构建候选池蓝球序列。
     *
     * COVERAGE：
     * 每16个号码构成一批，每批独立随机洗牌。
     * 不使用历史概率。
     *
     * INDEPENDENT：
     * 每个候选独立均匀随机抽取。
     */
    private fun buildBlueCandidateSequence(
        rng: Random,
        count: Int,
        mode: BlueBallMode
    ): List<Int> {

        if (count <= 0) {
            return emptyList()
        }

        if (mode == BlueBallMode.INDEPENDENT) {
            return List(count) {
                rng.nextInt(1, 17)
            }
        }

        val result = mutableListOf<Int>()

        while (result.size < count) {

            val batch = (1..16)
                .shuffled(rng)
                .take(minOf(16, count - result.size))

            result.addAll(batch)
        }

        return result
    }

    /**
     * 为豆包AI构建多样化候选池。
     *
     * SSQ：
     * 蓝球独立均匀随机生成。
     * 评分只使用红球评分。
     *
     * 非SSQ：
     * 使用 LotteryEngine 的原有接口。
     */
    fun buildDiverseCandidatePoolForAI(
        draws: List<Draw>,
        gameDraws: List<GameDraw>,
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

        if (targetSize <= 0) {
            return emptyList()
        }

        if (game.code != "ssq") {
            return LotteryEngine.generateForGameFromGameDraws(
                draws = gameDraws,
                count = targetSize,
                years = years,
                mode = mode,
                game = game,
                sumRange = sumRange,
                oddRange = oddRange,
                bigRange = bigRange,
                maxConsecutivePairs = maxConsecutivePairs,
                candidatePoolMultiplier = 1,
                seed = seed
            )
        }

        val safeTargetSize = targetSize.coerceAtMost(10000)

        val data = windowDraws(
            draws,
            years.coerceAtLeast(1)
        )

        val rng = Random(seed)

        val candidates = mutableListOf<Ticket>()
        val seenKeys = mutableSetOf<String>()

        val redScores = mutableMapOf<String, Double>()

        var attempts = 0

        val maxAttempts = (safeTargetSize * 10)
            .coerceAtMost(100000)

        while (
            candidates.size < safeTargetSize &&
            attempts < maxAttempts
        ) {
            attempts++

            // 每组候选独立均匀随机生成蓝球。
            val blue = rng.nextInt(1, 17)

            val reds = try {
                RedBallEngine.sampleRedSet(
                    rng = rng,
                    draws = data,
                    mode = mode,
                    sumRange = sumRange,
                    oddRange = oddRange,
                    bigRange = bigRange,
                    maxConsecutivePairs = maxConsecutivePairs
                )
            } catch (e: IllegalStateException) {
                Log.w(
                    "LOTTERY_BLUE_DIAG",
                    "AI候选池红球约束无法生成合法组合",
                    e
                )
                break
            }

            val key = reds.sorted().joinToString(",") + "+" + blue

            if (!seenKeys.add(key)) {
                continue
            }

            val redScore = RedBallEngine.scoreRedSet(
                data,
                reds
            )

            // AI候选池评分也不再使用蓝球模型评分。
            val score = redScore
                .toInt()
                .coerceIn(10, 99)

            candidates.add(
                Ticket(
                    reds,
                    blue,
                    score,
                    "ssq",
                    listOf(blue)
                )
            )

            redScores[key] = redScore
        }

        // 不再按蓝球号码轮流排列，也不按蓝球分组。
        // 只按红球评分排序，平分时随机打乱。
        val result = candidates
            .shuffled(rng)
            .sortedByDescending { ticket ->
                val key = ticket.reds.sorted().joinToString(",") +
                        "+" + ticket.blue

                redScores[key] ?: Double.NEGATIVE_INFINITY
            }
            .take(safeTargetSize)

        val distribution = (1..16).joinToString(" | ") { ball ->
            "$ball:${result.count { it.blue == ball }}"
        }

        Log.d(
            "LOTTERY_BLUE_DIAG",
            "AI候选池完成：目标=$safeTargetSize，实际=${result.size}，尝试=$attempts"
        )

        Log.d(
            "LOTTERY_BLUE_DIAG",
            "AI候选池均匀随机蓝球分布：$distribution"
        )

        return result
    }

    /**
     * 首页最优候选号码。
     *
     * SSQ 使用双色球 Draw 历史数据。
     * 非 SSQ 使用对应彩种的 GameDraw 历史数据。
     */
    fun findOptimalMonteCarloCandidate(
        draws: List<Draw>,
        gameDraws: List<GameDraw>,
        game: LotteryGame,
        runs: Int = 1000,
        seed: Long = System.nanoTime()
    ): Ticket {

        val safeRuns = runs.coerceIn(1, 10000)

        if (game.code != "ssq") {
            return LotteryEngine.generateForGameFromGameDraws(
                draws = gameDraws,
                count = 1,
                years = 1,
                mode = "AI集成",
                game = game,
                candidatePoolMultiplier = safeRuns,
                seed = seed
            ).firstOrNull()
                ?: throw IllegalStateException(
                    "Monte Carlo 未能为 ${game.name} 生成有效候选号码，请检查该彩种的历史数据"
                )
        }

        val pool = generateSSQTickets(
            draws = draws,
            count = 1,
            years = 1,
            mode = "AI集成",
            blueMode = BlueBallMode.INDEPENDENT,
            candidatePoolMultiplier = safeRuns,
            seed = seed
        )

        return pool.firstOrNull()
            ?: throw IllegalStateException(
                "Monte Carlo 未能生成有效双色球候选号码，请检查历史数据和红球约束"
            )
    }

    /**
     * 严格 Walk-Forward 滚动历史回测。
     *
     * 每一期预测只能使用该期开奖之前的数据。
     *
     * 当前蓝球生成已经统一为均匀随机，
     * 因此所有策略的蓝球预测概率均为1/16。
     */
    fun runWalkForwardBacktest(
        draws: List<Draw>,
        strategyName: String,
        testDrawsCount: Int = 50
    ): DetailedBacktestReport {

        if (draws.size < 20 || testDrawsCount <= 0) {
            return DetailedBacktestReport(
                strategyName,
                0,
                0,
                0,
                0.0,
                0.0,
                0.0,
                emptyMap(),
                0.0,
                0.0,
                0.0
            )
        }

        // 按期号从旧到新排序。
        val sorted = draws.sortedBy { it.issue }

        val startIndex = (
                sorted.size - testDrawsCount
                ).coerceAtLeast(15)

        val testSize = sorted.size - startIndex

        if (testSize <= 0) {
            return DetailedBacktestReport(
                strategyName,
                0,
                0,
                0,
                0.0,
                0.0,
                0.0,
                emptyMap(),
                0.0,
                0.0,
                0.0
            )
        }

        var totalRedHits = 0
        var totalBlueHits = 0

        val dist = mutableMapOf(
            "0个号" to 0,
            "1个号" to 0,
            "2个号" to 0,
            "3个号" to 0,
            "4个号及以上" to 0
        )

        var sumBrier = 0.0
        var sumLogLoss = 0.0

        for (i in startIndex until sorted.size) {

            // 只取当前开奖之前的历史数据。
            val historyBefore = sorted
                .take(i)
                .sortedByDescending { it.issue }

            val actual = sorted[i]

            if (
                actual.blue !in 1..16 ||
                actual.reds.size != 6 ||
                actual.reds.any { it !in 1..33 } ||
                actual.reds.distinct().size != 6
            ) {
                continue
            }

            val rng = Random(
                actual.issue.hashCode().toLong() + i
            )

            // 蓝球概率固定为均匀分布。
            // 所有策略都使用相同的蓝球概率基准。
            val blueProbabilities = DoubleArray(17)

            for (ball in 1..16) {
                blueProbabilities[ball] = 1.0 / 16.0
            }

            // 生成当前期预测。
            val predicted = if (
                strategyName == "完全均匀随机"
            ) {

                Ticket(
                    (1..33)
                        .shuffled(rng)
                        .take(6)
                        .sorted(),
                    rng.nextInt(1, 17),
                    50,
                    "ssq"
                )

            } else {

                val years = when (strategyName) {
                    "历史频率", "贝叶斯平滑" -> 3
                    "近期趋势" -> 1
                    else -> 1
                }

                val candidate = try {
                    generateSSQTickets(
                        draws = historyBefore,
                        count = 1,
                        years = years,
                        mode = when (strategyName) {
                            "历史频率" -> "热号优先"
                            "贝叶斯平滑" -> "均衡型"
                            "近期趋势" -> "近期趋势"
                            else -> "AI集成"
                        },
                        blueMode = BlueBallMode.INDEPENDENT,
                        candidatePoolMultiplier = 1,
                        seed = i.toLong()
                    ).firstOrNull()
                } catch (e: IllegalStateException) {
                    null
                }

                // 模型无法生成合法候选时跳过该期。
                if (candidate == null) {
                    continue
                }

                candidate
            }

            val rHits = predicted.reds
                .intersect(actual.reds.toSet())
                .size

            val bHit = if (
                predicted.blue == actual.blue
            ) 1 else 0

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

            // 计算16分类Brier Score。
            var brier = 0.0

            for (ball in 1..16) {
                val actualValue = if (
                    ball == actual.blue
                ) {
                    1.0
                } else {
                    0.0
                }

                val difference =
                    blueProbabilities[ball] - actualValue

                brier += difference * difference
            }

            sumBrier += brier

            // 计算实际蓝球的负对数似然。
            val actualProbability = blueProbabilities[
                actual.blue
            ].coerceIn(1e-12, 1.0)

            sumLogLoss += -ln(actualProbability)
        }

        // 只统计实际成功生成预测的有效测试期数。
        val actualTestSize = dist.values.sum()

        val meanHits =
            if (actualTestSize == 0) {
                0.0
            } else {
                (totalRedHits + totalBlueHits).toDouble() /
                        actualTestSize
            }

        val blueRate =
            if (actualTestSize == 0) {
                0.0
            } else {
                totalBlueHits.toDouble() / actualTestSize
            }

        // 双色球理论随机期望：
        // 红球：6 * 6 / 33
        // 蓝球：1 / 16
        val randomBaselineHits =
            (6.0 * 6.0 / 33.0) + (1.0 / 16.0)

        val vsRandomDiff =
            meanHits - randomBaselineHits

        return DetailedBacktestReport(
            strategyName = strategyName,
            drawsTested = actualTestSize,
            totalRedHits = totalRedHits,
            totalBlueHits = totalBlueHits,
            meanHitsPerDraw = meanHits,
            blueHitRate = blueRate,
            atLeastOneBlueHitRate = blueRate,
            hitDistribution = dist,
            brierScore = if (actualTestSize == 0) {
                0.0
            } else {
                sumBrier / actualTestSize
            },
            logLoss = if (actualTestSize == 0) {
                0.0
            } else {
                sumLogLoss / actualTestSize
            },
            vsRandomDiff = vsRandomDiff
        )
    }

    /**
     * 按年份筛选开奖数据。
     * 假设 draws 最新一期在前。
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
            ?: return draws.take(safeYears * 156)

        val cutoff = latestYear - safeYears + 1

        return draws.filter { draw ->
            val year = draw.date
                .take(4)
                .toIntOrNull()

            year != null && year >= cutoff
        }
    }
}