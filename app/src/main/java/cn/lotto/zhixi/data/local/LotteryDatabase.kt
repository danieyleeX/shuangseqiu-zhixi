package cn.lotto.zhixi.data.local

import cn.lotto.zhixi.domain.model.Draw
import cn.lotto.zhixi.domain.model.GameDraw

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.util.zip.GZIPInputStream

class LotteryDatabase(context: Context) : SQLiteOpenHelper(context, "shuangseqiu.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE draws (issue TEXT PRIMARY KEY NOT NULL, date TEXT NOT NULL, reds TEXT NOT NULL, blue INTEGER NOT NULL, source TEXT NOT NULL DEFAULT 'cwl.gov.cn')")
        db.execSQL("CREATE INDEX draws_date_idx ON draws(date DESC)")
        createGameDraws(db)
    }
    private fun createGameDraws(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS game_draws (game_code TEXT NOT NULL, issue TEXT NOT NULL, date TEXT NOT NULL, front TEXT NOT NULL, back TEXT NOT NULL DEFAULT '', source TEXT NOT NULL, PRIMARY KEY(game_code,issue))")
        db.execSQL("CREATE INDEX IF NOT EXISTS game_draws_date_idx ON game_draws(game_code,date DESC)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createGameDraws(db)
            db.execSQL("INSERT OR IGNORE INTO game_draws(game_code,issue,date,front,back,source) SELECT 'ssq',issue,date,reds,CAST(blue AS TEXT),'cwl.gov.cn' FROM draws")
        }
    }

    fun importAssetIfEmpty(context: Context): Int {
        if (readAll().isNotEmpty()) return 0
        // Android Gradle unpacks .gz assets and stores them without the .gz suffix.
        val text = runCatching {
            context.assets.open("ssq_history.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse {
            context.assets.open("ssq_history.json.gz").use { input ->
                GZIPInputStream(input).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        }
        val rows = JSONArray(text)
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                db.execSQL("INSERT OR IGNORE INTO draws(issue,date,reds,blue,source) VALUES(?,?,?,?,?)", arrayOf(row.getString("issue"), row.getString("date"), (0 until row.getJSONArray("reds").length()).joinToString(",") { row.getJSONArray("reds").getInt(it).toString() }, row.getInt("blue"), "cwl.gov.cn"))
                db.execSQL("INSERT OR IGNORE INTO game_draws(game_code,issue,date,front,back,source) VALUES('ssq',?,?,?,?,?)", arrayOf(row.getString("issue"), row.getString("date"), (0 until row.getJSONArray("reds").length()).joinToString(",") { row.getJSONArray("reds").getInt(it).toString() }, row.getInt("blue").toString(), "cwl.gov.cn"))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return rows.length()
    }

    fun upsert(draws: List<Draw>): Int {
        val db = writableDatabase
        var changed = 0
        db.beginTransaction()
        try {
            draws.forEach { draw ->
                db.execSQL("INSERT OR REPLACE INTO draws(issue,date,reds,blue,source) VALUES(?,?,?,?,?)", arrayOf<Any>(draw.issue, draw.date, draw.reds.joinToString(","), draw.blue, "cwl.gov.cn"))
                changed++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return changed
    }

    fun readAll(): List<Draw> {
        val output = ArrayList<Draw>()
        readableDatabase.rawQuery("SELECT issue,date,reds,blue FROM draws ORDER BY issue DESC", null).use { cursor ->
            while (cursor.moveToNext()) output += Draw(cursor.getString(0), cursor.getString(1), cursor.getString(2).split(',').map(String::toInt), cursor.getInt(3))
        }
        return output
    }

    fun upsertGame(draws: List<GameDraw>, source: String): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            draws.forEach { d -> db.execSQL("INSERT OR REPLACE INTO game_draws(game_code,issue,date,front,back,source) VALUES(?,?,?,?,?,?)", arrayOf(d.gameCode,d.issue,d.date,d.front.joinToString(","),d.back.joinToString(","),source)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return draws.size
    }
    fun readGame(gameCode: String): List<GameDraw> {
        val out = mutableListOf<GameDraw>()
        readableDatabase.rawQuery("SELECT game_code,issue,date,front,back FROM game_draws WHERE game_code=? ORDER BY issue DESC", arrayOf(gameCode)).use { c ->
            while (c.moveToNext()) out += GameDraw(c.getString(0),c.getString(1),c.getString(2),c.getString(3).split(',').filter(String::isNotBlank).map(String::toInt),c.getString(4).split(',').filter(String::isNotBlank).map(String::toInt))
        }
        return out
    }
}





