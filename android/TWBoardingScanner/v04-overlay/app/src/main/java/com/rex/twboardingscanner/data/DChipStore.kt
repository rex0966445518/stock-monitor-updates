package com.rex.twboardingscanner.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.rex.twboardingscanner.domain.DChipDay
import com.rex.twboardingscanner.domain.RuleMetrics
import java.io.InputStream
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Local, source-labelled D-chip observations. No ownership ratio, broker
 * concentration, or institutional buy/sell is derived from price/volume.
 *
 * Source must be an authorised DAILY feed. TDCC holdings are weekly and must
 * not be relabelled as "today's big-holder percentage".
 *
 * CSV columns: date,code,bigHolderPct,mainForce5Pct,foreignNetShares,
 *              trustNetShares,asOf,source
 * Blank optional numeric fields are pending, not zero.
 */
class DChipStore(context:Context){
    private val app=context.applicationContext
    private val prefs=app.getSharedPreferences("d-chip-evidence-v1",Context.MODE_PRIVATE)
    private class Database(c:Context):SQLiteOpenHelper(c,"d-chip-evidence-v1.db",null,1){
        override fun onCreate(db:SQLiteDatabase){
            db.execSQL("CREATE TABLE daydata(day TEXT NOT NULL,code TEXT NOT NULL,"+
                "holder REAL,main5 REAL,foreignbuy INTEGER,trustbuy INTEGER,"+
                "asof TEXT NOT NULL,source TEXT NOT NULL,PRIMARY KEY(day,code))")
        }
        override fun onUpgrade(db:SQLiteDatabase,from:Int,to:Int){}
    }
    companion object {
        private val mutex=Any()
        private var helper:Database?=null
        private fun db(c:Context)=synchronized(mutex){
            (helper?:Database(c).also{helper=it}).writableDatabase
        }
        const val EXAMPLE="date,code,bigHolderPct,mainForce5Pct,foreignNetShares,trustNetShares,asOf,source\n"+
            "2026-10-21,2330,45.12,8.2,1200000,280000,13:30:00,licensed-daily-feed\n"+
            "2026-10-22,2330,45.14,8.5,800000,-30000,13:30:00,licensed-daily-feed"
        private fun csvLine(line:String):List<String>{
            val out=mutableListOf<String>()
            val cur=StringBuilder()
            var inQuotes=false
            var i=0
            while(i<line.length){
                when(val ch=line[i]){
                    '"'->if(inQuotes&&i+1<line.length&&line[i+1]=='"'){
                        cur.append('"');i++
                    }else inQuotes=!inQuotes
                    ','->if(inQuotes)cur.append(ch)else{out+=cur.toString().trim();cur.setLength(0)}
                    else->cur.append(ch)
                }
                i++
            }
            require(!inQuotes){"CSV 引號未關閉"}
            out+=cur.toString().trim()
            return out
        }
        fun parseCsv(raw:String,
            today:LocalDate=LocalDate.now(RuleMetrics.TAIPEI),
            now:LocalTime=LocalTime.now(RuleMetrics.TAIPEI)
        ):Map<Pair<LocalDate,String>,DChipDay>{
            require(raw.length in 1..16_000_000){"CSV 檔案為空白或過大"}
            val lines=raw.lineSequence().map{it.trimEnd('\r')}.filter{it.isNotBlank()}.iterator()
            require(lines.hasNext()){"CSV 是空白"}
            val columns=csvLine(lines.next().removePrefix("\uFEFF")).map{it.lowercase()}
            val required=listOf("date","code","bigholderpct","mainforce5pct",
                "foreignnetshares","trustnetshares","asof","source")
            require(required.all{it in columns}&&columns.toSet().size==columns.size) {
                "必須包含 date,code,bigHolderPct,mainForce5Pct,foreignNetShares,trustNetShares,asOf,source 八欄"
            }
            val keys=required.map{columns.indexOf(it)}
            val result=linkedMapOf<Pair<LocalDate,String>,DChipDay>()
            var index=0
            while(lines.hasNext()){
                index++;require(index<=100_000){"單次最多匯入 100,000 筆"}
                val row=csvLine(lines.next())
                require(row.size==columns.size){"第${index}列欄位數不符"}
                fun field(i:Int)=row[keys[i]]
                val date=LocalDate.parse(field(0))
                require(date in LocalDate.of(2016,1,1)..today){"第${index}列日期不合理"}
                val code=field(1)
                require(code.matches(Regex("[1-9][0-9]{3}"))){"第${index}列股號需是四位數"}
                fun num(i:Int):Double?=field(i).takeIf{it.isNotBlank()}?.toDoubleOrNull()
                    ?:if(field(i).isBlank())null else throw IllegalArgumentException("第${index}列百分比不是數字")
                fun volume(i:Int):Long?=field(i).takeIf{it.isNotBlank()}?.toLongOrNull()
                    ?:if(field(i).isBlank())null else throw IllegalArgumentException("第${index}列買賣超需為整數股數")
                val asOf=LocalTime.parse(field(6))
                if(date==today)require(!asOf.isAfter(now)){"第${index}列資料時間在未來"}
                val sample=DChipDay(date,num(2),num(3),volume(4),volume(5),asOf,field(7))
                val key=date to code
                val previous=result[key]
                if(previous==null||previous.asOf<=asOf)result[key]=sample
            }
            require(result.isNotEmpty()){"CSV 沒有資料列"}
            return result
        }
        private fun get(rows:android.database.Cursor,date:LocalDate):DChipDay?=runCatching{
            DChipDay(date,
                if(rows.isNull(0))null else rows.getDouble(0),
                if(rows.isNull(1))null else rows.getDouble(1),
                if(rows.isNull(2))null else rows.getLong(2),
                if(rows.isNull(3))null else rows.getLong(3),
                LocalTime.parse(rows.getString(4)),rows.getString(5))
        }.getOrNull()
    }
    fun revision()=prefs.getString("revision","none").orEmpty()
    fun read(code:String,date:LocalDate,completed:Boolean=false):DChipDay?=synchronized(mutex){
        if(!code.matches(Regex("[1-9][0-9]{3}")))return@synchronized null
        db(app).rawQuery("SELECT holder,main5,foreignbuy,trustbuy,asof,source FROM daydata WHERE day=? AND code=?",
            arrayOf(date.toString(),code)).use{rows->
            if(!rows.moveToFirst())null else get(rows,date)?.takeIf{!completed||it.completeDay}
        }
    }
    /** Read per-code batch for backtesting without one SQL query per candle. */
    fun forCode(code:String,start:LocalDate,end:LocalDate,completed:Boolean=true):Map<LocalDate,DChipDay> = synchronized(mutex){
        if(!code.matches(Regex("[1-9][0-9]{3}")))return@synchronized emptyMap()
        val rows=linkedMapOf<LocalDate,DChipDay>()
        db(app).rawQuery(
            "SELECT day,holder,main5,foreignbuy,trustbuy,asof,source FROM daydata WHERE code=? AND day>=? AND day<=? ORDER BY day",
            arrayOf(code,start.toString(),end.toString())
        ).use{data->
            while(data.moveToNext()){
                val date=runCatching{LocalDate.parse(data.getString(0))}.getOrNull()?:continue
                val sample=runCatching{
                    DChipDay(date,
                        if(data.isNull(1))null else data.getDouble(1),
                        if(data.isNull(2))null else data.getDouble(2),
                        if(data.isNull(3))null else data.getLong(3),
                        if(data.isNull(4))null else data.getLong(4),
                        LocalTime.parse(data.getString(5)),data.getString(6))
                }.getOrNull()?:continue
                if(!completed||sample.completeDay)rows[date]=sample
            }
        }
        rows
    }
    fun importCsv(stream:InputStream):Int{
        val contents=stream.bufferedReader(Charsets.UTF_8).use{it.readText()}
        val rows=parseCsv(contents)
        val written=synchronized(mutex){
            val database=db(app)
            database.beginTransaction()
            try{
                var count=0
                rows.forEach{(key,sample)->
                    val old=read(key.second,key.first)
                    if(old!=null&&old.asOf.isAfter(sample.asOf))return@forEach
                    val record=ContentValues().apply{
                        put("day",key.first.toString());put("code",key.second)
                        if(sample.bigHolderPct==null)putNull("holder")else put("holder",sample.bigHolderPct)
                        if(sample.mainForce5Pct==null)putNull("main5")else put("main5",sample.mainForce5Pct)
                        if(sample.foreignNetShares==null)putNull("foreignbuy")else put("foreignbuy",sample.foreignNetShares)
                        if(sample.trustNetShares==null)putNull("trustbuy")else put("trustbuy",sample.trustNetShares)
                        put("asof",sample.asOf.toString());put("source",sample.source)
                    }
                    check(database.insertWithOnConflict("daydata",null,record,SQLiteDatabase.CONFLICT_REPLACE)>0)
                    count++
                }
                database.setTransactionSuccessful()
                count
            }finally{database.endTransaction()}
        }
        if(written>0){
            check(prefs.edit().putString("revision",UUID.randomUUID().toString()).commit()){"資料修訂版本保存失敗"}
            // Never let old backtest/robot batches silently mix two chip revisions.
            runCatching {
                val bt=com.rex.twboardingscanner.backtest.BacktestStore(app)
                val active=bt.active()
                if(active.isNotEmpty())bt.update(active,"D 籌碼來源已更新，請建立新回測快照；舊日誌保留","CANCELED")
                val robot=com.rex.twboardingscanner.backtest.BtRobotStore(app)
                if(robot.active().isNotEmpty())
                    com.rex.twboardingscanner.backtest.BtRobotWorker.pause(app,robot.active())
            }
        }
        return written
    }
}
