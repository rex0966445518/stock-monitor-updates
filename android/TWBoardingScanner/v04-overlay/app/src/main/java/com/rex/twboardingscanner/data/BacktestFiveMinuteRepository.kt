package com.rex.twboardingscanner.data

import android.content.Context
import com.rex.twboardingscanner.domain.RuleMetrics
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.Executors

/** Historical, real OHLCV 5-minute candles. Never infer minute prices from backtest daily bars. */
internal data class FiveMinuteBar(
    val time:Long, val open:Double, val high:Double, val low:Double,
    val close:Double, val volumeShares:Long
)
internal sealed class FiveMinuteResult {
    data class Available(val bars:List<FiveMinuteBar>,val source:String):FiveMinuteResult()
    data class Unavailable(val reason:String):FiveMinuteResult()
}
internal class BacktestFiveMinuteRepository(context:Context){
    private val c=context.applicationContext
    companion object {
        // Keep a small, bounded queue: expanding an archived journal cannot flood the quote service.
        val executor=Executors.newFixedThreadPool(3)
        private val memory=object:LinkedHashMap<String,List<FiveMinuteBar>>(64,.75f,true){
            override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,List<FiveMinuteBar>>?)=size>96
        }
        private val lock=Any()
        fun retentionReason(day:LocalDate,today:LocalDate=LocalDate.now(RuleMetrics.TAIPEI)):String?=when {
            day>today->"交易日期尚未到來，沒有當日 5 分 K 資料"
            ChronoUnit.DAYS.between(day,today)>59->"該交易日超過免費行情來源約 60 天的 5 分 K 回溯範圍；若當時未快取，無法真實還原"
            else->null
        }
        fun parse(raw:String,symbol:String,day:LocalDate):List<FiveMinuteBar>{
            val chart=JSONObject(raw).getJSONObject("chart")
            require(chart.isNull("error")){"5 分 K 行情來源回傳錯誤"}
            val result=chart.optJSONArray("result")?.optJSONObject(0)?:return emptyList()
            require(result.getJSONObject("meta").optString("symbol")==symbol){"行情股號不一致"}
            val times=result.optJSONArray("timestamp")?:return emptyList()
            val quote=result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
            fun n(key:String,i:Int)=quote.optJSONArray(key)?.optDouble(i,Double.NaN)?:Double.NaN
            val parsed=mutableListOf<FiveMinuteBar>()
            for(i in 0 until times.length()){
                if(times.isNull(i))continue
                val timestamp=times.optLong(i,0)*1000L
                if(timestamp<=0||RuleMetrics.tradingDate(timestamp)!=day)continue
                val tm=Instant.ofEpochMilli(timestamp).atZone(RuleMetrics.TAIPEI).toLocalTime()
                if(tm.isBefore(LocalTime.of(9,0))||tm.isAfter(LocalTime.of(13,30)))continue
                val o=n("open",i);val h=n("high",i);val l=n("low",i);val cl=n("close",i);val v=n("volume",i)
                if(listOf(o,h,l,cl).any{!it.isFinite()||it<=0}||
                    h<maxOf(o,cl)||l>minOf(o,cl)||h<l||!v.isFinite()||v<0)continue
                parsed.add(FiveMinuteBar(timestamp,o,h,l,cl,v.toLong()))
            }
            // Keep every five-minute candle, not one bar per day like ChartSeries.prepare().
            return parsed.sortedBy{it.time}.distinctBy{it.time}
        }
    }
    private fun disk(symbol:String,day:LocalDate)=File(c.filesDir,"backtest-five-minute-v1/$day-$symbol.json")
    private fun saved(symbol:String,day:LocalDate):List<FiveMinuteBar>?{
        val file=disk(symbol,day)
        synchronized(lock){memory[file.name]?.let{return it}}
        if(!file.exists())return null
        val bars=runCatching{parse(file.readText(),symbol,day)}.getOrNull().orEmpty()
        if(bars.isEmpty()){file.delete();return null}
        synchronized(lock){memory[file.name]=bars}
        return bars
    }
    private fun fetch(symbol:String,day:LocalDate):String {
        val from=day.atStartOfDay(RuleMetrics.TAIPEI).toEpochSecond()
        val to=day.plusDays(1).atStartOfDay(RuleMetrics.TAIPEI).toEpochSecond()
        val link="https://query1.finance.yahoo.com/v8/finance/chart/$symbol?period1=$from&period2=$to&interval=5m"
        val connection=URL(link).openConnection() as HttpURLConnection
        try{
            connection.connectTimeout=8000;connection.readTimeout=12000
            connection.setRequestProperty("User-Agent","Mozilla/5.0 TWBoardingScanner/0.4")
            require(connection.responseCode==200){"行情服務 HTTP ${connection.responseCode}"}
            return connection.inputStream.bufferedReader().use{it.readText()}
        }finally{connection.disconnect()}
    }
    fun load(code:String,day:LocalDate,market:String?=null):FiveMinuteResult{
        if(!code.matches(Regex("[0-9]{4}")))return FiveMinuteResult.Unavailable("舊交易紀錄缺有效四位股號")
        val symbols=when(market){
            "TWSE"->listOf("$code.TW","$code.TWO")
            "TPEX"->listOf("$code.TWO","$code.TW")
            else->listOf("$code.TW","$code.TWO")
        }
        // Check permanent on-device snapshots FIRST. Previously saved bars remain usable after 60 days.
        symbols.forEach{symbol->saved(symbol,day)?.let{return FiveMinuteResult.Available(it,"已保存的真實 5 分 K")}}
        retentionReason(day)?.let{return FiveMinuteResult.Unavailable(it)}
        var lastError=""
        symbols.forEach{symbol->
            try{
                val body=fetch(symbol,day)
                val bars=parse(body,symbol,day)
                if(bars.isEmpty()){lastError="行情來源沒有該日的有效 5 分 K";return@forEach}
                val file=disk(symbol,day);file.parentFile?.mkdirs()
                val temp=File(file.path+".${UUID.randomUUID()}.tmp")
                temp.writeText(body)
                if(!temp.renameTo(file)){temp.delete()} // still show verified bars if disk is full
                synchronized(lock){memory[file.name]=bars}
                return FiveMinuteResult.Available(bars,"Yahoo Finance 歷史 5 分 K")
            }catch(e:Exception){lastError=e.message?.take(100).orEmpty()}
        }
        return FiveMinuteResult.Unavailable("查不到此日真實 5 分 K（${lastError.ifBlank{"行情來源受限或網路不通"} }）；不以日 K 補造分鐘線")
    }
}
