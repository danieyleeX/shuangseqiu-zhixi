package cn.lotto.zhixi.domain.engine

import cn.lotto.zhixi.domain.model.*
import kotlin.math.abs
import kotlin.random.Random

object LotteryEngine {
    fun frequency(draws: List<Draw>): IntArray = IntArray(34).also { f -> draws.forEach { d -> d.reds.forEach { f[it]++ } } }
    fun blueFrequency(draws: List<Draw>): IntArray = IntArray(17).also { f -> draws.forEach { f[it.blue]++ } }
    fun misses(draws: List<Draw>): IntArray = IntArray(34).also { out ->
        for (n in 1..33) out[n] = draws.indexOfFirst { n in it.reds }.let { if (it < 0) draws.size else it }
    }
    fun consecutivePairs(draw: Draw): Int = draw.reds.zipWithNext().count { (a,b) -> b == a + 1 }
    fun window(draws: List<Draw>, years: Int): List<Draw> {
        if (draws.isEmpty()) return emptyList()
        val latestYear = draws.first().date.take(4).toIntOrNull() ?: return draws.take(years * 156)
        val cutoff = latestYear - years + 1
        return draws.filter { (it.date.take(4).toIntOrNull() ?: 0) >= cutoff }
    }

    fun generate(
        draws: List<Draw>, count: Int, years: Int, mode: String,
        sumRange: IntRange = 21..183,
        oddRange: IntRange = 0..6,
        bigRange: IntRange = 0..6,
        maxConsecutivePairs: Int = 5,
        candidatePoolMultiplier: Int = 6,
        seed: Long = System.nanoTime()
    ): List<Ticket> {
        return LotteryCombinationEngine.generateSSQTickets(
            draws, count, years, mode, BlueBallMode.COVERAGE,
            sumRange, oddRange, bigRange, maxConsecutivePairs, candidatePoolMultiplier, seed
        )
    }

    fun generateForGame(
        draws: List<Draw>, count: Int, years: Int, mode: String, game: LotteryGame,
        sumRange: IntRange = Int.MIN_VALUE..Int.MAX_VALUE, oddRange: IntRange = 0..6, bigRange: IntRange = 0..6,
        maxConsecutivePairs: Int = 5, candidatePoolMultiplier: Int = 6, seed: Long = System.nanoTime()
    ): List<Ticket> {
        if (game.code == "ssq") {
            return generate(draws, count, years, mode, sumRange, oddRange, bigRange, maxConsecutivePairs, candidatePoolMultiplier, seed)
        }
        require(count in 1..10)
        val rng = Random(seed)
        val target = count * if (mode == "AI集成") candidatePoolMultiplier else 1
        val candidates = LinkedHashMap<String, Ticket>()
        val maxAttempts = target * 500
        var attempts = 0
        while (candidates.size < target && attempts++ < maxAttempts) {
            val front = if (game.digits) List(game.frontCount) { rng.nextInt(0, 10) }
                else (1..game.frontMax).shuffled(rng).take(game.frontCount).sorted()
            val secondary = if (game.backCount > 0) (1..game.backMax).shuffled(rng).take(game.backCount).sorted() else emptyList()
            val sum = front.sum()
            val odd = front.count { it % 2 == 1 }
            val bigThreshold = if (game.digits) 5 else if (game.code == "dlt") 18 else 17
            val big = front.count { it >= bigThreshold }
            val runs = front.distinct().sorted().zipWithNext().count { (a, b) -> b == a + 1 }
            if (sum !in sumRange || odd !in oddRange || big !in bigRange || runs > maxConsecutivePairs) continue
            val key = front.joinToString("") + "+" + secondary.joinToString("")
            if (candidates.containsKey(key)) continue
            val score = gameStructureScore(front, secondary, game)
            candidates[key] = Ticket(front, secondary.firstOrNull() ?: 0, score, game.code, secondary)
        }
        return candidates.values.sortedByDescending { it.score }.take(count)
    }

    private fun gameStructureScore(front: List<Int>, secondary: List<Int>, game: LotteryGame): Int {
        val score = if (game.digits) {
            val targetSum = front.size * 4.5
            val duplicates = front.size - front.distinct().size
            val odd = front.count { it % 2 == 1 }
            100.0 - abs(front.sum() - targetSum) * 3.0 - abs(odd - front.size / 2.0) * 8.0 - duplicates * 5.0
        } else {
            val targetSum = game.frontCount * (game.frontMax + 1) / 2.0
            val odd = front.count { it % 2 == 1 }
            val consecutive = front.zipWithNext().count { (a, b) -> b == a + 1 }
            val secondaryBalance = if (secondary.isEmpty()) 0.0 else abs(secondary.sum() - secondary.size * (game.backMax + 1) / 2.0)
            100.0 - abs(front.sum() - targetSum) * 0.7 - abs(odd - game.frontCount / 2.0) * 8.0 - consecutive * 7.0 - secondaryBalance * 0.8
        }
        return score.toInt().coerceIn(0, 100)
    }

    fun score(draws: List<Draw>, reds: List<Int>, blue: Int): Int {
        if(draws.isEmpty()) return 50
        val redScore = RedBallEngine.scoreRedSet(draws, reds)
        val blueStats = BlueBallEngine.analyzeBlueBalls(draws)
        val blueScore = blueStats.firstOrNull { it.ball == blue }?.modelScore ?: 50.0
        return (redScore * 0.75 + blueScore * 0.25).toInt().coerceIn(10, 99)
    }

    fun backtest(draws: List<Draw>, strategy: String, years: Int): BacktestResult {
        val report = LotteryCombinationEngine.runWalkForwardBacktest(draws, strategy, 50)
        val hit0 = report.hitDistribution["0个号"] ?: 0
        val hit1 = report.hitDistribution["1个号"] ?: 0
        val hit2 = report.hitDistribution["2个号"] ?: 0
        val hit3Plus = (report.hitDistribution["3个号"] ?: 0) + (report.hitDistribution["4个号及以上"] ?: 0)
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

    fun backtestGame(draws: List<GameDraw>, game: LotteryGame, strategy: String, years: Int): GameBacktestResult {
        val latestYear = draws.firstOrNull()?.date?.take(4)?.toIntOrNull() ?: return GameBacktestResult(0,0,0,0, emptyMap())
        val sorted = draws.filter { (it.date.take(4).toIntOrNull() ?: 0) >= latestYear - years + 1 }.sortedWith(compareBy<GameDraw> { it.date }.thenBy { it.issue })
        val from = (sorted.size - 101).coerceAtLeast(12)
        if (sorted.size - from < 2) return GameBacktestResult(0,0,0,0, emptyMap())
        var frontTotal = 0; var backTotal = 0; var exact = 0; var total = 0
        val distribution = mutableMapOf<Int,Int>()
        for (i in from until sorted.size) {
            val target = sorted[i]
            val pick = generateForGame(emptyList(), 1, years, strategy, game, candidatePoolMultiplier = 6,
                seed = target.issue.toLongOrNull() ?: (target.date.replace("-", "") + i).toLongOrNull() ?: i.toLong()).firstOrNull() ?: continue
            val frontHit = if (game.digits) pick.reds.zip(target.front).count { (a,b) -> a == b } else pick.reds.intersect(target.front.toSet()).size
            val backHit = if (game.backCount == 0) 0 else pick.secondary.intersect(target.back.toSet()).size
            val isExact = if (game.digits) pick.reds == target.front else frontHit == game.frontCount && backHit == game.backCount
            frontTotal += frontHit; backTotal += backHit; if (isExact) exact++
            distribution[frontHit + backHit] = (distribution[frontHit + backHit] ?: 0) + 1
            total++
        }
        return GameBacktestResult(total, frontTotal, backTotal, exact, distribution)
    }
}
