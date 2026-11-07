package cn.lotto.zhixi

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
        require(modelId.isNotBlank()) { "请填写豆包模型 ID" }
        require(candidates.isNotEmpty()) { "本地候选号码为空" }
        val cutoff = (history.firstOrNull()?.date?.take(4)?.toIntOrNull() ?: 0) - years + 1
        val window = history.filter { (it.date.take(4).toIntOrNull() ?: 0) >= cutoff }
        val recent = window.take(20)
        val frontFreq = IntArray(game.frontMax + 1)
        val backFreq = IntArray(game.backMax + 1)
        window.forEach { draw ->
            draw.front.forEach { if (it in frontFreq.indices) frontFreq[it]++ }
            draw.back.forEach { if (it in backFreq.indices) backFreq[it]++ }
        }
        val candidateJson = JSONArray().apply {
            candidates.forEachIndexed { index, ticket ->
                put(JSONObject().put("id", index).put("front", JSONArray(ticket.reds)).put("back", JSONArray(ticket.secondary)).put("local_score", ticket.score))
            }
        }
        val stats = JSONObject().put("lottery", game.name).put("history_count", window.size)
            .put("front_frequency", frequencyObject(frontFreq, game)).put("back_frequency", frequencyObject(backFreq, game))
            .put("recent_draws", JSONArray().apply { recent.forEach { put(JSONObject().put("issue",it.issue).put("date",it.date).put("front",JSONArray(it.front)).put("back",JSONArray(it.back))) } })
            .put("strategy", strategy).put("candidate_pool", candidateJson)
        val prompt = "根据提供的${game.name}历史统计与本地候选池，选择 $count 组结构较均衡的候选号。只允许选择候选池已有 id，不能编造或修改号码。历史频率只是描述性统计，不意味着可以预测随机开奖。请仅返回 JSON：{\"selected_ids\":[整数...],\"reason\":\"不超过100字的简短说明\"}。历史数据和候选池：$stats"
        val body = JSONObject().put("model", modelId).put("stream", false).put("temperature", 0.2)
            .put("max_completion_tokens", 700)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", "你是彩票历史数据分析助手。严格遵循用户提供的候选编号和 JSON 格式，不声称能预测随机开奖结果。"))
                .put(JSONObject().put("role", "user").put("content", prompt)))
        val connection = (URL("https://ark.cn-beijing.volces.com/api/v3/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 90_000; doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val root = runCatching { JSONObject(response) }.getOrElse { error("豆包返回无法识别的响应（HTTP $status）") }
            if (status !in 200..299) {
                val detail = root.optJSONObject("error")?.optString("message").orEmpty()
                error("豆包请求失败（HTTP $status）${detail.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}")
            }
            val content = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                ?: error("豆包响应缺少 choices.message.content")
            val jsonText = content.substringAfter('{', "").let { "{" + it.substringBeforeLast('}', "") + "}" }
            val selected = JSONObject(jsonText)
            val ids = selected.optJSONArray("selected_ids") ?: error("豆包响应缺少 selected_ids")
            val output = mutableListOf<Ticket>()
            for (i in 0 until ids.length()) {
                val id = ids.optInt(i, -1)
                val ticket = candidates.getOrNull(id) ?: continue
                if (output.none { it.reds == ticket.reds && it.secondary == ticket.secondary }) output += ticket.copy(gameCode = game.code)
                if (output.size == count) break
            }
            require(output.isNotEmpty()) { "豆包没有选择有效候选号码，请重试" }
            return DoubaoPickResult(output, selected.optString("reason", "已依据本地历史统计和候选池完成筛选。"))
        } finally { connection.disconnect() }
    }

    private fun frequencyObject(freq: IntArray, game: LotteryGame): JSONObject = JSONObject().apply {
        val start = if (game.digits) 0 else 1
        for (n in start until freq.size) put(n.toString(), freq[n])
    }
}
