package cn.lotto.zhixi.domain.engine

import cn.lotto.zhixi.domain.algorithm.backtest.WalkForwardBacktester
import cn.lotto.zhixi.domain.algorithm.core.LotteryDataValidator
import cn.lotto.zhixi.domain.algorithm.fusion.ProbabilityCalibrator
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

        testDataValidator(sampleDraws)
        testBlueBallEngineRangeAndProbabilities(sampleDraws)
        testRedBallEngineOutput(sampleDraws)
        testRegressionBlueBallNotFixedTo04(sampleDraws)
        testProbabilityCalibrator()
        testWalkForwardBacktestAndCsvExport(sampleDraws)
        test1000RunBlueBallDiversity(sampleDraws)
    }

    private fun testDataValidator(draws: List<Draw>) {
        val report = LotteryDataValidator.validateSSQDraws(draws)
        check(report.isValid) { "数据校验应当通过" }
        check(report.totalDraws == 100) { "总期数应当为100" }
        check(report.cutoffIssue == "2026100") { "截止期号应当为2026100" }
    }

    private fun testBlueBallEngineRangeAndProbabilities(draws: List<Draw>) {
        val stats = BlueBallEngine.analyzeBlueBalls(draws)
        check(stats.size == 16) { "蓝球诊断数量必须为16" }
        var sumP = 0.0
        stats.forEach { s ->
            check(s.ball in 1..16) { "蓝球必须在 1..16 之间" }
            check(s.finalProbability > 0) { "平滑概率必须大于0" }
            check(s.modelScore > 0) { "模型评分必须大于0" }
            sumP += s.finalProbability
        }
        check(kotlin.math.abs(sumP - 1.0) < 1e-4) { "16个蓝球概率总和必须为 1.0" }
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

    private fun testProbabilityCalibrator() {
        val raw = DoubleArray(17) { it.toDouble() }
        val calibrated = ProbabilityCalibrator.calibrateBlueProbabilities(raw, lambda = 0.25)
        check(calibrated.size == 17)
        var sum = 0.0
        for (i in 1..16) sum += calibrated[i]
        check(kotlin.math.abs(sum - 1.0) < 1e-4) { "校准后概率总和必须为1.0" }
    }

    private fun testWalkForwardBacktestAndCsvExport(draws: List<Draw>) {
        val report = WalkForwardBacktester.runWalkForwardBacktest(draws, "多因子集成", testDrawsCount = 20)
        check(report.drawsTested > 0) { "回测测试期数必须大于0" }
        check(report.details.isNotEmpty()) { "明细不为空" }

        val csv = report.exportToCsv()
        check(csv.startsWith("TargetIssue,CutoffIssue")) { "CSV 表头必须正确" }
        check(csv.contains("2026100")) { "CSV 必须包含最新期号" }
    }

    private fun test1000RunBlueBallDiversity(draws: List<Draw>) {
        val counts = IntArray(17)
        for (i in 1..1000) {
            val tickets = LotteryCombinationEngine.generateSSQTickets(
                draws,
                count = 1,
                years = 1,
                mode = "AI集成",
                blueMode = BlueBallMode.INDEPENDENT,
                seed = i * 10007L
            )
            val b = tickets.first().blue
            counts[b]++
        }
        val nonZeroCount = (1..16).count { counts[it] > 0 }
        check(nonZeroCount >= 12) { "1000次采样中至少有12个不同蓝球被采纳（实际采纳蓝球种类：$nonZeroCount）" }
    }
}
