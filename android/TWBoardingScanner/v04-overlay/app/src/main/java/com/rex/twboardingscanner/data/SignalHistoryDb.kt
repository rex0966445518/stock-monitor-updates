package com.rex.twboardingscanner.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.rex.twboardingscanner.domain.SignalResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HistoryRow(
    val code: String, val name: String, val sector: String, val radar: String, val light: String,
    val score: Int, val reasons: String, val price: Double, val changePct: Double, val ts: Long, val scanDate: String
)

class SignalHistoryDb(context: Context): SQLiteOpenHelper(context, "signals.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE signal_history(id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT, name TEXT, sector TEXT, radar TEXT, light TEXT, score INTEGER, reasons TEXT, price REAL, change_pct REAL, ts INTEGER, scan_date TEXT, UNIQUE(code,radar,light,reasons,scan_date))")
        db.execSQL("CREATE INDEX idx_signal_date ON signal_history(scan_date)")
        db.execSQL("CREATE INDEX idx_signal_code ON signal_history(code)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS signal_history")
            onCreate(db)
        }
    }

    @Synchronized fun insertIfNew(r: SignalResult): Boolean {
        val reasons = r.reasons.joinToString("+")
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.TAIWAN).format(Date(r.snapshot.timestamp))
        val sql = "INSERT OR IGNORE INTO signal_history(code,name,sector,radar,light,score,reasons,price,change_pct,ts,scan_date) VALUES(?,?,?,?,?,?,?,?,?,?,?)"
        writableDatabase.compileStatement(sql).use { st ->
            st.bindString(1, r.code); st.bindString(2, r.name); st.bindString(3, r.sector.label)
            st.bindString(4, r.radarType.name); st.bindString(5, r.light.name); st.bindLong(6, r.score.toLong())
            st.bindString(7, reasons); st.bindDouble(8, r.snapshot.price); st.bindDouble(9, r.snapshot.changePct)
            st.bindLong(10, r.snapshot.timestamp); st.bindString(11, date)
            return st.executeInsert() != -1L
        }
    }

    fun queryByDate(date: String): List<HistoryRow> = query("scan_date=?", arrayOf(date))
    fun queryByCode(code: String): List<HistoryRow> = query("code=?", arrayOf(code))

    private fun query(where: String, args: Array<String>): List<HistoryRow> {
        val rows = mutableListOf<HistoryRow>()
        readableDatabase.query("signal_history", null, where, args, null, null, "ts DESC").use { c ->
            while (c.moveToNext()) {
                rows += HistoryRow(
                    c.getString(c.getColumnIndexOrThrow("code")), c.getString(c.getColumnIndexOrThrow("name")),
                    c.getString(c.getColumnIndexOrThrow("sector")), c.getString(c.getColumnIndexOrThrow("radar")),
                    c.getString(c.getColumnIndexOrThrow("light")), c.getInt(c.getColumnIndexOrThrow("score")),
                    c.getString(c.getColumnIndexOrThrow("reasons")), c.getDouble(c.getColumnIndexOrThrow("price")),
                    c.getDouble(c.getColumnIndexOrThrow("change_pct")), c.getLong(c.getColumnIndexOrThrow("ts")),
                    c.getString(c.getColumnIndexOrThrow("scan_date"))
                )
            }
        }
        return rows
    }
}
