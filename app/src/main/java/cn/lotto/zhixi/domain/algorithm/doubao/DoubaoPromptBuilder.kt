
package cn.lotto.zhixi.domain.algorithm.doubao

import cn.lotto.zhixi.domain.model.LotteryGame
import cn.lotto.zhixi.domain.model.Ticket
import org.json.JSONArray
import org.json.JSONObject

object DoubaoPromptBuilder {

    // 豆包最多接收的候选组合数量。
    // Client 和 Validator 后续统一引用这个常量。
    const val MAX_CANDIDATES = 30

    /**
     * 系统提示词。
     *
     * 要求模型只负责候选筛选和统计分析，
     * 不允许自行生成号码或虚构中奖概率。
     */
    fun buildSystemPrompt(): String {
        return """
你是一个严谨的彩票历史数据统计分析与候选组合筛选助手。

你的唯一任务是：
从用户提供的 candidate_pool 候选池中，挑选指定数量的候选组合 ID。

必须遵守以下规则：

1. 只能选择 candidate_pool 中已经存在的 ID。
2. 严禁自行生成、修改、补充或猜测任何彩票号码。
3. 不得修改候选组合的 front 和 back。
4. selected_ids 中的 ID 必须是整数。
5. selected_ids 中不能出现重复 ID。
6. 必须尽可能返回指定数量的不同候选 ID。
7. 优先考虑候选组合的综合评分、号码分布和组合多样性。
8. 不要只因为某个号码历史上出现次数多，就认定它下一期更容易出现。
9. 历史频率、遗漏、冷热、和值和结构评分都只是历史统计指标，不代表真实中奖概率。
10. 不得声称任何组合具有确定中奖优势，也不得编造概率、预测准确率或回测结果。
11. 如果候选池数量不足，只能返回实际存在的不同 ID。
12. reason 应当简洁、客观，说明筛选依据和局限性。
13. ID 是候选组合的索引，不是彩票号码。
14. 必须严格遵循用户提供的候选池数据。

输出必须是严格合法的纯 JSON。

禁止输出 Markdown。
禁止输出代码围栏。
禁止输出 JSON 以外的前言、后语或解释。

唯一允许的返回结构：

{"selected_ids":[0,1,2],"reason":"筛选理由"}

selected_ids 必须是整数数组。
reason 必须是字符串。
""".trimIndent()
    }

    /**
     * 构建用户提示词。
     *
     * 只向 AI 提供最多30组候选组合，
     * 防止提示词过长。
     *
     * count 超过实际候选数量时自动调整。
     */
    fun buildUserPrompt(
        game: LotteryGame,
        candidates: List<Ticket>,
        count: Int,
        cutoffIssue: String,
        strategy: String
    ): String {

        require(candidates.isNotEmpty()) {
            "候选号码池不能为空"
        }

        require(count > 0) {
            "要求精选的注数必须大于0"
        }

        val visibleCandidates = candidates.take(MAX_CANDIDATES)

        val actualCount = minOf(
            count,
            visibleCandidates.size
        )

        /**
         * 彩种规则。
         */
        val gameRules = JSONObject().apply {
            put("game_code", game.code)
            put("game_name", game.name)

            put("front_count", game.frontCount)
            put("front_max", game.frontMax)

            put("back_count", game.backCount)
            put("back_max", game.backMax)

            put("digits_mode", game.digits)

            put(
                "number_rules",
                when (game.code) {

                    "ssq" ->
                        "红球6个，范围1至33，不能重复；蓝球1个，范围1至16"

                    "dlt" ->
                        "前区5个，范围1至35，不能重复；后区2个，范围1至12，不能重复"

                    "fc3d" ->
                        "3个数字，每位范围0至9，允许重复，位置有意义"

                    "pl3" ->
                        "3个数字，每位范围0至9，允许重复，位置有意义"

                    "pl5" ->
                        "5个数字，每位范围0至9，允许重复，位置有意义"

                    else ->
                        "严格遵循提供的彩种规则和候选号码"
                }
            )
        }

        /**
         * 构建候选号码 JSON。
         *
         * ID 从0开始连续编号。
         * ID 必须与原始 candidates 列表索引一致。
         *
         * 后区字段根据彩种规则生成：
         * backCount == 0：空数组
         * backCount == 1：使用 ticket.blue
         * backCount > 1：使用 ticket.secondary
         */
        val candidateJson = JSONArray()

        visibleCandidates.forEachIndexed { index, ticket ->

            val backNumbers = when (game.backCount) {
                0 -> emptyList()
                1 -> listOf(ticket.blue)
                else -> ticket.secondary
            }

            val candidate = JSONObject().apply {
                put("id", index)

                put(
                    "front",
                    JSONArray(ticket.reds)
                )

                put(
                    "back",
                    JSONArray(backNumbers)
                )

                put(
                    "local_score",
                    ticket.score
                )
            }

            candidateJson.put(candidate)
        }

        /**
         * 组装分析上下文。
         */
        val stats = JSONObject().apply {
            put("lottery", game.name)

            put("game_code", game.code)

            put("game_rules", gameRules)

            put("cutoff_issue", cutoffIssue)

            put("strategy", strategy)

            put("requested_count", actualCount)

            put("candidate_count", visibleCandidates.size)

            put("candidate_pool", candidateJson)

            put(
                "score_explanation",
                "local_score 是本地算法综合评分，仅用于候选组合排序，不是中奖概率，也不代表实际中奖优势"
            )
        }

        return """
请根据以下数据，从候选池中精选 $actualCount 组组合。

【当前任务】
彩种：${game.name}
策略：$strategy
历史数据截止期号：$cutoffIssue
要求精选数量：$actualCount
可用候选数量：${visibleCandidates.size}

【ID 使用规则】

1. 候选 ID 从0开始。
2. ID 对应 candidate_pool 中相同索引的候选组合。
3. ID 不是彩票号码。
4. 只能返回 candidate_pool 中实际存在的 ID。

【筛选要求】

1. 只能选择 candidate_pool 中存在的 ID。
2. 必须返回不同的 ID，不能重复。
3. 优先考虑 local_score 较高的候选组合。
4. 在综合评分接近时，优先考虑号码结构和组合分布的多样性。
5. 不要机械地只选择评分最高的连续编号。
6. 不要擅自改变任何候选号码。
7. 不要将历史统计指标解释成下一期开奖概率。
8. 如果不同候选组合之间没有明显差异，应如实说明，不要编造优势。

【输出要求】

只返回严格合法的 JSON：

{"selected_ids":[0,1,2],"reason":"简要说明筛选依据"}

selected_ids 必须包含实际存在的候选 ID。
selected_ids 不允许重复。
reason 使用简体中文，简洁说明筛选依据。

以下是完整数据：

$stats
""".trimIndent()
    }
}