package com.rex.twboardingscanner.backtest

import android.content.Context
import com.rex.twboardingscanner.data.MarketDataProvider
import com.rex.twboardingscanner.domain.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.concurrent.*

class BacktestData(private val context:Context) {
    data class Loaded(val series:List<BtSeries>,val requested:Int,val excluded:List<String>)
    fun load(settings:BtSettings,progress:(String)->Unit,cancel:()->Boolean):Loaded {
        progress("取得現存上市櫃股票名單…")
        val universe=MarketDataProvider(context).loadBacktestUniverse()
        require(universe.isNotEmpty()){ "無法取得股票名單，請稍後重試" }
        runCatching{com.rex.twboardingscanner.data.StockDirectory(context).remember(universe)}
        val codes=settings.codes.split(Regex("[,，\\s]+" )).filter{it.isNotBlank()}.toSet()
        if(codes.isEmpty())require(Market.entries.all{m->universe.any{it.market==m}}){"上市／上櫃名單不完整，請稍後重試"}
        val selected=universe.filter{it.sector in settings.sectors&&(codes.isEmpty()||it.code in codes)}
        require(selected.isNotEmpty()){ "目前產業設定與股號範圍內沒有股票" }
        val excluded=mutableListOf<String>();val loaded=mutableListOf<BtSeries>()
        codes.filter{c->selected.none{it.code==c}}.forEach{excluded.add("$it：不在目前產業範圍／現存名單")}
        val pool=Executors.newFixedThreadPool(4)
        try {
            val completion=ExecutorCompletionService<Pair<BtSeries?,String?>>(pool)
            selected.forEach{s->completion.submit(Callable{
                if(cancel())return@Callable null to "${s.code}：已取消"
                try{ loadOne(s,settings) to null }catch(e:Exception){null to "${s.code} ${s.name}：${e.message?.take(100)?:"歷史資料失敗"}"}
            })}
            repeat(selected.size){i->
                check(!cancel()){ "已取消回測" }
                var f:Future<Pair<BtSeries?,String?>>?=null
                while(f==null){check(!cancel()){ "已取消回測" };f=completion.poll(1,TimeUnit.SECONDS)}
                val (series,error)=f.get();if(series!=null)loaded+=series else if(error!=null)excluded+=error
                progress("歷史日線 ${i+1}/${selected.size} · 成功 ${loaded.size} · 缺少／排除 ${excluded.size}")
            }
        } finally {pool.shutdownNow()}
        require(loaded.isNotEmpty()){ "沒有可用歷史日線；請檢查日期或稍後重試" }
        return Loaded(loaded.sortedBy{it.code},selected.size+codes.count{c->selected.none{it.code==c}},excluded)
    }
    private fun loadOne(stock:MarketStock,settings:BtSettings):BtSeries {
        val symbol=stock.code+if(stock.market==Market.TWSE)".TW" else ".TWO"
        val from=settings.start.minusDays(600);val today=LocalDate.now(RuleMetrics.TAIPEI)
        // Events up through today are required to identify split-adjusted source series.
        val cache=File(context.cacheDir,"backtest_v1/${symbol}_${from}_${today}.json");cache.parentFile?.mkdirs()
        val raw=if(cache.exists())cache.readText() else {
            val start=from.atStartOfDay(RuleMetrics.TAIPEI).toEpochSecond();val end=today.plusDays(1).atStartOfDay(RuleMetrics.TAIPEI).toEpochSecond()
            val c=URL("https://query1.finance.yahoo.com/v8/finance/chart/$symbol?period1=$start&period2=$end&interval=1d&events=div%2Csplits").openConnection() as HttpURLConnection
            val text=try{c.connectTimeout=8000;c.readTimeout=12000;c.setRequestProperty("User-Agent","Mozilla/5.0 TWBoardingScanner/0.4.15");require(c.responseCode==200){"行情來源 HTTP ${c.responseCode}"};c.inputStream.bufferedReader().use{it.readText()}}finally{c.disconnect()}
            parse(text,symbol,stock.name,stock.market,from,settings.end) // invalid/partial responses never enter cache
            val temp=File(cache.path+".${java.util.UUID.randomUUID()}.tmp");temp.writeText(text);check(temp.renameTo(cache));text
        }
        return parse(raw,symbol,stock.name,stock.market,from,settings.end).also{series->require(series.bars.any{RuleMetrics.tradingDate(it.time)>=settings.start}){"所選區間沒有有效行情"}}
    }
    companion object {
        fun parse(raw:String,symbol:String,name:String,market:Market,from:LocalDate,end:LocalDate):BtSeries {
            val chart=JSONObject(raw).getJSONObject("chart");require(chart.isNull("error")){"來源回傳錯誤"}
            val r=chart.getJSONArray("result").getJSONObject(0);require(r.getJSONObject("meta").getString("symbol")==symbol){"股號不符"}
            val events=r.optJSONObject("events")
            // Do not mistake retrospectively split-adjusted nominal prices for the historical NT$50 gate.
            require((events?.optJSONObject("splits")?.length()?:0)==0){"查詢至今有拆併股，原始價格口徑未核對，排除"}
            val timestamps=r.getJSONArray("timestamp");val q=r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
            val bars=mutableListOf<DailyBar>()
            for(i in 0 until timestamps.length()){
                val time=timestamps.getLong(i)*1000;val date=RuleMetrics.tradingDate(time)
                if(date<from||date>end)continue
                fun number(key:String)=q.getJSONArray(key).optDouble(i,Double.NaN)
                val o=number("open");val h=number("high");val l=number("low");val c=number("close");val v=number("volume")
                if(listOf(o,h,l,c).any{!it.isFinite()||it<=0}||!v.isFinite()||v<0||h<maxOf(o,c)||l>minOf(o,c)||h<l)continue
                bars+=DailyBar(time,o,h,l,c,v.toLong())
            }
            require(bars.size>=60){"不足 60 根有效日線（含暖機期）"}
            val div=mutableMapOf<LocalDate,Double>();val dividends=events?.optJSONObject("dividends")
            dividends?.keys()?.forEach{key->val d=dividends.getJSONObject(key);val day=RuleMetrics.tradingDate(d.getLong("date")*1000);val amount=d.getDouble("amount");if(day>=from&&day<=end&&amount.isFinite()&&amount>=0)div[day]=amount}
            return BtSeries(symbol.substringBefore('.'),name,market,bars.sortedBy{it.time}.distinctBy{RuleMetrics.tradingDate(it.time)},div)
        }
    }
}
