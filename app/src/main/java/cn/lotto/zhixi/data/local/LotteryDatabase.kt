
package cn.lotto.zhixi.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import cn.lotto.zhixi.domain.model.Draw
import cn.lotto.zhixi.domain.model.GameDraw
import cn.lotto.zhixi.domain.model.GeneratedRecord
import cn.lotto.zhixi.domain.model.Ticket
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.GZIPInputStream

class LotteryDatabase(
    context: Context
) : SQLiteOpenHelper(context, "shuangseqiu.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE draws (
                issue TEXT PRIMARY KEY NOT NULL,
                date TEXT NOT NULL,
                reds TEXT NOT NULL,
                blue INTEGER NOT NULL,
                source TEXT NOT NULL DEFAULT 'cwl.gov.cn'
            )
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX draws_date_idx ON draws(date DESC)")

        createGameDraws(db)
        createGeneratedHistory(db)
    }

    private fun createGameDraws(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS game_draws (
                game_code TEXT NOT NULL,
                issue TEXT NOT NULL,
                date TEXT NOT NULL,
                front TEXT NOT NULL,
                back TEXT NOT NULL DEFAULT '',
                source TEXT NOT NULL,
                PRIMARY KEY(game_code, issue)
            )
            """.trimIndent()
        )

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS game_draws_date_idx " +
                    "ON game_draws(game_code, date DESC)"
        )
    }

    private fun createGeneratedHistory(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS generated_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                game_code TEXT NOT NULL,
                issue TEXT NOT NULL,
                timestamp TEXT NOT NULL,
                mode TEXT NOT NULL,
                filter_summary TEXT NOT NULL,
                tickets_json TEXT NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS gen_hist_game_idx " +
                    "ON generated_history(game_code, id DESC)"
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        if (oldVersion < 2) {
            createGameDraws(db)

            db.execSQL(
                """
                INSERT OR IGNORE INTO game_draws
                (game_code, issue, date, front, back, source)
                SELECT 'ssq', issue, date, reds,
                       CAST(blue AS TEXT), 'cwl.gov.cn'
                FROM draws
                """.trimIndent()
            )
        }

        if (oldVersion < 3) {
            createGeneratedHistory(db)
        }
    }

    /**
     * 导入 assets 中的双色球历史数据。
     * 优先读取 JSON，失败后尝试读取 GZIP 文件。
     */
    fun importAssetIfEmpty(context: Context): Int {
        if (readAll().isNotEmpty()) return 0

        val text = runCatching {
            context.assets.open("ssq_history.json").bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrElse {
            GZIPInputStream(
                context.assets.open("ssq_history.json.gz")
            ).bufferedReader(Charsets.UTF_8).use {
                it.readText()
            }
        }

        val rows = JSONArray(text)
        val db = writableDatabase
        var imported = 0

        db.beginTransaction()

        try {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue

                val issue = row.optString("issue")
                val date = row.optString("date")
                val redsArray = row.optJSONArray("reds")
                val blue = row.optInt("blue", -1)

                if (issue.isBlank() || date.isBlank()) continue
                if (redsArray == null || redsArray.length() != 6) continue
                if (blue !in 1..16) continue

                val reds = mutableListOf<Int>()
                var valid = true

                for (j in 0 until redsArray.length()) {
                    val number = redsArray.optInt(j, -1)
                    if (number !in 1..33) {
                        valid = false
                        break
                    }
                    reds.add(number)
                }

                if (!valid || reds.distinct().size != 6) continue

                val redText = reds.sorted().joinToString(",")

                val values = ContentValues().apply {
                    put("issue", issue)
                    put("date", date)
                    put("reds", redText)
                    put("blue", blue)
                    put("source", "cwl.gov.cn")
                }

                val inserted = db.insertWithOnConflict(
                    "draws",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE
                )

                if (inserted != -1L) {
                    val gameValues = ContentValues().apply {
                        put("game_code", "ssq")
                        put("issue", issue)
                        put("date", date)
                        put("front", redText)
                        put("back", blue.toString())
                        put("source", "cwl.gov.cn")
                    }

                    db.insertWithOnConflict(
                        "game_draws",
                        null,
                        gameValues,
                        SQLiteDatabase.CONFLICT_IGNORE
                    )

                    imported++
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return imported
    }

    /**
     * 写入双色球开奖数据。
     */
    fun upsert(draws: List<Draw>): Int {
        val db = writableDatabase
        var changed = 0

        db.beginTransaction()

        try {
            draws.forEach { draw ->
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO
                    draws(issue, date, reds, blue, source)
                    VALUES(?,?,?,?,?)
                    """.trimIndent(),
                    arrayOf(
                        draw.issue,
                        draw.date,
                        draw.reds.joinToString(","),
                        draw.blue,
                        "cwl.gov.cn"
                    )
                )

                changed++
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return changed
    }

    /**
     * 读取双色球历史开奖。
     * 返回顺序：最新日期在前。
     */
    fun readAll(): List<Draw> {
        val output = ArrayList<Draw>()

        readableDatabase.rawQuery(
            """
            SELECT issue, date, reds, blue
            FROM draws
            ORDER BY date DESC, issue DESC
            """.trimIndent(),
            null
        ).use { cursor ->

            while (cursor.moveToNext()) {
                val issue = cursor.getString(0)
                val date = cursor.getString(1)
                val reds = parseNumbers(cursor.getString(2))
                val blue = cursor.getInt(3)

                if (reds.size != 6 || blue !in 1..16) continue
                if (reds.any { it !in 1..33 }) continue

                output.add(
                    Draw(
                        issue = issue,
                        date = date,
                        reds = reds,
                        blue = blue
                    )
                )
            }
        }

        return output
    }

    /**
     * 写入其他彩种历史开奖。
     */
    fun upsertGame(
        draws: List<GameDraw>,
        source: String
    ): Int {
        val db = writableDatabase
        var changed = 0

        db.beginTransaction()

        try {
            draws.forEach { draw ->
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO
                    game_draws(game_code, issue, date, front, back, source)
                    VALUES(?,?,?,?,?,?)
                    """.trimIndent(),
                    arrayOf(
                        draw.gameCode,
                        draw.issue,
                        draw.date,
                        draw.front.joinToString(","),
                        draw.back.joinToString(","),
                        source
                    )
                )

                changed++
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return changed
    }

    /**
     * 读取指定彩种历史开奖。
     * 返回顺序：最新日期在前。
     */
    fun readGame(gameCode: String): List<GameDraw> {
        val output = mutableListOf<GameDraw>()

        readableDatabase.rawQuery(
            """
            SELECT game_code, issue, date, front, back
            FROM game_draws
            WHERE game_code = ?
            ORDER BY date DESC, issue DESC
            """.trimIndent(),
            arrayOf(gameCode)
        ).use { cursor ->

            while (cursor.moveToNext()) {
                val front = parseNumbers(cursor.getString(3))
                val back = parseNumbers(cursor.getString(4))

                output.add(
                    GameDraw(
                        gameCode = cursor.getString(0),
                        issue = cursor.getString(1),
                        date = cursor.getString(2),
                        front = front,
                        back = back
                    )
                )
            }
        }

        return output
    }

    /**
     * 保存生成记录。
     */
    fun insertGeneratedRecord(record: GeneratedRecord) {
        val db = writableDatabase
        val jsonArray = JSONArray()

        record.tickets.forEach { ticket ->
            jsonArray.put(
                JSONObject().apply {
                    put("reds", JSONArray(ticket.reds))
                    put("blue", ticket.blue)
                    put("score", ticket.score)
                    put("gameCode", ticket.gameCode)
                    put("secondary", JSONArray(ticket.secondary))
                }
            )
        }

        db.execSQL(
            """
            INSERT INTO generated_history
            (game_code, issue, timestamp, mode, filter_summary, tickets_json)
            VALUES(?,?,?,?,?,?)
            """.trimIndent(),
            arrayOf(
                record.gameCode,
                record.issue,
                record.timestamp,
                record.mode,
                record.filterSummary,
                jsonArray.toString()
            )
        )
    }

    /**
     * 读取生成记录。
     */
    fun readGeneratedRecords(
        gameCode: String,
        limit: Int = 30
    ): List<GeneratedRecord> {
        val safeLimit = limit.coerceIn(1, 500)
        val output = mutableListOf<GeneratedRecord>()

        readableDatabase.rawQuery(
            """
            SELECT id, game_code, issue, timestamp,
                   mode, filter_summary, tickets_json
            FROM generated_history
            WHERE game_code = ?
            ORDER BY id DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(gameCode, safeLimit.toString())
        ).use { cursor ->

            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val code = cursor.getString(1)
                val issue = cursor.getString(2)
                val timestamp = cursor.getString(3)
                val mode = cursor.getString(4)
                val summary = cursor.getString(5)
                val jsonString = cursor.getString(6)

                val tickets = mutableListOf<Ticket>()

                runCatching {
                    val array = JSONArray(jsonString)

                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        val redsArray = obj.optJSONArray("reds") ?: continue

                        val reds = mutableListOf<Int>()

                        for (j in 0 until redsArray.length()) {
                            reds.add(redsArray.getInt(j))
                        }

                        val blue = obj.optInt("blue", 0)
                        val score = obj.optInt("score", 75)
                        val gameCodeValue = obj.optString("gameCode", code)

                        val secondaryArray = obj.optJSONArray("secondary")
                        val secondary = if (secondaryArray != null) {
                            (0 until secondaryArray.length()).map {
                                secondaryArray.getInt(it)
                            }
                        } else {
                            listOf(blue)
                        }

                        tickets.add(
                            Ticket(
                                reds = reds,
                                blue = blue,
                                score = score,
                                gameCode = gameCodeValue,
                                secondary = secondary
                            )
                        )
                    }
                }

                output.add(
                    GeneratedRecord(
                        id = id,
                        gameCode = code,
                        issue = issue,
                        timestamp = timestamp,
                        mode = mode,
                        filterSummary = summary,
                        tickets = tickets
                    )
                )
            }
        }

        return output
    }

    /**
     * 清除指定彩种的生成记录。
     */
    fun clearGeneratedRecords(gameCode: String) {
        writableDatabase.execSQL(
            "DELETE FROM generated_history WHERE game_code = ?",
            arrayOf(gameCode)
        )
    }

    /**
     * 安全解析逗号分隔的号码。
     */
    private fun parseNumbers(value: String?): List<Int> {
        if (value.isNullOrBlank()) return emptyList()

        val result = mutableListOf<Int>()

        for (part in value.split(',')) {
            val number = part.trim().toIntOrNull() ?: return emptyList()
            result.add(number)
        }

        return result
    }
}