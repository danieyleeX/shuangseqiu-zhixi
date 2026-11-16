package cn.lotto.zhixi.domain.algorithm.doubao

import org.json.JSONObject

data class ParsedDoubaoResponse(
    val selectedIds: List<Int>,
    val reasonText: String,
    val rawJsonText: String
)

object DoubaoResponseParser {

    fun parseResponse(rawContent: String): ParsedDoubaoResponse {
        val cleanContent = rawContent.replace(Regex("(?s)<think>.*?</think>"), "").trim().ifBlank { rawContent.trim() }
        val jsonText = parseJsonBlock(cleanContent)
        val selected = runCatching { JSONObject(jsonText) }.getOrNull()

        val keys = listOf("selected_ids", "selected_id", "selected_indices", "ids", "indexes", "candidates", "selected_candidates")
        val ids = mutableListOf<Int>()

        if (selected != null) {
            for (key in keys) {
                val array = selected.optJSONArray(key)
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val valInt = array.optInt(i, -1)
                        if (valInt != -1) ids.add(valInt)
                    }
                    if (ids.isNotEmpty()) break
                }
                if (selected.has(key) && !selected.isNull(key)) {
                    val singleInt = selected.optInt(key, -1)
                    if (singleInt != -1) {
                        ids.add(singleInt)
                        break
                    }
                }
            }
        }

        if (ids.isEmpty()) {
            val keyPattern = Regex("\"(?:selected_ids|selected_id|selected_indices|ids|indexes|candidates|selected_candidates)\"\\s*:\\s*\\[([^]]+)\\]")
            val match = keyPattern.find(cleanContent)
            if (match != null) {
                match.groupValues[1].split(',').forEach { numStr ->
                    numStr.trim().toIntOrNull()?.let { ids.add(it) }
                }
            }
        }

        if (ids.isEmpty()) {
            val arrayPattern = Regex("\\[([0-9,\\s]+)\\]")
            val match = arrayPattern.find(cleanContent)
            if (match != null) {
                match.groupValues[1].split(',').forEach { numStr ->
                    numStr.trim().toIntOrNull()?.let { ids.add(it) }
                }
            }
        }

        val reasonText = selected?.optString("reason")?.ifBlank { null } ?: "已依据概率平滑与样本候选池完成选择。"
        return ParsedDoubaoResponse(ids.filter { it >= 0 }, reasonText, jsonText)
    }

    private fun parseJsonBlock(text: String): String {
        val blockMatch = Regex("(?s)```(?:json)?\\s*(\\{.*?\\})\\s*```").find(text)
        if (blockMatch != null) return blockMatch.groupValues[1]
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start != -1 && end > start) {
            return text.substring(start, end + 1)
        }
        return text
    }
}
