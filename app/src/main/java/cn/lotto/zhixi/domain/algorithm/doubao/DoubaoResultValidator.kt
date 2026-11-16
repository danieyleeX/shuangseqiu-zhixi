package cn.lotto.zhixi.domain.algorithm.doubao

import cn.lotto.zhixi.domain.model.LotteryGame
import cn.lotto.zhixi.domain.model.Ticket

object DoubaoResultValidator {

    fun validateAndMapTickets(
        parsedIds: List<Int>,
        candidates: List<Ticket>,
        count: Int,
        game: LotteryGame
    ): List<Ticket> {
        val validIds = parsedIds.filter { it in candidates.indices }.distinct().take(count)
        return validIds.map { candidates[it].copy(gameCode = game.code) }
    }
}
