package cn.lotto.zhixi

import kotlin.math.abs
import kotlin.random.Random

data class Draw(val issue: String, val date: String, val reds: List<Int>, val blue: Int)
data class Ticket(val reds: List<Int>, val blue: Int, val score: Int)
data class BacktestResult(val draws: Int, val redHits: Int, val blueHits: Int, val hit0: Int, val hit1: Int, val hit2: Int, val hit3Plus: Int) {
    val meanHits: Double get() = if (draws == 0) 0.0 else (redHits + blueHits).toDouble() / draws
}

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
        seed: Long = System.nanoTime()
    ): List<Ticket> {
        require(count in 1..10)
        val data = window(draws, years)
        val redFreq = frequency(data); val blueFreq = blueFrequency(data); val miss = misses(data)
        val recent = data.take(30); val recentFreq = frequency(recent)
        val max = redFreq.maxOrNull()?.coerceAtLeast(1) ?: 1
        val targetPool = if (mode == "AI集成") count * 6 else count
        val pool = mutableListOf<Ticket>(); val seen = mutableSetOf<String>(); val rng = Random(seed)
        var attempts = 0
        while(pool.size < targetPool && attempts++ < targetPool * 1200) {
            val reds = weightedSample(rng, redFreq, recentFreq, miss, max, mode).sorted()
            val sum = reds.sum(); val odd = reds.count { it % 2 == 1 }; val big = reds.count { it >= 17 }
            val runs = reds.zipWithNext().count { (a, b) -> b == a + 1 }
            if(sum !in sumRange || odd !in oddRange || big !in bigRange || runs > maxConsecutivePairs) continue
            if (mode == "分区均衡" && listOf(reds.count { it <= 11 }, reds.count { it in 12..22 }, reds.count { it >= 23 }).any { it !in 1..3 }) continue
            val blue = weightedBlue(rng, blueFreq, mode)
            val key = reds.joinToString(",") + "+" + blue
            if(!seen.add(key)) continue
            pool += Ticket(reds, blue, score(data, reds, blue))
        }
        var fallbackAttempts = 0
        while(pool.size < targetPool && fallbackAttempts++ < targetPool * 12000) {
            val reds = (1..33).shuffled(rng).take(6).sorted(); val blue = rng.nextInt(1,17)
            val sum = reds.sum(); val odd = reds.count { it % 2 == 1 }; val big = reds.count { it >= 17 }
            val runs = reds.zipWithNext().count { (a, b) -> b == a + 1 }
            val key = reds.joinToString(",") + "+" + blue
            val valid = sum in sumRange && odd in oddRange && big in bigRange && runs <= maxConsecutivePairs
            if(seen.add(key) && valid && (mode != "分区均衡" || listOf(reds.count { it <= 11 }, reds.count { it in 12..22 }, reds.count { it >= 23 }).all { it in 1..3 })) {
                pool += Ticket(reds, blue, score(data, reds, blue))
            }
        }
        // Extreme or contradictory filters still return valid ticket-shaped output rather than hanging.
        while (pool.size < count) {
            val reds = (1..33).shuffled(rng).take(6).sorted(); val blue = rng.nextInt(1, 17)
            val key = reds.joinToString(",") + "+" + blue
            if (seen.add(key)) pool += Ticket(reds, blue, score(data, reds, blue))
        }
        return pool.sortedByDescending { it.score }.take(count)
    }

    private fun weightedSample(rng: Random, freq: IntArray, recentFreq: IntArray, miss: IntArray, max: Int, mode: String): List<Int> {
        val heatWeight = when(mode) {
            "热号优先" -> .68
            "冷热搭配" -> .42
            "均衡型" -> .30
            "近期趋势" -> .25
            "遗漏反转" -> .14
            "分区均衡" -> .32
            "AI集成" -> .34
            else -> .48
        }
        val recentWeight = when(mode) { "近期趋势" -> .62; "冷热搭配", "AI集成" -> .22; else -> .08 }
        return (1..33).map { n ->
            val heat = (freq[n].toDouble() / max).coerceAtLeast(.02)
            val recent = recentFreq[n].toDouble() / (recentFreq.maxOrNull()?.coerceAtLeast(1) ?: 1)
            val overdue = (miss[n].coerceAtMost(50) / 50.0)
            val balanced = 1.0 - abs(n - 17) / 17.0
            val weight = when(mode) {
                "热号优先" -> heat * heatWeight + recent * recentWeight + .12
                "冷热搭配" -> heat * heatWeight + overdue * .33 + recent * recentWeight + .06
                "均衡型" -> balanced * .23 + heat * heatWeight + .1
                "近期趋势" -> recent * recentWeight + heat * heatWeight + overdue * .08 + .04
                "遗漏反转" -> overdue * .72 + heat * heatWeight + recent * .06 + .04
                "分区均衡" -> heat * heatWeight + overdue * .16 + recent * .08 + .10
                // AI集成为本地启发式集成：频率、近期、遗漏和号码位置多因子加权，
                // 再从较大的候选池中按结构评分择优；不调用在线模型。
                "AI集成" -> heat * .34 + recent * .22 + overdue * .22 + balanced * .13 + .08
                else -> heat * heatWeight + overdue * .22 + balanced * .10 + recent * recentWeight + .06
            }
            n to (-kotlin.math.ln(rng.nextDouble().coerceAtLeast(1e-12)) / weight)
        }.sortedBy { it.second }.take(6).map { it.first }
    }
    private fun weightedBlue(rng: Random, freq: IntArray, mode: String): Int {
        val max = freq.maxOrNull()?.coerceAtLeast(1) ?: 1
        val randomFactor = when (mode) { "热号优先" -> .25; "近期趋势", "AI集成" -> .55; else -> .85 }
        val weighted = (1..16).map { it to (freq[it].toDouble()/max + rng.nextDouble() * randomFactor) }
        return weighted.maxBy { it.second }.first
    }
    fun score(draws: List<Draw>, reds: List<Int>, blue: Int): Int {
        if(draws.isEmpty()) return 50
        val f=frequency(draws); val miss=misses(draws); val max=f.maxOrNull()?.coerceAtLeast(1) ?: 1
        val freqScore=reds.map { f[it].toDouble()/max }.average()*100
        val missScore=reds.map { (miss[it].coerceAtMost(30)/30.0) }.average()*100
        val sumScore=(100-abs(reds.sum()-102)*1.2).coerceIn(0.0,100.0)
        val odd=reds.count { it%2==1 }; val span=reds.last()-reds.first(); val balanceScore=(100-abs(odd-3)*16-abs(span-25)*1.2).coerceIn(0.0,100.0)
        val b=blueFrequency(draws); val blueScore=100.0*b[blue]/(b.maxOrNull()?.coerceAtLeast(1) ?: 1)
        val consecutive=reds.zipWithNext().count { (a,z)->z==a+1 }; val consecutiveScore=when(consecutive){1,2->100.0;0->70.0;else->maxOf(0.0,100.0-consecutive*25)}
        return (freqScore*.26+missScore*.16+sumScore*.19+balanceScore*.17+blueScore*.12+consecutiveScore*.10).toInt().coerceIn(0,100)
    }

    fun backtest(draws: List<Draw>, strategy: String, years: Int): BacktestResult {
        val sorted=window(draws, years).sortedBy { it.issue }
        val first=sorted.size-101; val from=first.coerceAtLeast(12)
        if(sorted.size-from<2) return BacktestResult(0,0,0,0,0,0,0)
        var red=0;var blue=0;var h0=0;var h1=0;var h2=0;var h3=0;var total=0
        for(i in from until sorted.size) {
            val history=sorted.take(i).sortedByDescending { it.issue }; val target=sorted[i]
            val pick=generate(history,1,minOf(years,10),strategy,seed=(target.issue.toLongOrNull() ?: i.toLong())).first()
            val rh=pick.reds.intersect(target.reds.toSet()).size; val bh=if(pick.blue==target.blue)1 else 0
            red+=rh;blue+=bh;when(rh+bh){0->h0++;1->h1++;2->h2++;else->h3++}
            total++
        }
        return BacktestResult(total,red,blue,h0,h1,h2,h3)
    }
}





