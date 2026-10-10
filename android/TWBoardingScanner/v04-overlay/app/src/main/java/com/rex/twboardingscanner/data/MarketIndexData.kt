package com.rex.twboardingscanner.data

import android.content.Context
import com.rex.twboardingscanner.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** TWSE official closing index; month caches never substitute for a failed live refresh. */
class MarketIndexData(private val context:Context){
    private val prefs=context.getSharedPreferences("market-crash-guard-v1",0)
    companion object {
        private val liveLock=Any()
        private const val TTL=300000L
        fun date(raw:String):LocalDate {
            val digits=raw.filter{it.isDigit()}
            require(digits.length in 7..8){"大盤日期格式無效"}
            val y=digits.dropLast(4).toInt().let{if(it<1911)it+1911 else it}
            return LocalDate.of(y,digits.takeLast(4).take(2).toInt(),digits.takeLast(2).toInt())
        }
        fun parse(raw:String,month:YearMonth):List<MarketIndexBar>{
            val o=JSONObject(raw);require(o.optString("stat")=="OK"){"證交所未提供大盤月資料"}
            val fields=o.getJSONArray("fields");val names=(0 until fields.length()).map{fields.getString(it)}
            val di=names.indexOf("日期");val ci=names.indexOf("發行量加權股價指數");val xi=names.indexOf("漲跌點數")
            require(listOf(di,ci,xi).all{it>=0}){"大盤來源欄位變更"}
            val data=o.getJSONArray("data")
            val bars=(0 until data.length()).map{i->
                val row=data.getJSONArray(i);val day=date(row.getString(di))
                fun n(j:Int)=row.getString(j).replace(",","").replace("−","-").trim().toDouble()
                require(YearMonth.from(day)==month){"大盤資料月份不符"}
                MarketIndexBar(day,n(ci),n(xi)).also{require(it.close.isFinite()&&it.close>0&&it.change.isFinite())}
            }
            require(bars.isNotEmpty()&&bars.map{it.date}.distinct().size==bars.size){"大盤月資料缺少或重複"}
            return bars.sortedBy{it.date}
        }
        fun previousSession(today:LocalDate,holidays:JSONArray):LocalDate{
            val rows=(0 until holidays.length()).map{holidays.getJSONObject(it)}
            require(rows.any{date(it.getString("Date")).year==today.year}){"休市行事曆尚未更新"}
            val closed=rows.filter{!it.optString("Name").contains("開始交易")&&!it.optString("Name").contains("最後交易")}.map{date(it.getString("Date"))}.toSet()
            var day=today.minusDays(1)
            while(day.dayOfWeek.value>=6||day in closed)day=day.minusDays(1)
            return day
        }
        private fun readDecision(o:JSONObject):MarketGuardDecision=MarketGuardDecision(o.getString("state"),
            o.optString("asOf").takeIf{it.isNotBlank()&&it!="null"}?.let(LocalDate::parse),
            o.optDouble("close",Double.NaN).takeIf{it.isFinite()},o.optDouble("change",Double.NaN).takeIf{it.isFinite()},
            o.optDouble("ma5",Double.NaN).takeIf{it.isFinite()},o.optInt("risingDays"),
            o.optString("trigger").takeIf{it.isNotBlank()&&it!="null"}?.let(LocalDate::parse),o.optString("reason"))
    }
    private fun fetch(url:String):String{
        check(!Thread.currentThread().isInterrupted){"已取消大盤讀取"}
        val c=URL(url).openConnection() as HttpURLConnection
        return try{c.connectTimeout=8000;c.readTimeout=12000;c.setRequestProperty("User-Agent","Mozilla/5.0 TWBoardingScanner/0.4.35")
            c.setRequestProperty("Accept","application/json");require(c.responseCode==200){"大盤來源 HTTP ${c.responseCode}"}
            c.inputStream.bufferedReader().use{it.readText()}
        }finally{c.disconnect()}
    }
    fun load(from:LocalDate,end:LocalDate,progress:(String)->Unit={},cancel:()->Boolean={false},force:Boolean=false):List<MarketIndexBar>{
        require(from<=end)
        val result=mutableListOf<MarketIndexBar>();val today=LocalDate.now(RuleMetrics.TAIPEI)
        var month=YearMonth.from(from)
        while(month<=YearMonth.from(end)){
            check(!cancel()){"已取消大盤讀取"};progress("讀取加權指數 $month · 大盤暴跌保護")
            val file=File(context.cacheDir,"taiex-official-v1/$month.json").apply{parentFile?.mkdirs()}
            val closed=month<YearMonth.from(today)
            val cached=if(file.exists()&&(closed||(!force&&System.currentTimeMillis()-file.lastModified() in 0..TTL)))runCatching{parse(file.readText(),month)}.getOrNull() else null
            val bars=cached?:run{
                val date=month.atDay(1).format(DateTimeFormatter.BASIC_ISO_DATE)
                val raw=fetch("https://www.twse.com.tw/rwd/zh/afterTrading/FMTQIK?date=$date&response=json")
                val parsed=parse(raw,month)
                val temp=File(file.path+".${java.util.UUID.randomUUID()}.tmp");temp.writeText(raw);check(temp.renameTo(file)){"無法保存大盤快取"}
                parsed
            }
            result+=bars.filter{it.date>=from&&it.date<=end};month=month.plusMonths(1)
        }
        require(result.size>=5){"不足 5 個交易日大盤資料"}
        return result.sortedBy{it.date}
    }
    fun cached(now:Long=System.currentTimeMillis()):MarketGuardDecision{
        val day=java.time.Instant.ofEpochMilli(now).atZone(RuleMetrics.TAIPEI).toLocalDate().toString()
        if(prefs.getString("day",null)!=day||now-prefs.getLong("checked",0) !in 0..TTL)return MarketCrashGuard.unknown("尚未確認最新大盤，買入前會重新檢查")
        return runCatching{readDecision(JSONObject(prefs.getString("decision",null)!!))}.getOrElse{MarketCrashGuard.unknown("大盤狀態未保存")}
    }
    fun live(force:Boolean=false):MarketGuardDecision=synchronized(liveLock){
        val now=System.currentTimeMillis();val today=LocalDate.now(RuleMetrics.TAIPEI)
        if(!force&&prefs.getString("day",null)==today.toString()&&now-prefs.getLong("checked",0) in 0..TTL)return@synchronized cached(now)
        val decision=runCatching{
            val calendarFile=File(context.cacheDir,"taiex-calendar-${today.year}.json")
            val calendar=if(calendarFile.exists()&&now-calendarFile.lastModified() in 0..86400000L)JSONArray(calendarFile.readText()) else {
                val raw=fetch("https://openapi.twse.com.tw/v1/holidaySchedule/holidaySchedule")
                val a=JSONArray(raw);previousSession(today,a);calendarFile.writeText(raw);a
            }
            val expected=previousSession(today,calendar)
            val bars=load(expected.minusDays(100),expected,force=force)
            MarketCrashGuard.before(bars,today,expected)
        }.getOrElse{MarketCrashGuard.unknown("大盤資料無法確認：${it.message?.take(90)}；待更新後恢復判斷")}
        check(prefs.edit().putString("day",today.toString()).putLong("checked",now).putString("decision",decision.json().toString()).commit())
        decision
    }
}
