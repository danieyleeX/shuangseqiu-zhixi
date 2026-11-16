
package cn.lotto.zhixi.data.remote

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cn.lotto.zhixi.data.local.LotteryDatabase
import cn.lotto.zhixi.domain.model.Draw
import cn.lotto.zhixi.domain.model.GameDraw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class OfficialDrawSync(
    private val database: LotteryDatabase
) {

    companion object {
        private const val TAG = "OfficialDrawSync"
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 100
    }

    fun syncLatest(): Int = syncAll()

    fun syncGame(gameCode: String): Int {
        return when (gameCode) {
            "ssq" -> syncWelfare("ssq", "ssq")
            "fc3d" -> syncWelfare("fc3d", "3d")
            "dlt" -> syncSport("dlt", "85")
            "pl3" -> syncSport("pl3", "35")
            "pl5" -> syncSport("pl5", "350133")
            else -> 0
        }
    }

    /**
     * 同步全部彩种。
     *
     * 单个彩种失败不会中断其他彩种。
     * 如果全部彩种都失败，则抛出异常，让Worker执行重试。
     */
    fun syncAll(): Int {
        val games = listOf(
            "ssq",
            "fc3d",
            "dlt",
            "pl3",
            "pl5"
        )

        var total = 0
        var successCount = 0
        val failures = mutableListOf<String>()

        for (gameCode in games) {
            try {
                val synced = syncGame(gameCode)
                total += synced
                successCount++

                Log.i(
                    TAG,
                    "$gameCode 同步完成，本次写入/更新 $synced 条"
                )
            } catch (e: Exception) {
                failures.add("$gameCode: ${e.message}")

                Log.e(
                    TAG,
                    "$gameCode 同步失败，继续同步其他彩种",
                    e
                )
            }
        }

        Log.i(
            TAG,
            "全部彩种同步结束：成功=$successCount，失败=${failures.size}，总写入/更新=$total"
        )

        if (successCount == 0) {
            throw IllegalStateException(
                "全部彩种同步失败：" + failures.joinToString("; ")
            )
        }

        if (failures.isNotEmpty()) {
            Log.w(
                TAG,
                "以下彩种同步失败，等待下次定时任务重试：${failures.joinToString("; ")}"
            )
        }

        return total
    }

    /**
     * 中国福利彩票官方数据同步。
     *
     * 支持：
     * ssq  双色球
     * fc3d 福彩3D
     *
     * 本地没有数据时分页获取全部历史。
     * 本地已有数据时只获取最新缺失期号。
     *
     * 注意：依赖官方接口按最新期号倒序返回。
     */
    private fun syncWelfare(
        gameCode: String,
        apiName: String
    ): Int {

        require(gameCode == "ssq" || gameCode == "fc3d") {
            "不支持的福利彩票类型：$gameCode"
        }

        val home =
            "https://www.cwl.gov.cn/ygkj/wqkjgg/ssq/"

        // 读取本地已有期号。
        val existingIssues = database.readGame(gameCode)
            .map { it.issue }
            .toHashSet()

        Log.i(
            TAG,
            "$gameCode 本地已有${existingIssues.size}期，开始增量同步"
        )

        val allDraws = linkedMapOf<String, GameDraw>()

        for (page in 1..MAX_PAGES) {

            val apiUrl =
                "https://www.cwl.gov.cn/cwl_admin/front/cwlkj/search/kjxx/findDrawNotice" +
                        "?name=$apiName" +
                        "&pageNo=$page" +
                        "&pageSize=$PAGE_SIZE" +
                        "&systemType=PC"

            val connection =
                (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 20_000

                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
                    )

                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Referer", home)
                }

            val rows = try {
                val status = connection.responseCode

                if (status !in 200..299) {
                    error("中国福彩网返回 HTTP $status，第${page}页")
                }

                val body = connection.inputStream
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }

                val root = JSONObject(body)

                if (root.optInt("state", -1) != 0) {
                    error(
                        "中国福彩网接口异常：" +
                                root.optString("message", "未知错误")
                    )
                }

                root.optJSONArray("result")
                    ?: error("中国福彩网没有返回开奖数据，第${page}页")

            } finally {
                connection.disconnect()
            }

            if (rows.length() == 0) {
                Log.i(TAG, "$gameCode 第${page}页没有数据，分页结束")
                break
            }

            var newCount = 0
            var foundExistingIssue = false

            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue

                try {
                    val issue = row.optString("code").trim()

                    require(issue.isNotBlank()) {
                        "期号为空"
                    }

                    // 已经追上本地数据，不再向更早的历史翻页。
                    if (issue in existingIssues) {
                        foundExistingIssue = true
                        break
                    }

                    val date = row.optString("date")
                        .substringBefore("(")
                        .trim()
                        .take(10)

                    require(date.isNotBlank()) {
                        "开奖日期为空"
                    }

                    val draw = if (gameCode == "ssq") {

                        val reds = row.optString("red")
                            .split(",")
                            .map { it.trim().toInt() }
                            .sorted()

                        val blue = row.optString("blue")
                            .trim()
                            .toInt()

                        require(
                            reds.size == 6 &&
                                    reds.distinct().size == 6 &&
                                    reds.all { it in 1..33 } &&
                                    blue in 1..16
                        ) {
                            "双色球号码校验失败：$issue"
                        }

                        GameDraw(
                            gameCode = "ssq",
                            issue = issue,
                            date = date,
                            front = reds,
                            back = listOf(blue)
                        )

                    } else {

                        val raw = row.optString(
                            "red",
                            row.optString("result")
                        )

                        val digits = raw
                            .filter { it.isDigit() }
                            .map { it.digitToInt() }

                        require(
                            digits.size == 3 &&
                                    digits.all { it in 0..9 }
                        ) {
                            "福彩3D号码校验失败：$issue"
                        }

                        GameDraw(
                            gameCode = "fc3d",
                            issue = issue,
                            date = date,
                            front = digits
                        )
                    }

                    if (!allDraws.containsKey(issue)) {
                        allDraws[issue] = draw
                        newCount++
                    }

                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "$gameCode 第${page}页第${i + 1}条记录解析失败，跳过",
                        e
                    )
                }
            }

            Log.i(
                TAG,
                "$gameCode 已读取第${page}页，本次新增候选${allDraws.size}期"
            )

            if (foundExistingIssue) {
                Log.i(
                    TAG,
                    "$gameCode 已遇到本地已有期号，增量同步结束"
                )
                break
            }

            // 如果接口重复返回相同页面，停止，避免无限请求。
            if (newCount == 0) {
                Log.w(
                    TAG,
                    "$gameCode 第${page}页没有新增期号，停止分页"
                )
                break
            }

            // 返回记录不足一页，说明已经到达历史数据末尾。
            if (rows.length() < PAGE_SIZE) {
                Log.i(
                    TAG,
                    "$gameCode 已到达历史数据末尾"
                )
                break
            }
        }

        val parsed = allDraws.values.toList()

        if (parsed.isEmpty()) {
            if (existingIssues.isNotEmpty()) {
                Log.i(
                    TAG,
                    "$gameCode 没有新期号，无需更新"
                )
                return 0
            }

            error("中国福彩网没有返回有效的${gameCode}开奖记录")
        }

        if (gameCode == "ssq") {
            database.upsert(
                parsed.map {
                    Draw(
                        issue = it.issue,
                        date = it.date,
                        reds = it.front,
                        blue = it.back.first()
                    )
                }
            )
        }

        val savedCount = database.upsertGame(
            parsed,
            "cwl.gov.cn"
        )

        Log.i(
            TAG,
            "$gameCode 增量同步完成，写入/更新${savedCount}期"
        )

        return savedCount
    }

    /**
     * 中国体育彩票官方数据同步。
     *
     * 支持：
     * dlt  超级大乐透
     * pl3  排列三
     * pl5  排列五
     *
     * 本地没有数据时分页获取全部历史。
     * 本地已有数据时只获取最新缺失期号。
     *
     * 注意：依赖官方接口按最新期号倒序返回。
     */
    private fun syncSport(
        gameCode: String,
        gameNo: String
    ): Int {

        require(
            gameCode == "dlt" ||
                    gameCode == "pl3" ||
                    gameCode == "pl5"
        ) {
            "不支持的体育彩票类型：$gameCode"
        }

        // 读取本地已有期号。
        val existingIssues = database.readGame(gameCode)
            .map { it.issue }
            .toHashSet()

        Log.i(
            TAG,
            "$gameCode 本地已有${existingIssues.size}期，开始增量同步"
        )

        val allDraws = linkedMapOf<String, GameDraw>()

        for (page in 1..MAX_PAGES) {

            val apiUrl =
                "https://webapi.sporttery.cn/gateway/lottery/getHistoryPageListV1.qry" +
                        "?gameNo=$gameNo" +
                        "&provinceId=0" +
                        "&isVerify=1" +
                        "&pageNo=$page" +
                        "&pageSize=$PAGE_SIZE"

            val connection =
                (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 20_000

                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
                    )

                    setRequestProperty("Accept", "application/json")

                    setRequestProperty(
                        "Referer",
                        "https://m.lottery.gov.cn/zst/tbsj/"
                    )
                }

            val list = try {
                val status = connection.responseCode

                if (status !in 200..299) {
                    error("中国体彩网返回 HTTP $status，第${page}页")
                }

                val body = connection.inputStream
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }

                val root = JSONObject(body)

                root.optJSONObject("value")
                    ?.optJSONArray("list")
                    ?: error(
                        "中国体彩网没有返回${gameCode}开奖列表，第${page}页"
                    )

            } finally {
                connection.disconnect()
            }

            if (list.length() == 0) {
                Log.i(TAG, "$gameCode 第${page}页没有数据，分页结束")
                break
            }

            var newCount = 0
            var foundExistingIssue = false

            for (i in 0 until list.length()) {
                val row = list.optJSONObject(i) ?: continue

                try {
                    val issue = row
                        .optString("lotteryDrawNum")
                        .trim()

                    require(issue.isNotBlank()) {
                        "开奖期号为空"
                    }

                    // 已经追上本地数据，不再向更早的历史翻页。
                    if (issue in existingIssues) {
                        foundExistingIssue = true
                        break
                    }

                    val date = row
                        .optString("lotteryDrawTime")
                        .trim()
                        .take(10)

                    val raw = row
                        .optString("lotteryDrawResult")
                        .trim()

                    require(date.isNotBlank()) {
                        "开奖日期为空"
                    }

                    val nums = Regex("\\d+")
                        .findAll(raw)
                        .map { it.value.toInt() }
                        .toList()

                    val draw = when (gameCode) {

                        "dlt" -> {
                            require(nums.size == 7) {
                                "大乐透号码数量错误：$issue"
                            }

                            val front = nums.take(5)
                            val back = nums.drop(5)

                            require(
                                front.distinct().size == 5 &&
                                        front.all { it in 1..35 } &&
                                        back.distinct().size == 2 &&
                                        back.all { it in 1..12 }
                            ) {
                                "大乐透号码范围或重复校验失败：$issue"
                            }

                            GameDraw(
                                gameCode = "dlt",
                                issue = issue,
                                date = date,
                                front = front,
                                back = back
                            )
                        }

                        "pl3" -> {
                            require(
                                nums.size == 3 &&
                                        nums.all { it in 0..9 }
                            ) {
                                "排列三号码校验失败：$issue"
                            }

                            GameDraw(
                                gameCode = "pl3",
                                issue = issue,
                                date = date,
                                front = nums
                            )
                        }

                        "pl5" -> {
                            require(
                                nums.size == 5 &&
                                        nums.all { it in 0..9 }
                            ) {
                                "排列五号码校验失败：$issue"
                            }

                            GameDraw(
                                gameCode = "pl5",
                                issue = issue,
                                date = date,
                                front = nums
                            )
                        }

                        else -> error("未知彩种：$gameCode")
                    }

                    if (!allDraws.containsKey(issue)) {
                        allDraws[issue] = draw
                        newCount++
                    }

                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "$gameCode 第${page}页第${i + 1}条记录解析失败，跳过",
                        e
                    )
                }
            }

            Log.i(
                TAG,
                "$gameCode 已读取第${page}页，本次新增候选${allDraws.size}期"
            )

            if (foundExistingIssue) {
                Log.i(
                    TAG,
                    "$gameCode 已遇到本地已有期号，增量同步结束"
                )
                break
            }

            if (newCount == 0) {
                Log.w(
                    TAG,
                    "$gameCode 第${page}页没有新增期号，停止分页"
                )
                break
            }

            if (list.length() < PAGE_SIZE) {
                Log.i(
                    TAG,
                    "$gameCode 已到达历史数据末尾"
                )
                break
            }
        }

        val parsed = allDraws.values.toList()

        if (parsed.isEmpty()) {
            if (existingIssues.isNotEmpty()) {
                Log.i(
                    TAG,
                    "$gameCode 没有新期号，无需更新"
                )
                return 0
            }

            error(
                "中国体彩网没有返回有效的${gameCode}开奖记录（游戏编号：$gameNo）"
            )
        }

        val savedCount = database.upsertGame(
            parsed,
            "lottery.gov.cn"
        )

        Log.i(
            TAG,
            "$gameCode 增量同步完成，写入/更新${savedCount}期"
        )

        return savedCount
    }
}

/**
 * 后台开奖同步任务。
 */
class DrawSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            runCatching {
                OfficialDrawSync(
                    LotteryDatabase(applicationContext)
                ).syncLatest()
            }.fold(
                onSuccess = {
                    Result.success()
                },
                onFailure = { error ->
                    Log.e(
                        "DrawSyncWorker",
                        "开奖同步任务失败，第${runAttemptCount + 1}次尝试",
                        error
                    )

                    if (runAttemptCount < 3) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
            )
        }
}

/**
 * 首次启动时立即安排一次全部彩种历史数据同步，
 * 同时安排每12小时一次的定时同步。
 */
fun scheduleDrawSync(context: Context) {

    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    // 首次同步：应用启动后联网执行。
    val initialRequest =
        OneTimeWorkRequestBuilder<DrawSyncWorker>()
            .setConstraints(constraints)
            .build()

    WorkManager.getInstance(context)
        .enqueueUniqueWork(
            "official-multi-lottery-initial-sync",
            ExistingWorkPolicy.KEEP,
            initialRequest
        )

    // 后续每12小时同步一次。
    val periodicRequest =
        PeriodicWorkRequestBuilder<DrawSyncWorker>(
            12,
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

    WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(
            "official-multi-lottery-sync",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest
        )
}