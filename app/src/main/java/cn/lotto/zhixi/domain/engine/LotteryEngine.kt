
package cn.lotto.zhixi.domain.engine

import cn.lotto.zhixi.domain.model.*
import kotlin.math.abs
import kotlin.random.Random

object LotteryEngine {

    fun frequency(draws: List<Draw>): IntArray {
        val result = IntArray(34)
        draws.forEach { draw ->
            draw.reds.filter { it in 1..33 }.forEach {
                result[it]++
            }
        }
        return result
    }

    fun blueFrequency(draws: List<Draw>): IntArray {
        val result = IntArray(17)
        draws.forEach { draw ->
            if (draw.blue in 1..16) result[draw.blue]++
        }
        return result
    }

    fun misses(draws: List<Draw>): IntArray {
        val result = IntArray(34)

        for (number in 1..33) {
            result[number] = draws.indexOfFirst {
                number in it.reds
            }.let {
                if (it < 0) draws.size else it
            }
        }

        return result
    }

    fun consecutivePairs(draw: Draw): Int {
        return draw.reds.distinct().sorted()
            .zipWithNext()
            .count { (a, b) -> b == a + 1 }
    }

    /**
     * draws 默认按最新一期在前。
     */
    fun window(draws: List<Draw>, years: Int): List<Draw> {
        if (draws.isEmpty()) return emptyList()
        require(years >= 1) { "years 必须大于等于 1" }

        val latestYear = draws.mapNotNull {
            it.date.take(4).toIntOrNull()
        }.maxOrNull()

        if (latestYear == null) {
            return draws.take(years * 156)
        }

        val cutoff = latestYear - years + 1

        return draws.filter {
            val year = it.date.take(4).toIntOrNull()
            year != null && year >= cutoff
        }
    }

    fun generate(
        draws: List<Draw>,
        count: Int,
        years: Int,
        mode: String,
        sumRange: IntRange = 21..183,
        oddRange: IntRange = 0..6,
        bigRange: IntRange = 0..6,
        maxConsecutivePairs: Int = 5,
        candidatePoolMultiplier: Int = 6,
        seed: Long = System.nanoTime()
    ): List<Ticket> {
        return LotteryCombinationEngine.generateSSQTickets(
            draws,
            count,
            years,
            mode,
            BlueBallMode.COVERAGE,
            sumRange,
            oddRange,
            bigRange,
            maxConsecutivePairs,
            candidatePoolMultiplier,
            seed
        )
    }

    /**
     * 双色球专用生成入口。
     *
     * 非双色球不能使用 List<Draw> 生成，
     * 必须传入对应彩种的真实 GameDraw 历史。
     */
    fun generateForGame(
        draws: List<Draw>,
        count: Int,
        years: Int,
        mode: String,
        game: LotteryGame,
        sumRange: IntRange = Int.MIN_VALUE..Int.MAX_VALUE,
        oddRange: IntRange = 0..6,
        bigRange: IntRange = 0..6,
        maxConsecutivePairs: Int = 5,
        candidatePoolMultiplier: Int = 6,
        seed: Long = System.nanoTime()
    ): List<Ticket> {
        require(game.code == "ssq") {
            "非双色球彩种必须使用 generateForGameFromGameDraws()"
        }

        return generate(
            draws = draws,
            count = count,
            years = years,
            mode = mode,
            sumRange = sumRange,
            oddRange = oddRange,
            bigRange = bigRange,
            maxConsecutivePairs = maxConsecutivePairs,
            candidatePoolMultiplier = candidatePoolMultiplier,
            seed = seed
        )
    }

    /**
     * 非双色球专用生成入口。
     *
     * 使用目标彩种自己的 GameDraw 历史数据。
     */
    fun generateForGameFromGameDraws(
        draws: List<GameDraw>,
        count: Int,
        years: Int,
        mode: String,
        game: LotteryGame,
        sumRange: IntRange = Int.MIN_VALUE..Int.MAX_VALUE,
        oddRange: IntRange = 0..6,
        bigRange: IntRange = 0..6,
        maxConsecutivePairs: Int = 5,
        candidatePoolMultiplier: Int = 6,
        seed: Long = System.nanoTime()
    ): List<Ticket> {
        require(game.code != "ssq") {
            "双色球必须使用 generateForGame()"
        }

        return generateForGameFromHistory(
            history = draws,
            count = count,
            years = years,
            mode = mode,
            game = game,
            sumRange = sumRange,
            oddRange = oddRange,
            bigRange = bigRange,
            maxConsecutivePairs = maxConsecutivePairs,
            candidatePoolMultiplier = candidatePoolMultiplier,
            seed = seed
        )
    }

    /**
     * 非双色球彩种的内部生成方法。
     * history 只允许包含目标开奖之前的历史数据。
     */
    fun generateForGameFromHistory(
        history: List<GameDraw>,
        count: Int,
        years: Int,
        mode: String,
        game: LotteryGame,
        sumRange: IntRange,
        oddRange: IntRange,
        bigRange: IntRange,
        maxConsecutivePairs: Int,
        candidatePoolMultiplier: Int,
        seed: Long
    ): List<Ticket> {
        require(count in 1..10) { "count 必须在 1..10 之间" }
        require(years >= 1) { "years 必须大于等于 1" }
        require(game.frontCount > 0) { "frontCount 必须大于 0" }
        require(game.frontMax >= 0) { "frontMax 不能小于 0" }
        require(game.backCount >= 0) { "backCount 不能小于 0" }
        require(game.backMax >= 0) { "backMax 不能小于 0" }

        if (!game.digits) {
            require(game.frontCount <= game.frontMax) {
                "前区号码数量不能大于号码范围"
            }
        }

        if (game.backCount > 0) {
            require(game.backCount <= game.backMax) {
                "后区号码数量不能大于号码范围"
            }
        }

        val multiplier = if (mode == "AI集成") {
            candidatePoolMultiplier.coerceIn(1, 1000)
        } else {
            1
        }

        val targetLong = count.toLong() * multiplier
        val target = targetLong.coerceAtMost(10000L).toInt()

        val validHistory = windowGameDraws(history, years, game.code)

        val frontFrequency = IntArray(game.frontMax + 1)
        val backFrequency = IntArray(game.backMax + 1)

        validHistory.forEach { draw ->
            draw.front.forEach { number ->
                if (number in 0..game.frontMax) {
                    frontFrequency[number]++
                }
            }

            draw.back.forEach { number ->
                if (number in 1..game.backMax) {
                    backFrequency[number]++
                }
            }
        }

        // 数字型彩种按位置统计历史频率。
        val positionFrequency = if (game.digits) {
            Array(game.frontCount) { IntArray(10) }.also { frequencies ->
                validHistory.forEach { draw ->
                    draw.front.take(game.frontCount)
                        .forEachIndexed { index, number ->
                            if (index < frequencies.size && number in 0..9) {
                                frequencies[index][number]++
                            }
                        }
                }
            }
        } else {
            emptyArray()
        }

        val rng = Random(seed)
        val candidates = LinkedHashMap<String, Ticket>()

        val maxAttempts = (target.toLong() * 500L)
            .coerceAtMost(500000L)
            .toInt()

        var attempts = 0

        while (candidates.size < target && attempts < maxAttempts) {
            attempts++

            val front = if (game.digits) {
                List(game.frontCount) { index ->
                    weightedDigit(positionFrequency[index], rng)
                }
            } else {
                weightedUniqueNumbers(
                    game.frontMax,
                    game.frontCount,
                    frontFrequency,
                    rng
                )
            }

            val secondary = if (game.backCount > 0) {
                weightedUniqueNumbers(
                    game.backMax,
                    game.backCount,
                    backFrequency,
                    rng
                )
            } else {
                emptyList()
            }

            if (front.size != game.frontCount) continue
            if (secondary.size != game.backCount) continue

            val sum = front.sum()
            val odd = front.count { it % 2 == 1 }

            val bigThreshold = when {
                game.digits -> 5
                game.code == "dlt" -> 18
                else -> (game.frontMax + 1) / 2
            }

            val big = front.count { it >= bigThreshold }

            val runs = if (game.digits) {
                0
            } else {
                front.sorted().zipWithNext()
                    .count { (a, b) -> b == a + 1 }
            }

            if (
                sum !in sumRange ||
                odd !in oddRange ||
                big !in bigRange ||
                runs > maxConsecutivePairs
            ) {
                continue
            }

            val key = front.joinToString("") + "+" +
                    secondary.joinToString("")

            if (candidates.containsKey(key)) continue

            val structureScore = gameStructureScore(
                front,
                secondary,
                game
            )

            val historyScore = calculateHistoryScore(
                front,
                secondary,
                game,
                frontFrequency,
                backFrequency,
                positionFrequency
            )

            // 历史频率只作为候选排序参考，不代表真实中奖概率。
            val finalScore = (structureScore * 0.7 + historyScore * 0.3)
                .toInt()
                .coerceIn(0, 100)

            candidates[key] = Ticket(
                reds = front,
                blue = secondary.firstOrNull() ?: 0,
                score = finalScore,
                gameCode = game.code,
                secondary = secondary
            )
        }

        return candidates.values
            .sortedByDescending { it.score }
            .take(count)
    }

    private fun windowGameDraws(
        draws: List<GameDraw>,
        years: Int,
        gameCode: String
    ): List<GameDraw> {
        val matching = draws.filter {
            it.gameCode == gameCode
        }

        if (matching.isEmpty()) return emptyList()

        val latestYear = matching.mapNotNull {
            it.date.take(4).toIntOrNull()
        }.maxOrNull() ?: return matching.take(years * 156)

        val cutoff = latestYear - years + 1

        return matching.filter {
            val year = it.date.take(4).toIntOrNull()
            year != null && year >= cutoff
        }
    }

    private fun weightedDigit(
        frequencies: IntArray,
        rng: Random
    ): Int {
        val total = frequencies.sum() + frequencies.size
        var value = rng.nextInt(total)

        for (number in frequencies.indices) {
            value -= frequencies[number] + 1
            if (value < 0) return number
        }

        return rng.nextInt(10)
    }

    private fun weightedUniqueNumbers(
        maxNumber: Int,
        count: Int,
        frequencies: IntArray,
        rng: Random
    ): List<Int> {
        if (count <= 0) return emptyList()
        if (maxNumber < count) return emptyList()

        val remaining = (1..maxNumber).toMutableList()
        val result = mutableListOf<Int>()

        repeat(count) {
            val totalWeight = remaining.sumOf {
                (frequencies.getOrElse(it) { 0 } + 1).toLong()
            }

            if (totalWeight <= 0L) return@repeat

            var selectedWeight = rng.nextLong(totalWeight)
            var selected = remaining.last()

            for (number in remaining) {
                selectedWeight -= frequencies.getOrElse(number) { 0 } + 1L

                if (selectedWeight < 0L) {
                    selected = number
                    break
                }
            }

            result.add(selected)
            remaining.remove(selected)
        }

        return result.sorted()
    }

    private fun calculateHistoryScore(
        front: List<Int>,
        secondary: List<Int>,
        game: LotteryGame,
        frontFrequency: IntArray,
        backFrequency: IntArray,
        positionFrequency: Array<IntArray>
    ): Double {
        val frontScore = if (game.digits) {
            if (front.isEmpty()) {
                50.0
            } else {
                front.mapIndexed { index, number ->
                    val frequencies = positionFrequency.getOrNull(index)

                    if (frequencies == null) {
                        50.0
                    } else {
                        val maxFrequency = frequencies.maxOrNull() ?: 0

                        if (maxFrequency == 0) 50.0
                        else frequencies.getOrElse(number) { 0 }.toDouble() /
                                maxFrequency * 100.0
                    }
                }.average()
            }
        } else {
            averageFrequencyScore(front, frontFrequency)
        }

        val backScore = if (secondary.isEmpty()) {
            50.0
        } else {
            averageFrequencyScore(secondary, backFrequency)
        }

        return frontScore * 0.75 + backScore * 0.25
    }

    private fun averageFrequencyScore(
        numbers: List<Int>,
        frequencies: IntArray
    ): Double {
        if (numbers.isEmpty()) return 50.0

        val maxFrequency = frequencies.maxOrNull() ?: 0
        if (maxFrequency <= 0) return 50.0

        return numbers.map { number ->
            frequencies.getOrElse(number) { 0 }.toDouble() /
                    maxFrequency * 100.0
        }.average()
    }

    private fun gameStructureScore(
        front: List<Int>,
        secondary: List<Int>,
        game: LotteryGame
    ): Int {
        val score = if (game.digits) {
            val targetSum = front.size * 4.5
            val duplicates = front.size - front.distinct().size
            val odd = front.count { it % 2 == 1 }

            100.0 -
                    abs(front.sum() - targetSum) * 3.0 -
                    abs(odd - front.size / 2.0) * 8.0 -
                    duplicates * 5.0
        } else {
            val targetSum = game.frontCount * (game.frontMax + 1) / 2.0
            val odd = front.count { it % 2 == 1 }

            val consecutive = front.sorted()
                .zipWithNext()
                .count { (a, b) -> b == a + 1 }

            val secondaryBalance = if (secondary.isEmpty()) {
                0.0
            } else {
                abs(
                    secondary.sum() -
                            secondary.size * (game.backMax + 1) / 2.0
                )
            }

            100.0 -
                    abs(front.sum() - targetSum) * 0.7 -
                    abs(odd - game.frontCount / 2.0) * 8.0 -
                    consecutive * 7.0 -
                    secondaryBalance * 0.8
        }

        return score.toInt().coerceIn(0, 100)
    }

    fun score(draws: List<Draw>, reds: List<Int>, blue: Int): Int {
        if (draws.isEmpty()) return 50

        val redScore = RedBallEngine.scoreRedSet(draws, reds)
        val blueStats = BlueBallEngine.analyzeBlueBalls(draws)
        val blueScore = blueStats.firstOrNull {
            it.ball == blue
        }?.modelScore ?: 50.0

        return (redScore * 0.75 + blueScore * 0.25)
            .toInt()
            .coerceIn(10, 99)
    }

    fun backtest(
        draws: List<Draw>,
        strategy: String,
        years: Int
    ): BacktestResult {
        require(years >= 1) { "years 必须大于等于 1" }

        val selectedDraws = window(draws, years)

        if (selectedDraws.size < 2) {
            return BacktestResult(0, 0, 0, 0, 0, 0, 0)
        }

        val report = LotteryCombinationEngine.runWalkForwardBacktest(
            selectedDraws,
            strategy,
            50
        )

        val hit0 = report.hitDistribution["0个号"] ?: 0
        val hit1 = report.hitDistribution["1个号"] ?: 0
        val hit2 = report.hitDistribution["2个号"] ?: 0
        val hit3Plus =
            (report.hitDistribution["3个号"] ?: 0) +
                    (report.hitDistribution["4个号及以上"] ?: 0)

        return BacktestResult(
            report.drawsTested,
            report.totalRedHits,
            report.totalBlueHits,
            hit0,
            hit1,
            hit2,
            hit3Plus
        )
    }

    fun backtestGame(
        draws: List<GameDraw>,
        game: LotteryGame,
        strategy: String,
        years: Int
    ): GameBacktestResult {
        require(years >= 1) { "years 必须大于等于 1" }

        val matching = draws.filter {
            it.gameCode == game.code
        }

        if (matching.isEmpty()) {
            return GameBacktestResult(0, 0, 0, 0, emptyMap())
        }

        val latestYear = matching.mapNotNull {
            it.date.take(4).toIntOrNull()
        }.maxOrNull() ?: return GameBacktestResult(
            0, 0, 0, 0, emptyMap()
        )

        val cutoff = latestYear - years + 1

        val sorted = matching.filter {
            val year = it.date.take(4).toIntOrNull()
            year != null && year >= cutoff
        }.sortedWith(
            compareBy<GameDraw> { it.date }.thenBy { it.issue }
        )

        if (sorted.size <= 12) {
            return GameBacktestResult(0, 0, 0, 0, emptyMap())
        }

        // 最多回测最近 101 期，同时至少保留 12 期历史数据。
        val from = (sorted.size - 101).coerceAtLeast(12)

        var frontTotal = 0
        var backTotal = 0
        var exact = 0
        var total = 0

        val distribution = mutableMapOf<Int, Int>()

        for (i in from until sorted.size) {
            val target = sorted[i]

            // 只使用目标开奖之前的数据，杜绝未来数据泄漏。
            val history = sorted.subList(0, i)

            val seed = target.issue.toLongOrNull()
                ?: target.date.replace("-", "").toLongOrNull()
                ?: i.toLong()

            val pick = generateForGameFromHistory(
                history = history,
                count = 1,
                years = years,
                mode = strategy,
                game = game,
                sumRange = Int.MIN_VALUE..Int.MAX_VALUE,
                oddRange = 0..game.frontCount,
                bigRange = 0..game.frontCount,
                maxConsecutivePairs = game.frontCount,
                candidatePoolMultiplier = 6,
                seed = seed
            ).firstOrNull() ?: continue

            val frontHit = if (game.digits) {
                pick.reds.zip(target.front)
                    .count { (a, b) -> a == b }
            } else {
                pick.reds.intersect(target.front.toSet()).size
            }

            val backHit = if (game.backCount == 0) {
                0
            } else {
                pick.secondary.intersect(target.back.toSet()).size
            }

            val isExact = if (game.digits) {
                pick.reds == target.front
            } else {
                frontHit == game.frontCount &&
                        backHit == game.backCount
            }

            frontTotal += frontHit
            backTotal += backHit

            if (isExact) exact++

            val totalHits = frontHit + backHit
            distribution[totalHits] =
                (distribution[totalHits] ?: 0) + 1

            total++
        }

        return GameBacktestResult(
            total,
            frontTotal,
            backTotal,
            exact,
            distribution
        )
    }
}