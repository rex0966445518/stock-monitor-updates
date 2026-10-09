package com.rex.twboardingscanner.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.rex.twboardingscanner.domain.SignalResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.rex.twboardingscanner.domain.DailyBar
import org.json.JSONArray
import org.json.JSONObject
import com.rex.twboardingscanner.domain.ClosingAuction

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
    val epsPass:Boolean?,
    val ruleReport:String = "",
    val chartBars:List<DailyBar> = emptyList(),
    val closingAuction:ClosingAuction? = null
)

class SignalHistoryDb(context: Context): SQLiteOpenHelper(context, "signals.db", null, 6) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE signal_history(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            code TEXT, name TEXT, sector TEXT, radar TEXT, light TEXT, score INTEGER, reasons TEXT,
            price REAL, change_pct REAL, ts INTEGER, scan_date TEXT,
            rsi REAL, ma20 REAL, volume_ratio REAL, eps REAL,
            macd_pass INTEGER, ma20_pass INTEGER, rsi_pass INTEGER, volume_pass INTEGER, eps_pass INTEGER,
            rule_report TEXT, chart_bars TEXT, closing_auction TEXT,
            UNIQUE(code,radar,light,reasons,scan_date)
        )""")
        db.execSQL("CREATE INDEX idx_signal_date ON signal_history(scan_date)")
        db.execSQL("CREATE INDEX idx_signal_code ON signal_history(code)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion:Int, newVersion:Int) {
        if(oldVersion<6) db.execSQL("ALTER TABLE signal_history ADD COLUMN closing_auction TEXT")
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
        if (oldVersion < 4) db.execSQL("ALTER TABLE signal_history ADD COLUMN rule_report TEXT")
        if (oldVersion < 5) db.execSQL("ALTER TABLE signal_history ADD COLUMN chart_bars TEXT")
    }

    @Synchronized
    fun insertIfNew(r: SignalResult): Boolean {
        val s = r.snapshot
        val reasons = "v0.4.8｜日K " + r.snapshot.bars.lastOrNull()?.let { com.rex.twboardingscanner.domain.RuleMetrics.tradingDate(it.time) }.toString() + "｜" + r.reasons.joinToString("；")
        val report = (s.sourceStock?.financials?.summary(com.rex.twboardingscanner.domain.RuleMetrics(s).today)?.plus("\n") ?: "") + r.checks.filter { it.selected || it.extra }.joinToString("\n") { check ->
            val state = when(check.state) {
                com.rex.twboardingscanner.domain.CheckState.PASS -> "通過"
                com.rex.twboardingscanner.domain.CheckState.FAIL -> "未通過"
                com.rex.twboardingscanner.domain.CheckState.PENDING -> "待查核"
            }
            "${check.label}：$state${if (check.selected) "" else "（未啟用）"}"
        }
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.TAIWAN).apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Taipei") }.format(Date(s.timestamp))
        val vr = if (s.avg20VolumeLots > 0) s.volumeLots / s.avg20VolumeLots else null
        val metrics = com.rex.twboardingscanner.domain.RuleMetrics(s)
        val macd = metrics.macdUp == true
        val sql = """INSERT OR IGNORE INTO signal_history(
            code,name,sector,radar,light,score,reasons,price,change_pct,ts,scan_date,
            rsi,ma20,volume_ratio,eps,macd_pass,ma20_pass,rsi_pass,volume_pass,eps_pass,rule_report,chart_bars,closing_auction
        ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""

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
            st.bindLong(17, if (s.ma20?.let { s.price > it } == true) 1 else 0)
            st.bindLong(18, if ((s.rsi ?: 0.0) > 50) 1 else 0)
            st.bindLong(19, if (metrics.volumeAtLeast(if (r.radarType == com.rex.twboardingscanner.domain.RadarType.C_LONG_RED_VOLUME) 2.0 else 1.5) == true) 1 else 0)
            if (metrics.earnings == null) st.bindNull(20) else st.bindLong(20, if (metrics.earnings == true) 1 else 0)
            st.bindString(21, report)
            val savedBars=JSONArray()
            s.bars.forEach { b -> savedBars.put(JSONArray().put(b.time).put(b.open).put(b.high).put(b.low).put(b.close).put(b.volumeShares)) }
            st.bindString(22, savedBars.toString())
            st.bindString(23,encodeAuction(s.sourceStock?.closingAuction))
            val inserted=st.executeInsert()!=-1L
            if(!inserted && s.sourceStock?.closingAuction?.lots!=null) {
                val values=android.content.ContentValues().apply {put("closing_auction",encodeAuction(s.sourceStock?.closingAuction))}
                writableDatabase.update("signal_history",values,"code=? AND radar=? AND scan_date=?",arrayOf(r.code,r.radarType.name,date))
            }
            return inserted
        }
    }

    private fun encodeAuction(a:ClosingAuction?):String = if(a==null) "" else JSONObject().apply {
        put("date",a.date);put("time",a.time);put("lots",a.lots);put("price",a.price);put("before",a.beforePrice);put("note",a.note)
    }.toString()
    private fun decodeAuction(raw:String?):ClosingAuction? = runCatching {
        val j=JSONObject(raw ?: "");ClosingAuction(j.getString("date"),j.getString("time"),
            j.optString("lots").toLongOrNull(),j.optString("price").toDoubleOrNull(),j.optString("before").toDoubleOrNull(),j.optString("note"))
    }.getOrNull()

    private fun decodeBars(raw: String?): List<DailyBar> = runCatching {
        val array=JSONArray(raw ?: "[]")
        (0 until array.length()).map { i ->
            val b=array.getJSONArray(i)
            DailyBar(b.getLong(0),b.getDouble(1),b.getDouble(2),b.getDouble(3),b.getDouble(4),b.getLong(5))
        }
    }.getOrDefault(emptyList())

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
                    bn("eps_pass"),
                    c.getString(c.getColumnIndexOrThrow("rule_report")) ?: "",
                    decodeBars(c.getString(c.getColumnIndexOrThrow("chart_bars"))),
                    decodeAuction(c.getString(c.getColumnIndexOrThrow("closing_auction")))
                )
            }
        }
        return rows
    }
}
