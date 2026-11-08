package cn.lotto.zhixi.data.remote

import cn.lotto.zhixi.data.local.LotteryDatabase
import cn.lotto.zhixi.domain.engine.LotteryEngine
import cn.lotto.zhixi.domain.model.*

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class OfficialDrawSync(private val database: LotteryDatabase) {
    fun syncLatest(): Int = syncAll()
    fun syncAll(): Int {
        var total = 0
        total += syncWelfare("ssq", "ssq")
        total += syncWelfare("fc3d", "3d")
        total += syncSport("dlt", "85")
        total += syncSport("pl3", "35")
        total += syncSport("pl5", "350133")
        return total
    }
    private fun syncWelfare(gameCode: String, apiName: String): Int {
        val home = "https://www.cwl.gov.cn/ygkj/wqkjgg/ssq/"
        val url = URL("https://www.cwl.gov.cn/cwl_admin/front/cwlkj/search/kjxx/findDrawNotice?name=$apiName&issueCount=100&pageNo=1&pageSize=100&systemType=PC")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Referer", home)
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) error("中国福彩网返回 HTTP $status")
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(body)
            if (root.optInt("state", -1) != 0) error("中国福彩网接口：${root.optString("message", "未知错误")}")
            val rows = root.getJSONArray("result")
            val parsed = buildList {
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val issue = row.getString("code")
                    val date = row.getString("date").substringBefore('(')
                    if (gameCode == "ssq") {
                        val reds = row.getString("red").split(',').map(String::trim).map(String::toInt).sorted()
                        val blue = row.getString("blue").trim().toInt()
                        require(issue.matches(Regex("\\d{7}")) && reds.size == 6 && reds.distinct().size == 6 && reds.all { it in 1..33 } && blue in 1..16) { "双色球官方数据校验失败：$issue" }
                        add(GameDraw("ssq", issue, date, reds, listOf(blue)))
                    } else {
                        val digits = row.optString("red", row.optString("result")).filter(Char::isDigit).map { it.digitToInt() }
                        require(issue.isNotBlank() && digits.size == 3) { "福彩3D官方数据格式校验失败：$issue" }
                        add(GameDraw("fc3d", issue, date, digits))
                    }
                }
            }
            if (gameCode == "ssq") database.upsert(parsed.map { Draw(it.issue,it.date,it.front,it.back.first()) })
            return database.upsertGame(parsed, "cwl.gov.cn")
        } finally { connection.disconnect() }
    }
    private fun syncSport(gameCode: String, gameNo: String): Int {
        val url = URL("https://webapi.sporttery.cn/gateway/lottery/getHistoryPageListV1.qry?gameNo=$gameNo&provinceId=0&isVerify=1&pageNo=1&pageSize=100&termLimits=100")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Referer", "https://m.lottery.gov.cn/zst/tbsj/")
        }
        try {
            if (connection.responseCode !in 200..299) error("中国体彩网返回 HTTP ${connection.responseCode}")
            val root = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val list = root.optJSONObject("value")?.optJSONArray("list") ?: error("中国体彩网没有返回${gameCode}开奖列表")
            val rows = buildList {
                for (i in 0 until list.length()) {
                    val row = list.getJSONObject(i)
                    val raw = row.optString("lotteryDrawResult").trim()
                    val nums = Regex("\\d+").findAll(raw).map { it.value.toInt() }.toList()
                    val frontCount = if (gameCode == "dlt") 5 else if (gameCode == "pl3") 3 else 5
                    val expected = if (gameCode == "dlt") 7 else frontCount
                    if (nums.size < expected) continue
                    add(GameDraw(gameCode, row.optString("lotteryDrawNum"), row.optString("lotteryDrawTime").take(10), nums.take(frontCount), nums.drop(frontCount).take(if (gameCode == "dlt") 2 else 0)))
                }
            }
            require(rows.isNotEmpty()) { "中国体彩网${gameCode}开奖数据为空（游戏编号 $gameNo），请检查官方接口" }
            return database.upsertGame(rows, "lottery.gov.cn")
        } finally { connection.disconnect() }
    }
}

class DrawSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching { OfficialDrawSync(LotteryDatabase(applicationContext)).syncLatest() }.fold({ Result.success() }, { if (runAttemptCount < 3) Result.retry() else Result.failure() })
    }
}

fun scheduleDrawSync(context: Context) {
    val request = PeriodicWorkRequestBuilder<DrawSyncWorker>(12, TimeUnit.HOURS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork("official-multi-lottery-sync", ExistingPeriodicWorkPolicy.KEEP, request)
}

