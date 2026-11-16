package cn.lotto.zhixi.domain.algorithm.core

import cn.lotto.zhixi.domain.model.Draw
import cn.lotto.zhixi.domain.model.GameDraw

data class ValidationReport(
    val isValid: Boolean,
    val totalDraws: Int,
    val cutoffIssue: String,
    val cutoffDate: String,
    val duplicateIssuesCount: Int,
    val invalidRedCount: Int,
    val invalidBlueCount: Int,
    val errors: List<String>
)

object LotteryDataValidator {

    fun validateSSQDraws(draws: List<Draw>): ValidationReport {
        if (draws.isEmpty()) {
            return ValidationReport(false, 0, "", "", 0, 0, 0, listOf("历史开奖数据为空"))
        }

        val errors = mutableListOf<String>()
        val seenIssues = mutableSetOf<String>()
        var duplicateCount = 0
        var invalidRed = 0
        var invalidBlue = 0

        draws.forEach { d ->
            if (!seenIssues.add(d.issue)) {
                duplicateCount++
            }
            if (d.reds.size != 6 || d.reds.distinct().size != 6 || d.reds.any { it !in 1..33 }) {
                invalidRed++
            }
            if (d.blue !in 1..16) {
                invalidBlue++
            }
        }

        if (duplicateCount > 0) errors.add("存在 $duplicateCount 期重复记录")
        if (invalidRed > 0) errors.add("存在 $invalidRed 期红球数据非法")
        if (invalidBlue > 0) errors.add("存在 $invalidBlue 期蓝球数据非法")

        val sorted = draws.sortedByDescending { it.issue }
        val latest = sorted.first()

        return ValidationReport(
            isValid = errors.isEmpty(),
            totalDraws = draws.size,
            cutoffIssue = latest.issue,
            cutoffDate = latest.date,
            duplicateIssuesCount = duplicateCount,
            invalidRedCount = invalidRed,
            invalidBlueCount = invalidBlue,
            errors = errors
        )
    }

    fun validateGameDraws(draws: List<GameDraw>): ValidationReport {
        if (draws.isEmpty()) {
            return ValidationReport(false, 0, "", "", 0, 0, 0, listOf("彩种历史开奖数据为空"))
        }

        val errors = mutableListOf<String>()
        val sorted = draws.sortedByDescending { it.issue }
        val latest = sorted.first()

        return ValidationReport(
            isValid = errors.isEmpty(),
            totalDraws = draws.size,
            cutoffIssue = latest.issue,
            cutoffDate = latest.date,
            duplicateIssuesCount = 0,
            invalidRedCount = 0,
            invalidBlueCount = 0,
            errors = errors
        )
    }
}
