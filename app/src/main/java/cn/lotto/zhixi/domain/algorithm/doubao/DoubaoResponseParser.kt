
package cn.lotto.zhixi.domain.algorithm.doubao

import org.json.JSONArray
import org.json.JSONObject

data class ParsedDoubaoResponse(
    val selectedIds: List<Int>,
    val reasonText: String,
    val rawJsonText: String
)

object DoubaoResponseParser {

    private val supportedKeys = listOf(
        "selected_ids",
        "selected_id",
        "selected_indices",
        "ids",
        "indexes",
        "candidates",
        "selected_candidates"
    )

    private const val DEFAULT_REASON =
        "已根据本地候选池的综合评分和组合结构完成筛选。"

    /**
     * 解析豆包返回内容。
     */
    fun parseResponse(
        rawContent: String
    ): ParsedDoubaoResponse {

        val cleanContent = rawContent
            .replace(
                Regex("(?s)<think>.*?</think>"),
                ""
            )
            .trim()

        if (cleanContent.isBlank()) {

            return ParsedDoubaoResponse(
                selectedIds = emptyList(),
                reasonText = DEFAULT_REASON,
                rawJsonText = ""
            )
        }

        val jsonText = parseJsonBlock(cleanContent)

        val selected = runCatching {
            JSONObject(jsonText)
        }.getOrNull()

        if (selected != null) {

            val ids = extractIds(selected)

            val reasonValue = selected.opt("reason")

            val reason = (reasonValue as? String)
                ?.trim()
                .orEmpty()
                .ifBlank {
                    DEFAULT_REASON
                }

            return ParsedDoubaoResponse(
                selectedIds = ids,
                reasonText = reason,
                rawJsonText = jsonText
            )
        }

        /**
         * JSON 无法解析时，只从明确的候选 ID 字段中恢复。
         *
         * 不从任意数字数组中猜测 ID，
         * 防止把彩票号码数组误识别成候选编号。
         */
        val recoveredIds = extractIdsFromText(cleanContent)

        return ParsedDoubaoResponse(
            selectedIds = recoveredIds,
            reasonText = DEFAULT_REASON,
            rawJsonText = jsonText
        )
    }

    /**
     * 从 JSON 对象中提取候选 ID。
     */
    private fun extractIds(
        json: JSONObject
    ): List<Int> {

        for (key in supportedKeys) {

            if (!json.has(key) || json.isNull(key)) {
                continue
            }

            val value = json.opt(key)

            val ids = when (value) {

                is JSONArray -> {
                    extractIdsFromArray(value)
                }

                is Number -> {
                    listOfNotNull(
                        toExactInt(value)
                    )
                }

                else -> emptyList()
            }

            if (ids.isNotEmpty()) {
                return normalizeIds(ids)
            }
        }

        return emptyList()
    }

    /**
     * 从 JSON 数组中提取整数 ID。
     *
     * 只接受真正的整数数字，
     * 不接受字符串、布尔值、对象或小数。
     */
    private fun extractIdsFromArray(
        array: JSONArray
    ): List<Int> {

        val result = mutableListOf<Int>()

        for (i in 0 until array.length()) {

            val value = array.opt(i)

            if (value !is Number) {
                continue
            }

            val id = toExactInt(value)

            if (id != null && id >= 0) {
                result.add(id)
            }
        }

        return normalizeIds(result)
    }

    /**
     * 将 Number 安全转换为整数。
     *
     * 拒绝小数、无穷大、NaN 和 Int 越界值。
     */
    private fun toExactInt(
        value: Number
    ): Int? {

        val number = value.toDouble()

        if (!number.isFinite()) {
            return null
        }

        if (number % 1.0 != 0.0) {
            return null
        }

        if (
            number < Int.MIN_VALUE.toDouble() ||
            number > Int.MAX_VALUE.toDouble()
        ) {
            return null
        }

        return number.toInt()
    }

    /**
     * 对 ID 去重，并过滤负数。
     *
     * 保留模型原始选择顺序。
     */
    private fun normalizeIds(
        ids: List<Int>
    ): List<Int> {

        return ids
            .filter { it >= 0 }
            .distinct()
    }

    /**
     * 从非标准 JSON 文本中恢复明确字段的 ID。
     *
     * 仅接受完整的整数数组或单个整数。
     */
    private fun extractIdsFromText(
        text: String
    ): List<Int> {

        for (key in supportedKeys) {

            val pattern = Regex(
                """"${Regex.escape(key)}"\s*:\s*(\[[^\]]*]|-?\d+)"""
            )

            val match = pattern.find(text) ?: continue

            val value = match.groupValues[1].trim()

            val ids = if (value.startsWith("[")) {

                val content = value
                    .removePrefix("[")
                    .removeSuffix("]")
                    .trim()

                if (content.isBlank()) {
                    emptyList()
                } else {

                    val tokens = content.split(",")

                    // 只接受全部由整数构成的数组。
                    if (
                        tokens.any {
                            !Regex("""-?\d+""").matches(it.trim())
                        }
                    ) {
                        emptyList()
                    } else {
                        tokens.mapNotNull {
                            it.trim().toIntOrNull()
                        }
                    }
                }

            } else {

                listOfNotNull(
                    value.toIntOrNull()
                )
            }

            val normalized = normalizeIds(ids)

            if (normalized.isNotEmpty()) {
                return normalized
            }
        }

        return emptyList()
    }

    /**
     * 提取 JSON 对象。
     *
     * 支持：
     * 1. 纯 JSON
     * 2. ```json 代码围栏
     * 3. JSON 前后存在解释文字
     *
     * 使用括号深度和字符串状态识别对象边界，
     * 避免字符串中的大括号干扰解析。
     */
    private fun parseJsonBlock(
        text: String
    ): String {

        val fenced = Regex(
            """(?is)```(?:json)?\s*(.*?)\s*```"""
        ).find(text)

        val source = fenced
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?: text

        val start = source.indexOf('{')

        if (start < 0) {
            return source
        }

        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until source.length) {

            val char = source[i]

            if (inString) {

                if (escaped) {
                    escaped = false
                    continue
                }

                when (char) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }

                continue
            }

            when (char) {

                '"' -> {
                    inString = true
                }

                '{' -> {
                    depth++
                }

                '}' -> {
                    depth--

                    if (depth == 0) {
                        return source.substring(
                            start,
                            i + 1
                        )
                    }
                }
            }
        }

        return source
    }
}