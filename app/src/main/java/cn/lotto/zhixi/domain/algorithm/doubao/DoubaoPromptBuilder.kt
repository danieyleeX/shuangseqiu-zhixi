package cn.lotto.zhixi.domain.algorithm.doubao

import cn.lotto.zhixi.domain.model.LotteryGame
import cn.lotto.zhixi.domain.model.Ticket
import org.json.JSONArray
import org.json.JSONObject

object DoubaoPromptBuilder {

    fun buildSystemPrompt(): String {
        return "你是一个专业的统计数据采样助手。你的任务是从提供的 candidate_pool 候选池中挑选出综合指标最佳的编号 ID。必须严格输出符合格式的纯 JSON：{\"selected_ids\":[0,1,2],\"reason\":\"说明\"}。绝对禁止 Markdown 标记（如 ```json），绝对禁止前言、后语或 Markdown 解释。"
    }

    fun buildUserPrompt(
        game: LotteryGame,
        candidates: List<Ticket>,
        count: Int,
        cutoffIssue: String,
        strategy: String
    ): String {
        val candidateJson = JSONArray().apply {
            candidates.take(30).forEachIndexed { index, ticket ->
                put(JSONObject().apply {
                    put("id", index)
                    put("front", JSONArray(ticket.reds))
                    put("back", JSONArray(ticket.secondary))
                    put("local_score", ticket.score)
                })
            }
        }

        val stats = JSONObject().apply {
            put("lottery", game.name)
            put("cutoff_issue", cutoffIssue)
            put("strategy", strategy)
            put("candidate_pool", candidateJson)
        }

        return "请从给定的 candidate_pool 候选池中，精选 $count 组代表性编号 id，优先选择高分且分布均衡的组合。只能从 candidate_pool 中选择已有的 id，不能修改号码。严格只返回纯 JSON：{\"selected_ids\":[id0,id1...],\"reason\":\"精选分析说明\"}。数据：$stats"
    }
}
