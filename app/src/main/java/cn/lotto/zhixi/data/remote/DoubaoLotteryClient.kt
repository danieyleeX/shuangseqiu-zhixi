package cn.lotto.zhixi.data.remote

import cn.lotto.zhixi.data.local.LotteryDatabase
import cn.lotto.zhixi.domain.engine.LotteryEngine
import cn.lotto.zhixi.domain.model.*

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object DoubaoKeyVault {
    private const val alias = "lottery_doubao_key_v1"
    private const val prefsName = "doubao_settings"
    private const val keyName = "api_key_encrypted"

    fun loadOrInitialize(context: Context, defaultKey: String): String {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val stored = prefs.getString(keyName, null)
        if (stored != null) return runCatching { decrypt(stored) }.getOrDefault("")
        if (defaultKey.isNotBlank()) save(context, defaultKey)
        return defaultKey
    }

    fun save(context: Context, apiKey: String) {
        val encrypted = encrypt(apiKey)
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putString(keyName, encrypted).apply()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val packed = Base64.decode(value, Base64.NO_WRAP)
        val iv = packed.copyOfRange(0, 12)
        val encrypted = packed.copyOfRange(12, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }
}

data class DoubaoPickResult(val tickets: List<Ticket>, val reasoning: String)

object DoubaoLotteryClient {
    fun generate(
        game: LotteryGame,
        history: List<GameDraw>,
        candidates: List<Ticket>,
        count: Int,
        years: Int,
        strategy: String,
        modelId: String,
        apiKey: String
    ): DoubaoPickResult {
        require(apiKey.isNotBlank()) { "请先配置豆包 API Key" }
        require(modelId.isNotBlank()) { "请填写豆包模型 ID 或推理接入点 Endpoint ID" }
        require(candidates.isNotEmpty()) { "本地候选号码为空" }

        val cutoff = (history.firstOrNull()?.date?.take(4)?.toIntOrNull() ?: 0) - years + 1
        val window = history.filter { (it.date.take(4).toIntOrNull() ?: 0) >= cutoff }
        val frontFreq = IntArray(game.frontMax + 1)
        val backFreq = IntArray(game.backMax + 1)
        window.forEach { draw ->
            draw.front.forEach { if (it in frontFreq.indices) frontFreq[it]++ }
            draw.back.forEach { if (it in backFreq.indices) backFreq[it]++ }
        }

        val candidateJson = JSONArray().apply {
            candidates.forEachIndexed { index, ticket ->
                put(JSONObject()
                    .put("id", index)
                    .put("front", JSONArray(ticket.reds))
                    .put("back", JSONArray(ticket.secondary))
                    .put("local_score", ticket.score))
            }
        }

        val stats = JSONObject()
            .put("sample_group", game.name)
            .put("sample_count", window.size)
            .put("front_frequency", frequencyObject(frontFreq, game))
            .put("back_frequency", frequencyObject(backFreq, game))
            .put("strategy", strategy)
            .put("candidate_pool", candidateJson)

        val prompt = """
从候选池 candidate_pool 抽样选出 $count 组样本 id，优先选择分布均衡的样本。
仅使用池内已有 id，不可编造 id。
输出强制规则：只返回标准 JSON，禁止 markdown、注释、额外文字。
格式：{"selected_ids":[id0,id1...], "reason":"均衡说明"}
统计数据：$stats
        """.trimIndent()

        val body = JSONObject()
            .put("model", modelId)
            .put("stream", false)
            .put("temperature", 0.1)
            .put("max_completion_tokens", 1000)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", "你是一个只输出纯 JSON 的数据分析助手。从给定的 candidate_pool 候选池中选择 $count 组候选 ID。必须严格输出符合格式的纯 JSON，格式为：{\"selected_ids\":[0,1,2],\"reason\":\"说明文字\"}。绝对禁止使用 Markdown 标记（如 ```json）、禁止包含任何前言、后语或 Markdown 解释。"))
                .put(JSONObject().put("role", "user").put("content", prompt))
            )

        val connection = (URL("https://ark.cn-beijing.volces.com/api/v3/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            Log.d("DOUBAO_RAW_FULL_RESP", "status=$status, response=$response")
            val root = runCatching { JSONObject(response) }.getOrNull()

            if (status !in 200..299 || root == null) {
                val errorBody = root?.optJSONObject("error")
                val code = errorBody?.optString("code").orEmpty()
                val detail = errorBody?.optString("message").orEmpty()
                val guidance = when (code) {
                    "ModelNotOpen" -> "方舟账号尚未开通此模型。请在方舟控制台开通模型服务，或填写已创建的推理接入点 ID（Endpoint ID）。"
                    "InvalidEndpointOrModel.ModelIDAccessDisabled" -> "当前方舟账号不允许直接使用模型 ID，请填写控制台中已创建的推理接入点 ID（Endpoint ID，例如 ep-20250101xxxxxx-xxxxx）。"
                    "InvalidEndpointOrModel.NotFound" -> "模型或推理接入点不存在，或当前账号无权访问。请核对模型 ID / Endpoint ID 并确认已开通。"
                    "PathNotFound" -> "方舟接口路径未识别，请检查 API 区域和接口地址。"
                    else -> detail.ifBlank { "HTTP $status 请求异常，请检查模型 Endpoint ID 和 API Key。" }
                }
                error("豆包请求失败（$guidance）")
            }

            val rawContent = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
            val cleanContent = rawContent.replace(Regex("(?s)<think>.*?</think>"), "").trim().ifBlank { rawContent.trim() }

            Log.d("DOUBAO_RAW", "rawContent: $rawContent")
            Log.d("DOUBAO_RAW", "cleanContent: $cleanContent")

            val parsedIds = extractCandidateIds(cleanContent)
            val jsonObject = runCatching { JSONObject(parseJsonBlock(cleanContent)) }.getOrNull()
            val reasonText = jsonObject?.optString("reason")?.ifBlank { null } ?: "已根据模型分析和本地候选池完成精选。"

            val validIds = parsedIds.filter { it in candidates.indices }.distinct().take(count)

            if (validIds.isEmpty()) {
                val fallbackTickets = candidates.shuffled().take(count).map { it.copy(gameCode = game.code) }
                val rawSummary = cleanContent.take(120).replace("\n", " ")
                val fallbackReason = "【模型未返回有效编号，已自动随机挑选】 原始返回：${rawSummary.ifBlank { "模型响应空白" }}"
                return DoubaoPickResult(fallbackTickets, fallbackReason)
            }

            val output = validIds.map { candidates[it].copy(gameCode = game.code) }
            return DoubaoPickResult(output, reasonText)
        } finally {
            connection.disconnect()
        }
    }

    private fun extractCandidateIds(cleanContent: String): List<Int> {
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

        return ids.filter { it >= 0 }
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

    private fun frequencyObject(freq: IntArray, game: LotteryGame): JSONObject = JSONObject().apply {
        val start = if (game.digits) 0 else 1
        for (n in start until freq.size) put(n.toString(), freq[n])
    }
}
