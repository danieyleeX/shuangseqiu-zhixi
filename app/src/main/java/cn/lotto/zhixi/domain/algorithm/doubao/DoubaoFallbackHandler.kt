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
        val fallbackTickets = if (candidates.isNotEmpty()) {
            candidates.shuffled().take(count).map { it.copy(gameCode = game.code) }
        } else {
            LotteryCombinationEngine.generateSSQTickets(draws, count, years, mode, BlueBallMode.COVERAGE)
        }

        val summaryText = rawSummary.take(120).replace("\n", " ")
        val reason = "【模型未返回有效编号，已自动转为本地概率平滑精选】 原始返回：${summaryText.ifBlank { "模型响应空白" }}"
        return DoubaoPickResult(fallbackTickets, reason)
    }
}
