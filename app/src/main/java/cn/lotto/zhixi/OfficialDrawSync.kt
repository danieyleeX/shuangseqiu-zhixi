package cn.lotto.zhixi

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
    fun syncLatest(): Int {
        val home = "https://www.cwl.gov.cn/ygkj/wqkjgg/ssq/"
        val url = URL("https://www.cwl.gov.cn/cwl_admin/front/cwlkj/search/kjxx/findDrawNotice?name=ssq&issueCount=100&pageNo=1&pageSize=100&systemType=PC")
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
                    val reds = row.getString("red").split(',').map(String::trim).map(String::toInt).sorted()
                    val blue = row.getString("blue").trim().toInt()
                    require(row.getString("code").matches(Regex("\\d{7}")) && reds.size == 6 && reds.distinct().size == 6 && reds.all { it in 1..33 } && blue in 1..16) { "官方数据格式校验失败：${row.optString("code")}" }
                    add(Draw(row.getString("code"), row.getString("date").substringBefore('('), reds, blue))
                }
            }
            return database.upsert(parsed)
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
    WorkManager.getInstance(context).enqueueUniquePeriodicWork("official-ssq-sync", ExistingPeriodicWorkPolicy.KEEP, request)
}
