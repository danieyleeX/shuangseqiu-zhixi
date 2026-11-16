
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

        if (
            count <= 0 ||
            candidates.isEmpty() ||
            parsedIds.isEmpty()
        ) {
            return emptyList()
        }

        // AI只能选择实际展示给它的候选池编号。
        // 候选池最多向AI展示30组，因此不接受30以后的ID。
        val visibleCandidateCount = minOf(
            candidates.size,
            DoubaoPromptBuilder.MAX_CANDIDATES
        )

        val validIds = parsedIds
            .filter { it in 0 until visibleCandidateCount }
            .distinct()

        if (validIds.isEmpty()) {
            return emptyList()
        }

        val selectedTickets = mutableListOf<Ticket>()
        val seenTickets = mutableSetOf<String>()

        for (id in validIds) {

            if (selectedTickets.size >= count) {
                break
            }

            val candidate = candidates[id]

            // 不允许跨彩种候选混入结果。
            if (candidate.gameCode != game.code) {
                continue
            }

            // 检查号码是否符合当前彩种规则。
            if (!isValidTicket(candidate, game)) {
                continue
            }

            // 防止不同ID指向相同号码组合。
            val key = buildTicketKey(candidate, game)

            if (!seenTickets.add(key)) {
                continue
            }

            selectedTickets.add(
                candidate.copy(gameCode = game.code)
            )
        }

        // 只返回AI实际选中的有效候选。
        // 不自行生成号码，也不补足不足的注数。
        return selectedTickets
    }

    /**
     * 检查候选号码是否符合当前彩种的基本规则。
     */
    private fun isValidTicket(
        ticket: Ticket,
        game: LotteryGame
    ): Boolean {

        val front = ticket.reds

        // 前区数量必须正确。
        if (front.size != game.frontCount) {
            return false
        }

        if (game.digits) {
            // 数字型彩票允许重复数字，且顺序有意义。
            if (front.any { it !in 0..game.frontMax }) {
                return false
            }
        } else {
            // 普通号码型彩票必须在范围内且不能重复。
            if (front.any { it !in 1..game.frontMax }) {
                return false
            }

            if (front.distinct().size != game.frontCount) {
                return false
            }
        }

        // 没有后区的彩种，不需要检查后区号码。
        if (game.backCount == 0) {
            return true
        }

        val back = when (game.backCount) {
            1 -> listOf(ticket.blue)
            else -> ticket.secondary
        }

        if (back.size != game.backCount) {
            return false
        }

        if (back.any { it !in 1..game.backMax }) {
            return false
        }

        // 后区号码不能重复。
        if (back.distinct().size != game.backCount) {
            return false
        }

        return true
    }

    /**
     * 构建号码组合唯一标识。
     *
     * 数字型彩票保留顺序；
     * 普通号码型彩票忽略号码排列顺序。
     */
    private fun buildTicketKey(
        ticket: Ticket,
        game: LotteryGame
    ): String {

        val frontKey = if (game.digits) {
            ticket.reds.joinToString(",")
        } else {
            ticket.reds.sorted().joinToString(",")
        }

        val backKey = when {
            game.backCount == 0 -> ""
            game.backCount == 1 -> ticket.blue.toString()
            game.digits -> ticket.secondary.joinToString(",")
            else -> ticket.secondary.sorted().joinToString(",")
        }

        return "$frontKey+$backKey"
    }
}