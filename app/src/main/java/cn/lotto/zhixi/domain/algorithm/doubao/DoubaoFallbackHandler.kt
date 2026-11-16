
package cn.lotto.zhixi.domain.algorithm.doubao

import cn.lotto.zhixi.data.remote.DoubaoPickResult
import cn.lotto.zhixi.domain.engine.BlueBallMode
import cn.lotto.zhixi.domain.engine.LotteryCombinationEngine
import cn.lotto.zhixi.domain.model.Draw
import cn.lotto.zhixi.domain.model.LotteryGame
import cn.lotto.zhixi.domain.model.Ticket

object DoubaoFallbackHandler {

    fun createFallbackResult(
        candidates: List<Ticket>,
        draws: List<Draw>,
        count: Int,
        years: Int,
        mode: String,
        game: LotteryGame,
        rawSummary: String
    ): DoubaoPickResult {

        require(count > 0) { "兜底生成注数必须大于0" }

        val validCandidates = candidates
            .filter { it.gameCode == game.code }
            .distinctBy {
                val frontKey = if (game.digits) {
                    it.reds
                } else {
                    it.reds.sorted()
                }

                val backKey = if (game.digits) {
                    it.secondary
                } else {
                    it.secondary.sorted()
                }

                Triple(
                    frontKey,
                    it.blue,
                    backKey
                )
            }

        val fallbackTickets = when {
            validCandidates.isNotEmpty() -> {
                validCandidates
                    .sortedByDescending { it.score }
                    .take(count)
                    .map { it.copy(gameCode = game.code) }
            }

            game.code == "ssq" -> {
                LotteryCombinationEngine.generateSSQTickets(
                    draws = draws,
                    count = count.coerceAtMost(10),
                    years = years,
                    mode = mode,
                    blueMode = BlueBallMode.COVERAGE
                )
            }

            else -> emptyList()
        }

        val summaryText = rawSummary
            .take(120)
            .replace("\n", " ")
            .replace("\r", " ")

        val reason = buildString {
            append("【豆包 AI 未返回足够的有效候选，已启用本地兜底】")

            if (validCandidates.isNotEmpty()) {
                append(" 已按照本地候选评分排序。")
            } else if (game.code == "ssq") {
                append(" 已调用双色球本地组合引擎。")
            } else {
                append(" 当前彩种没有可用的本地候选，未使用其他彩种号码代替。")
            }

            append(" 原始返回：")
            append(summaryText.ifBlank { "模型响应空白" })
        }

        return DoubaoPickResult(
            tickets = fallbackTickets,
            reasoning = reason
        )
    }
}