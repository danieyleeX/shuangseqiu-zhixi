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
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
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
        require(modelId.isNotBlank()) { "请填写豆包模型 ID" }
        require(candidates.isNotEmpty()) { "本地候选号码为空" }

        // 最多重试一次
        val firstResult = runRequestOnce(game, history, candidates, count, years, strategy, modelId, apiKey)
        if (firstResult != null) return firstResult
        val secondResult = runRequestOnce(game, history, candidates, count, years, strategy, modelId, apiKey)
        if (secondResult != null) return secondResult

        // 两次都空，兜底随机
        val fallbackTickets = candidates.shuffled().take(count).map { it.copy(gameCode = game.code) }
        return DoubaoPickResult(fallbackTickets, "【两次请求均返回空内容，模型被风控拦截】已自动随机选取候选号码")
    }

    private fun runRequestOnce(
        game: LotteryGame,
        history: List<GameDraw>,
        candidates: List<Ticket>,
        count: Int,
        years: Int,
        strategy: String,
        modelId: String,
        apiKey: String
    ): DoubaoPickResult? {
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

        // 精简风控友好prompt
        val prompt = """
从候选池 candidate_pool 抽样选出 $count 组样本id，优先选择分布均衡的样本。
仅使用池内已有id，不可编造id。
输出强制规则：只返回标准JSON，禁止markdown、注释、额外文字。
格式：{"selected_ids":[id0,id1...], "reason":"均衡说明"}
无法抽样返回：{"selected_ids":[], "reason":"无法完成抽样"}
统计数据：$stats
        """.trimIndent()

        val body = JSONObject()
            .put("model", modelId)
            .put("stream", false)
            .put("temperature", 0.3) // 调高，降低风控概率
            .put("max_completion_tokens", 1000)
            .put("response_format", JSONObject().put("type", "json_object")) // 强制json输出
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", """
你是统计抽样助手，严格只输出JSON，不要任何附加文字。
固定输出模板：{"selected_ids":[整数数组],"reason":"字符串"}
无法处理直接输出 {"selected_ids":[],"reason":"无法完成抽样"}
""".trimIndent()))
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

        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            Log.d("DOUBAO_RAW_FULL_RESP", "status=$status, response=$response") // 打印完整返回，排查拦截原因
            val root = runCatching { JSONObject(response) }.getOrNull() ?: return null

            if (status !in 200..299) {
                val errorBody = root.optJSONObject("error")
                val code = errorBody?.optString("code").orEmpty()
                val detail = errorBody?.optString("message").orEmpty()
                Log.e("DOUBAO_ERR", "code=$code, detail=$detail")
                return null
            }

            val rawContent = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                ?: return null

            val cleanContent = rawContent.trim()
            Log.d("DOUBAO_RAW", "rawContent: $rawContent")
            Log.d("DOUBAO_RAW", "cleanContent: $cleanContent")

            if (cleanContent.isBlank()) return null

            val parsedIds = extractCandidateIds(cleanContent)
            val jsonObject = runCatching { JSONObject(parseJsonBlock(cleanContent)) }.getOrNull()
            val reasonText = jsonObject?.optString("reason")?.ifBlank { null } ?: "已完成样本均衡抽样"

            val validIds = parsedIds.filter { it in candidates.indices }.distinct().take(count)
            if (validIds.isEmpty()) {
                val fallbackTickets = candidates.shuffled().take(count).map { it.copy(gameCode = game.code) }
                val rawSummary = cleanContent.take(120).replace("\n", " ")
                val fallbackReason = "【模型输出格式异常，已自动随机选取候选号码】 原始返回：$rawSummary"
                return DoubaoPickResult(fallbackTickets, fallbackReason)
            }

            val output = validIds.map { candidates[it].copy(gameCode = game.code) }
            DoubaoPickResult(output, reasonText)
        } catch (ex: Exception) {
            Log.e("DOUBAO_EX", "请求异常", ex)
            return null
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
