
package cn.lotto.zhixi.data.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import cn.lotto.zhixi.domain.algorithm.doubao.DoubaoFallbackHandler
import cn.lotto.zhixi.domain.algorithm.doubao.DoubaoPromptBuilder
import cn.lotto.zhixi.domain.algorithm.doubao.DoubaoResponseParser
import cn.lotto.zhixi.domain.algorithm.doubao.DoubaoResultValidator
import cn.lotto.zhixi.domain.model.Draw
import cn.lotto.zhixi.domain.model.GameDraw
import cn.lotto.zhixi.domain.model.LotteryGame
import cn.lotto.zhixi.domain.model.Ticket
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
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

    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128

    /**
     * 读取已保存的 API Key。
     *
     * 如果密文损坏或 Keystore 密钥失效，
     * 清理失效数据，再尝试使用默认 Key 初始化。
     */
    @Synchronized
    fun loadOrInitialize(
        context: Context,
        defaultKey: String
    ): String {

        val prefs = context.getSharedPreferences(
            prefsName,
            Context.MODE_PRIVATE
        )

        val stored = prefs.getString(keyName, null)

        if (!stored.isNullOrBlank()) {

            val decrypted = runCatching {
                decrypt(stored)
            }.getOrNull()

            if (!decrypted.isNullOrBlank()) {
                return decrypted
            }

            Log.w(
                "DoubaoKeyVault",
                "已保存的 API Key 无法解密，将清理失效数据"
            )

            prefs.edit()
                .remove(keyName)
                .apply()
        }

        if (defaultKey.isNotBlank()) {
            runCatching {
                save(context, defaultKey)
            }.onFailure {
                Log.e(
                    "DoubaoKeyVault",
                    "默认 API Key 加密保存失败",
                    it
                )
            }
        }

        return defaultKey
    }

    /**
     * 保存 API Key。
     */
    @Synchronized
    fun save(
        context: Context,
        apiKey: String
    ) {

        require(apiKey.isNotBlank()) {
            "API Key 不能为空"
        }

        val encrypted = encrypt(apiKey)

        context.getSharedPreferences(
            prefsName,
            Context.MODE_PRIVATE
        )
            .edit()
            .putString(keyName, encrypted)
            .apply()
    }

    /**
     * 删除本地保存的 API Key。
     */
    @Synchronized
    fun clear(context: Context) {

        context.getSharedPreferences(
            prefsName,
            Context.MODE_PRIVATE
        )
            .edit()
            .remove(keyName)
            .apply()
    }

    /**
     * 获取或创建 Android Keystore AES 密钥。
     */
    private fun secretKey(): SecretKey {

        val store = KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
        }

        val existing = store.getKey(alias, null) as? SecretKey

        if (existing != null) {
            return existing
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(
                KeyProperties.ENCRYPTION_PADDING_NONE
            )
            .setRandomizedEncryptionRequired(true)
            .build()

        generator.init(spec)

        return generator.generateKey()
    }

    /**
     * AES/GCM 加密。
     *
     * 保存格式：
     * IV + 密文 + GCM认证标签
     */
    private fun encrypt(value: String): String {

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        cipher.init(
            Cipher.ENCRYPT_MODE,
            secretKey()
        )

        val encrypted = cipher.doFinal(
            value.toByteArray(Charsets.UTF_8)
        )

        val packed = cipher.iv + encrypted

        return Base64.encodeToString(
            packed,
            Base64.NO_WRAP
        )
    }

    /**
     * AES/GCM 解密。
     */
    private fun decrypt(value: String): String {

        val packed = Base64.decode(
            value,
            Base64.NO_WRAP
        )

        require(packed.size > IV_LENGTH) {
            "API Key 密文长度异常"
        }

        val iv = packed.copyOfRange(
            0,
            IV_LENGTH
        )

        val encrypted = packed.copyOfRange(
            IV_LENGTH,
            packed.size
        )

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(
                TAG_LENGTH_BITS,
                iv
            )
        )

        return String(
            cipher.doFinal(encrypted),
            Charsets.UTF_8
        )
    }
}

data class DoubaoPickResult(
    val tickets: List<Ticket>,
    val reasoning: String
)

object DoubaoLotteryClient {

    private const val TAG = "DoubaoLotteryClient"

    private const val API_URL =
        "https://ark.cn-beijing.volces.com/api/v3/chat/completions"

    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 90_000

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

        require(apiKey.isNotBlank()) {
            "请先配置豆包 API Key"
        }

        require(modelId.isNotBlank()) {
            "请填写豆包模型 ID 或推理接入点 Endpoint ID"
        }

        require(candidates.isNotEmpty()) {
            "本地候选号码为空"
        }

        require(count > 0) {
            "生成注数必须大于0"
        }

        require(years > 0) {
            "历史分析年份必须大于0"
        }

        require(candidates.all { it.gameCode == game.code }) {
            "候选号码与当前彩种不匹配"
        }

        /*
         * 豆包最多分析30组候选。
         *
         * 提示词和结果验证必须使用同一个数量，
         * 避免请求数量超过30时发生验证不一致。
         */
        val aiSelectionCount = minOf(
            count,
            candidates.size,
            DoubaoPromptBuilder.MAX_CANDIDATES
        )

        val cutoffIssue = history
            .firstOrNull()
            ?.issue
            .orEmpty()

        val sysPrompt = DoubaoPromptBuilder.buildSystemPrompt()

        val userPrompt = DoubaoPromptBuilder.buildUserPrompt(
            game,
            candidates,
            aiSelectionCount,
            cutoffIssue,
            strategy
        )

        /**
         * 构建豆包请求 JSON。
         */
        fun createRequestBody(
            disableThinking: Boolean
        ): JSONObject {

            return JSONObject().apply {

                put("model", modelId)
                put("stream", false)
                put("temperature", 0.1)
                put("max_completion_tokens", 1500)

                if (disableThinking) {
                    put(
                        "thinking",
                        JSONObject().put("type", "disabled")
                    )
                }

                put(
                    "messages",
                    JSONArray().apply {

                        put(
                            JSONObject()
                                .put("role", "system")
                                .put("content", sysPrompt)
                        )

                        put(
                            JSONObject()
                                .put("role", "user")
                                .put("content", userPrompt)
                        )
                    }
                )
            }
        }

        /**
         * 执行一次 HTTP 请求。
         */
        fun executeRequest(
            body: JSONObject
        ): Pair<Int, String> {

            val connection =
                (URL(API_URL).openConnection() as HttpURLConnection)
                    .apply {

                        requestMethod = "POST"

                        connectTimeout = CONNECT_TIMEOUT
                        readTimeout = READ_TIMEOUT

                        doOutput = true

                        setRequestProperty(
                            "Authorization",
                            "Bearer $apiKey"
                        )

                        setRequestProperty(
                            "Content-Type",
                            "application/json; charset=utf-8"
                        )

                        setRequestProperty(
                            "Accept",
                            "application/json"
                        )
                    }

            try {

                connection.outputStream.use {
                    it.write(
                        body.toString().toByteArray(
                            Charsets.UTF_8
                        )
                    )
                    it.flush()
                }

                val status = connection.responseCode

                val responseStream =
                    if (status in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                val response = responseStream
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()

                return status to response

            } finally {
                connection.disconnect()
            }
        }

        /**
         * 本地兜底。
         *
         * 必须传入真实历史数据，不能使用空列表。
         */
        fun createFallback(
            rawSummary: String
        ): DoubaoPickResult {

            Log.w(
                TAG,
                "豆包结果无效，启用本地候选兜底"
            )

            return DoubaoFallbackHandler.createFallbackResult(
                candidates = candidates,
                draws = if (game.code == "ssq") {
                    history.mapNotNull { draw ->

                        val blue = draw.back.firstOrNull()
                            ?: return@mapNotNull null

                        Draw(
                            issue = draw.issue,
                            date = draw.date,
                            reds = draw.front,
                            blue = blue
                        )
                    }
                } else {
                    emptyList()
                },
                count = count,
                years = years,
                mode = strategy,
                game = game,
                rawSummary = rawSummary
            )
        }

        /*
         * 第一次请求关闭思考模式。
         * 如果接口不支持 thinking 参数，则重试一次。
         */
        var (status, response) = try {
            executeRequest(
                createRequestBody(
                    disableThinking = true
                )
            )
        } catch (e: IOException) {

            Log.e(
                TAG,
                "豆包网络请求失败，启用本地兜底",
                e
            )

            return createFallback(
                "网络连接失败，已启用本地候选兜底"
            )
        }

        if (
            status == 400 &&
            response.contains(
                "thinking",
                ignoreCase = true
            )
        ) {

            Log.w(
                TAG,
                "当前模型不支持指定的 thinking 参数，尝试兼容模式"
            )

            val fallbackRequest = try {
                executeRequest(
                    createRequestBody(
                        disableThinking = false
                    )
                )
            } catch (e: IOException) {

                Log.e(
                    TAG,
                    "豆包兼容模式请求失败，启用本地兜底",
                    e
                )

                return createFallback(
                    "网络连接失败，已启用本地候选兜底"
                )
            }

            status = fallbackRequest.first
            response = fallbackRequest.second
        }

        /**
         * 解析 HTTP 返回内容。
         */
        val root = runCatching {
            JSONObject(response)
        }.getOrNull()

        if (status !in 200..299 || root == null) {

            val errorBody = root?.optJSONObject("error")

            val code = errorBody
                ?.optString("code")
                .orEmpty()

            val detail = errorBody
                ?.optString("message")
                .orEmpty()

            val guidance = when (code) {

                "ModelNotOpen" ->
                    "方舟账号尚未开通此模型。请在方舟控制台开通模型服务，或填写已创建的推理接入点 ID。"

                "InvalidEndpointOrModel.ModelIDAccessDisabled" ->
                    "当前方舟账号不允许直接使用模型 ID，请填写已创建的推理接入点 Endpoint ID。"

                "InvalidEndpointOrModel.NotFound" ->
                    "模型或推理接入点不存在，或当前账号无权访问，请检查模型 ID / Endpoint ID。"

                "PathNotFound" ->
                    "方舟接口路径未识别，请检查 API 区域和接口地址。"

                else ->
                    detail.ifBlank {
                        "HTTP $status 请求异常，请检查模型 Endpoint ID 和 API Key。"
                    }
            }

            Log.e(
                TAG,
                "豆包请求失败：HTTP $status，错误代码：$code"
            )

            error(
                "豆包请求失败（$guidance）"
            )
        }

        /**
         * 检查 choices。
         */
        val choices = root.optJSONArray("choices")
            ?: return createFallback(
                "豆包返回内容缺少 choices 字段"
            )

        if (choices.length() == 0) {

            return createFallback(
                "豆包返回了空 choices"
            )
        }

        val message = choices
            .optJSONObject(0)
            ?.optJSONObject("message")
            ?: return createFallback(
                "豆包返回内容缺少 message 字段"
            )

        val rawContent = message
            .optString("content")
            .trim()

        if (rawContent.isBlank()) {

            return createFallback(
                "豆包返回的 content 为空"
            )
        }

        /**
         * 只记录必要的诊断信息，不输出完整 AI 响应。
         */
        Log.d(
            TAG,
            "豆包响应成功，HTTP=$status，内容长度=${rawContent.length}"
        )

        /**
         * 解析模型输出。
         */
        val parsed = runCatching {
            DoubaoResponseParser.parseResponse(rawContent)
        }.getOrElse {

            Log.e(
                TAG,
                "豆包响应解析失败",
                it
            )

            return createFallback(
                rawContent
            )
        }

        /**
         * 验证 AI 选择的候选号码。
         *
         * 只允许从本地候选池中映射号码，
         * 不允许 AI 自行创造号码。
         */
        val validTickets = runCatching {
            DoubaoResultValidator.validateAndMapTickets(
                parsed.selectedIds,
                candidates,
                aiSelectionCount,
                game
            )
        }.getOrElse {

            Log.e(
                TAG,
                "豆包候选号码验证失败",
                it
            )

            emptyList()
        }

        /*
         * 如果 AI 返回数量不足，
         * 不将不完整结果当成成功结果。
         * 交给本地兜底处理。
         */
        if (validTickets.size < aiSelectionCount) {

            Log.w(
                TAG,
                "豆包有效候选数量不足：实际=${validTickets.size}，要求=$aiSelectionCount"
            )

            return createFallback(
                parsed.rawJsonText.ifBlank {
                    rawContent
                }
            )
        }

        return DoubaoPickResult(
            tickets = validTickets,
            reasoning = parsed.reasonText
        )
    }
}