package cn.lotto.zhixi.domain.model

data class Draw(val issue: String, val date: String, val reds: List<Int>, val blue: Int)
data class GameDraw(val gameCode: String, val issue: String, val date: String, val front: List<Int>, val back: List<Int> = emptyList())
data class Ticket(
    val reds: List<Int>,
    val blue: Int,
    val score: Int,
    val gameCode: String = "ssq",
    val secondary: List<Int> = listOf(blue)
)
data class LotteryGame(val code: String, val name: String, val frontCount: Int, val frontMax: Int, val backCount: Int = 0, val backMax: Int = 0, val digits: Boolean = false)
object LotteryGames {
    val all = listOf(
        LotteryGame("ssq", "双色球", 6, 33, 1, 16),
        LotteryGame("dlt", "超级大乐透", 5, 35, 2, 12),
        LotteryGame("fc3d", "福彩3D", 3, 9, digits = true),
        LotteryGame("pl3", "排列三", 3, 9, digits = true),
        LotteryGame("pl5", "排列五", 5, 9, digits = true)
    )
    fun byCode(code: String) = all.firstOrNull { it.code == code } ?: all.first()
}
data class BacktestResult(val draws: Int, val redHits: Int, val blueHits: Int, val hit0: Int, val hit1: Int, val hit2: Int, val hit3Plus: Int) {
    val meanHits: Double get() = if (draws == 0) 0.0 else (redHits + blueHits).toDouble() / draws
}
data class GameBacktestResult(val draws: Int, val frontHits: Int, val backHits: Int, val exactHits: Int, val hitDistribution: Map<Int, Int>) {
    val meanHits: Double get() = if (draws == 0) 0.0 else (frontHits + backHits).toDouble() / draws
}
