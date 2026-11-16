package cn.lotto.zhixi.data.remote

import cn.lotto.zhixi.domain.algorithm.doubao.DoubaoFallbackHandler
import cn.lotto.zhixi.domain.algorithm.doubao.DoubaoPromptBuilder
import cn.lotto.zhixi.domain.algorithm.doubao.DoubaoResponseParser
import cn.lotto.zhixi.domain.algorithm.doubao.DoubaoResultValidator
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

        val cutoffIssue = history.firstOrNull()?.issue.orEmpty()
        val sysPrompt = DoubaoPromptBuilder.buildSystemPrompt()
        val userPrompt = DoubaoPromptBuilder.buildUserPrompt(game, candidates, count, cutoffIssue, strategy)

        fun createRequestBody(disableThinking: Boolean) = JSONObject().apply {
            put("model", modelId)
            put("stream", false)
            put("temperature", 0.1)
            put("max_completion_tokens", 1500)
            if (disableThinking) {
                put("thinking", JSONObject().put("type", "disabled"))
            }
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", sysPrompt))
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
        }

        fun executeRequest(body: JSONObject): Pair<Int, String> {
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
                status to response
            } finally {
                connection.disconnect()
            }
        }

        var (status, response) = executeRequest(createRequestBody(disableThinking = true))
        if (status == 400 && response.contains("thinking", ignoreCase = true)) {
            val fallbackResult = executeRequest(createRequestBody(disableThinking = false))
            status = fallbackResult.first
            response = fallbackResult.second
        }

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
        Log.d("DOUBAO_RAW", "rawContent: $rawContent")

        val parsed = DoubaoResponseParser.parseResponse(rawContent)
        val validTickets = DoubaoResultValidator.validateAndMapTickets(parsed.selectedIds, candidates, count, game)

        if (validTickets.isEmpty()) {
            return DoubaoFallbackHandler.createFallbackResult(
                candidates = candidates,
                draws = emptyList(),
                count = count,
                years = years,
                mode = strategy,
                game = game,
                rawSummary = parsed.rawJsonText.ifBlank { rawContent }
            )
        }

        return DoubaoPickResult(validTickets, parsed.reasonText)
    }
}
