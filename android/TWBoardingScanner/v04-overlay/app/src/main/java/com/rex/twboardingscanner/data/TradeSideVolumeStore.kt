package com.rex.twboardingscanner.data

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.rex.twboardingscanner.domain.*
import java.io.InputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Verified-source IMPORT of aggressive EXECUTED volume. No snapshot is synthesized from
 * TWSE five-level bid/ask order book, total daily volume, or up/down candles.
 *
 * CSV required headers:
 * date,code,tradeVolumeAtAsk,tradeVolumeAtBid,asOf,source
 * 2026-10-09,2330,150000,90000,13:30:00,broker-export
 *
 * Unit must be identical for buy/sell, preferably SHARES. Ratios are unit-independent.
 * Older 09:xx snapshots remain valid for live intraday scans, but historical EOD
 * backtesting requires a complete >=13:25 observation.
 */
class TradeSideVolumeStore(context:Context) {
    private val c=context.applicationContext
    private class Db(context:Context):SQLiteOpenHelper(context,"trade-side-volume-v1.db",null,1){
        override fun onCreate(db:SQLiteDatabase){
            db.execSQL("CREATE TABLE flows(day TEXT NOT NULL,code TEXT NOT NULL,buy INTEGER NOT NULL,sell INTEGER NOT NULL,asof TEXT NOT NULL,source TEXT NOT NULL,PRIMARY KEY(day,code))")
        }
        override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){}
    }
    companion object {
        private val lock=Any()
        private var helper:Db?=null
        private fun db(c:Context):SQLiteDatabase=synchronized(lock){
            (helper?:Db(c).also{helper=it}).writableDatabase
        }
        const val EXAMPLE="date,code,tradeVolumeAtAsk,tradeVolumeAtBid,asOf,source\n2026-10-09,2330,150000,90000,13:30:00,licensed-broker-export"
        private val COMPLETE=LocalTime.of(13,25)
        private fun csvLine(line:String):List<String>{
            val parts=mutableListOf<String>();val current=StringBuilder();var quoted=false;var i=0
            while(i<line.length){
                val ch=line[i]
                if(ch=='"'){
                    if(quoted&&i+1<line.length&&line[i+1]=='"'){current.append('"');i++}else quoted=!quoted
                }else if(ch==','&&!quoted){parts+=current.toString().trim();current.setLength(0)}
                else current.append(ch)
                i++
            }
            require(!quoted){"CSV 引號未關閉"}
            parts+=current.toString().trim()
            return parts
        }
        fun parseCsv(text:String,today:LocalDate=LocalDate.now(RuleMetrics.TAIPEI),clock:LocalTime=LocalTime.now(RuleMetrics.TAIPEI)):Map<Pair<LocalDate,String>,TradeSideVolume>{
            require(text.length in 1..16_000_000){"CSV 檔案太大"}
            val lines=text.lineSequence().map{it.trimEnd('\r')}.filter{it.isNotBlank()}.iterator()
            require(lines.hasNext()){"CSV 是空白檔案"}
            val columns=csvLine(lines.next().removePrefix("\uFEFF")).map{it.trim().lowercase()}
            val required=listOf("date","code","tradevolumeatask","tradevolumeatbid","asof","source")
            require(required.all{it in columns}){"CSV 必須含 date,code,tradeVolumeAtAsk,tradeVolumeAtBid,asOf,source 六欄"}
            val indexes=required.map{columns.indexOf(it)}
            val all=mutableMapOf<Pair<LocalDate,String>,TradeSideVolume>()
            var total=0
            while(lines.hasNext()){
                total++;require(total<=100000){"單次 CSV 最多 100,000 筆"}
                val row=csvLine(lines.next());require(row.size==columns.size){"第 $total 筆 CSV 欄位數不符"}
                fun col(i:Int)=row[indexes[i]]
                val day=LocalDate.parse(col(0))
                require(day>=LocalDate.of(2016,1,1)&&day<=today){"第 $total 筆交易日期不合理"}
                val code=col(1)
                require(code.matches(Regex("[1-9][0-9]{3}"))){"第 $total 筆股號需為 4 碼"}
                fun volume(v:String):Long=v.replace(",","").toLongOrNull()
                    ?:throw IllegalArgumentException("第 $total 筆買賣量需為整數")
                val asOf=LocalTime.parse(col(4))
                if(day==today)require(!asOf.isAfter(clock)){"第 $total 筆資料時間在未來"}
                val flow=TradeSideVolume(day,volume(col(2)),volume(col(3)),asOf,col(5))
                val key=day to code
                val previous=all[key]
                if(previous==null||previous.asOf<=asOf)all[key]=flow
            }
            require(all.isNotEmpty()){"CSV 必須至少有一筆行情"}
            return all
        }
    }
    private val prefs=c.getSharedPreferences("trade-side-volume-v1",0)
    fun revision():String=prefs.getString("revision","none").orEmpty()
    fun read(code:String,day:LocalDate,completed:Boolean=false):TradeSideVolume?=synchronized(lock){
        if(!code.matches(Regex("[1-9][0-9]{3}")))return@synchronized null
        db(c).rawQuery("SELECT buy,sell,asof,source FROM flows WHERE day=? AND code=?",
            arrayOf(day.toString(),code)).use{row->
            if(!row.moveToFirst())return@use null
            runCatching{TradeSideVolume(day,row.getLong(0),row.getLong(1),LocalTime.parse(row.getString(2)),row.getString(3))}
                .getOrNull()?.takeIf{!completed||!it.asOf.isBefore(COMPLETE)}
        }
    }
    /** Read at most one stock's historical daily samples into memory at a time.
     * Avoid one SQLite lookup per candle or materializing the entire market.
     */
    fun forCode(code:String,start:LocalDate,end:LocalDate,completed:Boolean=true):Map<LocalDate,TradeSideVolume> = synchronized(lock){
        if(revision()=="none"||!code.matches(Regex("[1-9][0-9]{3}")))return@synchronized emptyMap()
        val result=mutableMapOf<LocalDate,TradeSideVolume>()
        db(c).rawQuery("SELECT day,buy,sell,asof,source FROM flows WHERE code=? AND day>=? AND day<=?",
            arrayOf(code,start.toString(),end.toString())).use{rows->
            while(rows.moveToNext()){
                val date=runCatching{LocalDate.parse(rows.getString(0))}.getOrNull()?:continue
                val flow=runCatching{TradeSideVolume(date,rows.getLong(1),rows.getLong(2),
                    LocalTime.parse(rows.getString(3)),rows.getString(4))}.getOrNull()?:continue
                if(!completed||!flow.asOf.isBefore(COMPLETE))result[date]=flow
            }
        }
        result
    }
    fun importCsv(stream:InputStream):Int {
        val raw=stream.bufferedReader(Charsets.UTF_8).use{it.readText()}
        val rows=parseCsv(raw)
        val written=synchronized(lock){
            val database=db(c);database.beginTransaction()
            try{
                var count=0
                rows.forEach{(key,volume)->
                    val older=read(key.second,key.first)
                    if(older!=null&&older.asOf.isAfter(volume.asOf))return@forEach
                    val row=ContentValues().apply{
                        put("day",key.first.toString());put("code",key.second)
                        put("buy",volume.buyAtAsk);put("sell",volume.sellAtBid)
                        put("asof",volume.asOf.toString());put("source",volume.source)
                    }
                    check(database.insertWithOnConflict("flows",null,row,SQLiteDatabase.CONFLICT_REPLACE)>0)
                    count++
                }
                database.setTransactionSuccessful();count
            }finally{database.endTransaction()}
        }
        if(written>0){
            check(prefs.edit().putString("revision",UUID.randomUUID().toString()).commit()){"內外盤資料修訂版本保存失敗"}
            // Previously prepared backtest signals and robot trials must stay frozen.
            runCatching{
                val bt=com.rex.twboardingscanner.backtest.BacktestStore(c)
                bt.update(bt.active(),"內外盤資料更新，請以新快照重做回測；原日誌保留","CANCELED")
                val robot=com.rex.twboardingscanner.backtest.BtRobotStore(c)
                if(robot.active().isNotEmpty())com.rex.twboardingscanner.backtest.BtRobotWorker.pause(c,robot.active())
            }
        }
        return written
    }
}
