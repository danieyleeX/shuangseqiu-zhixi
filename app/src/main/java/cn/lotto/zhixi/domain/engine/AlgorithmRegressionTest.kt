package cn.lotto.zhixi.domain.engine

import cn.lotto.zhixi.domain.model.Draw
import kotlin.random.Random

class AlgorithmRegressionTest {

    fun runAllRegressionTests() {
        val sampleDraws = mutableListOf<Draw>()
        for (i in 1..100) {
            val rng = Random(i.toLong())
            val reds = (1..33).shuffled(rng).take(6).sorted()
            val blue = if (i % 5 == 0) 4 else (i % 16) + 1
            sampleDraws.add(Draw("2026%03d".format(i), "2026-01-01", reds, blue))
        }

        testBlueBallEngineRangeAndProbabilities(sampleDraws)
        testRedBallEngineOutput(sampleDraws)
        testRegressionBlueBallNotFixedTo04(sampleDraws)
        testEmptyHistoryDoesNotDefaultTo04()
    }

    private fun testBlueBallEngineRangeAndProbabilities(draws: List<Draw>) {
        val stats = BlueBallEngine.analyzeBlueBalls(draws)
        check(stats.size == 16) { "蓝球诊断数量必须为16" }
        stats.forEach { s ->
            check(s.ball in 1..16) { "蓝球必须在 1..16 之间" }
            check(s.finalProbability > 0) { "平滑概率必须大于0" }
            check(s.modelScore > 0) { "模型评分必须大于0" }
        }
    }

    private fun testRedBallEngineOutput(draws: List<Draw>) {
        val rng = Random(42)
        val reds = RedBallEngine.sampleRedSet(rng, draws, "AI集成", 21..183, 0..6, 0..6, 5)
        check(reds.size == 6) { "红球必须恰好 6 个" }
        check(reds.distinct().size == 6) { "红球不能重复" }
        check(reds == reds.sorted()) { "红球必须升序" }
        check(reds.all { it in 1..33 }) { "红球必须在 1..33 之间" }
    }

    private fun testRegressionBlueBallNotFixedTo04(draws: List<Draw>) {
        val blueSet = mutableSetOf<Int>()
        for (i in 1..50) {
            val tickets = LotteryCombinationEngine.generateSSQTickets(
                draws,
                count = 3,
                years = 1,
                mode = "AI集成",
                blueMode = BlueBallMode.COVERAGE,
                seed = System.currentTimeMillis() + i * 997L
            )
            tickets.forEach { ticket ->
                check(ticket.blue in 1..16) { "蓝球必须在 1..16 之间" }
                blueSet.add(ticket.blue)
            }
        }

        check(blueSet.size >= 4) { "50次生成中必须出现多种不同蓝球（不能死锁04，实际出现个数：${blueSet.size}）" }
        check(!(blueSet.size == 1 && blueSet.contains(4))) { "蓝球集合不能死锁只有04" }
    }

    private fun testEmptyHistoryDoesNotDefaultTo04() {
        val emptyDraws = emptyList<Draw>()
        val tickets = LotteryCombinationEngine.generateSSQTickets(
            emptyDraws,
            count = 5,
            years = 1,
            mode = "AI集成",
            blueMode = BlueBallMode.COVERAGE,
            seed = 12345L
        )
        val blues = tickets.map { it.blue }
        check(tickets.size == 5) { "生成的Ticket组数必须为5" }
        check(blues.distinct().size > 1 || blues.first() != 4) { "空数据时蓝球不能固定全为 04" }
    }
}
