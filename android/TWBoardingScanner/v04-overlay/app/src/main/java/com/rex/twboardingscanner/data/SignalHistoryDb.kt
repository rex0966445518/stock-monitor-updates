package com.rex.twboardingscanner.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.rex.twboardingscanner.domain.SignalResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HistoryRow(
    val code:String,
    val name:String,
    val sector:String,
    val radar:String,
    val light:String,
    val score:Int,
    val reasons:String,
    val price:Double,
    val changePct:Double,
    val ts:Long,
    val scanDate:String,
    val rsi:Double?,
    val ma20:Double?,
    val volumeRatio:Double?,
    val eps:Double?,
    val macdPass:Boolean,
    val ma20Pass:Boolean,
    val rsiPass:Boolean,
    val volumePass:Boolean,
    val epsPass:Boolean?
)

class SignalHistoryDb(context: Context): SQLiteOpenHelper(context, "signals.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE signal_history(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            code TEXT, name TEXT, sector TEXT, radar TEXT, light TEXT, score INTEGER, reasons TEXT,
            price REAL, change_pct REAL, ts INTEGER, scan_date TEXT,
            rsi REAL, ma20 REAL, volume_ratio REAL, eps REAL,
            macd_pass INTEGER, ma20_pass INTEGER, rsi_pass INTEGER, volume_pass INTEGER, eps_pass INTEGER,
            UNIQUE(code,radar,light,reasons,scan_date)
        )""")
        db.execSQL("CREATE INDEX idx_signal_date ON signal_history(scan_date)")
        db.execSQL("CREATE INDEX idx_signal_code ON signal_history(code)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion:Int, newVersion:Int) {
        if (oldVersion < 3) {
            listOf(
                "ALTER TABLE signal_history ADD COLUMN rsi REAL",
                "ALTER TABLE signal_history ADD COLUMN ma20 REAL",
                "ALTER TABLE signal_history ADD COLUMN volume_ratio REAL",
                "ALTER TABLE signal_history ADD COLUMN eps REAL",
                "ALTER TABLE signal_history ADD COLUMN macd_pass INTEGER DEFAULT 0",
                "ALTER TABLE signal_history ADD COLUMN ma20_pass INTEGER DEFAULT 0",
                "ALTER TABLE signal_history ADD COLUMN rsi_pass INTEGER DEFAULT 0",
                "ALTER TABLE signal_history ADD COLUMN volume_pass INTEGER DEFAULT 0",
                "ALTER TABLE signal_history ADD COLUMN eps_pass INTEGER"
            ).forEach { runCatching { db.execSQL(it) } }
        }
    }

    @Synchronized
    fun insertIfNew(r: SignalResult): Boolean {
        val s = r.snapshot
        val reasons = r.reasons.joinToString("+")
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.TAIWAN).format(Date(s.timestamp))
        val vr = if (s.avg20VolumeLots > 0) s.volumeLots / s.avg20VolumeLots else null
        val macd = s.difRising && (s.macdGoldenCross || s.macdTurnedPositive || s.macdRedExpanding || s.macdNegBarsShrinking)
        val sql = """INSERT OR IGNORE INTO signal_history(
            code,name,sector,radar,light,score,reasons,price,change_pct,ts,scan_date,
            rsi,ma20,volume_ratio,eps,macd_pass,ma20_pass,rsi_pass,volume_pass,eps_pass
        ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""

        writableDatabase.compileStatement(sql).use { st ->
            st.bindString(1, r.code)
            st.bindString(2, r.name)
            st.bindString(3, r.sector.label)
            st.bindString(4, r.radarType.name)
            st.bindString(5, r.light.name)
            st.bindLong(6, r.score.toLong())
            st.bindString(7, reasons)
            st.bindDouble(8, s.price)
            st.bindDouble(9, s.changePct)
            st.bindLong(10, s.timestamp)
            st.bindString(11, date)
            bindNullableDouble(st, 12, s.rsi)
            bindNullableDouble(st, 13, s.ma20)
            bindNullableDouble(st, 14, vr)
            bindNullableDouble(st, 15, s.epsTtm)
            st.bindLong(16, if (macd) 1 else 0)
            st.bindLong(17, if (s.ma20?.let { s.price >= it } == true) 1 else 0)
            st.bindLong(18, if ((s.rsi ?: 0.0) > 50) 1 else 0)
            st.bindLong(19, if ((vr ?: 0.0) >= 1.2) 1 else 0)
            if (s.epsTtm == null) st.bindNull(20) else st.bindLong(20, if (s.epsTtm > 0) 1 else 0)
            return st.executeInsert() != -1L
        }
    }

    private fun bindNullableDouble(st: android.database.sqlite.SQLiteStatement, idx:Int, v:Double?) {
        if (v == null) st.bindNull(idx) else st.bindDouble(idx, v)
    }

    fun queryByDate(date:String):List<HistoryRow> = query("scan_date=?", arrayOf(date))
    fun queryByCode(code:String):List<HistoryRow> = query("code=?", arrayOf(code))

    private fun query(where:String, args:Array<String>):List<HistoryRow> {
        val rows = mutableListOf<HistoryRow>()
        readableDatabase.query("signal_history", null, where, args, null, null, "ts DESC").use { c ->
            while (c.moveToNext()) {
                fun d(name:String):Double? {
                    val i = c.getColumnIndex(name)
                    return if (i < 0 || c.isNull(i)) null else c.getDouble(i)
                }
                fun b(name:String):Boolean {
                    val i = c.getColumnIndex(name)
                    return i >= 0 && !c.isNull(i) && c.getInt(i) == 1
                }
                fun bn(name:String):Boolean? {
                    val i = c.getColumnIndex(name)
                    return if (i < 0 || c.isNull(i)) null else c.getInt(i) == 1
                }
                rows += HistoryRow(
                    c.getString(c.getColumnIndexOrThrow("code")),
                    c.getString(c.getColumnIndexOrThrow("name")),
                    c.getString(c.getColumnIndexOrThrow("sector")),
                    c.getString(c.getColumnIndexOrThrow("radar")),
                    c.getString(c.getColumnIndexOrThrow("light")),
                    c.getInt(c.getColumnIndexOrThrow("score")),
                    c.getString(c.getColumnIndexOrThrow("reasons")),
                    c.getDouble(c.getColumnIndexOrThrow("price")),
                    c.getDouble(c.getColumnIndexOrThrow("change_pct")),
                    c.getLong(c.getColumnIndexOrThrow("ts")),
                    c.getString(c.getColumnIndexOrThrow("scan_date")),
                    d("rsi"),
                    d("ma20"),
                    d("volume_ratio"),
                    d("eps"),
                    b("macd_pass"),
                    b("ma20_pass"),
                    b("rsi_pass"),
                    b("volume_pass"),
                    bn("eps_pass")
                )
            }
        }
        return rows
    }
}
