package com.rex.twboardingscanner.data

import com.rex.twboardingscanner.domain.*
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** Regular-board last auction, never Yahoo's aggregated minute volume or after-hours volume. */
class ClosingAuctionProvider {
    private val cache=ConcurrentHashMap<String,Pair<Long,ClosingAuction>>()
    fun load(code:String,market:Market):ClosingAuction {
        if(!code.matches(Regex("[0-9]{4}"))) return ClosingAuction(note="股號無效")
        val key="$market:$code";val now=ZonedDateTime.now(RuleMetrics.TAIPEI)
        cache[key]?.let { (at,value) -> if(System.currentTimeMillis()-at<60_000 && Instant.ofEpochMilli(at).atZone(RuleMetrics.TAIPEI).toLocalDate()==now.toLocalDate()) return value }
        val result=runCatching {
            val exchange=if(market==Market.TWSE)"tse" else "otc"
            val raw=fetch("https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=${exchange}_${code}.tw&json=1&delay=0")
            val auction=parseQuote(raw,code,exchange,now)
            if(auction.lots==null) auction else {
                val suffix=if(market==Market.TWSE)"TW" else "TWO"
                val before=runCatching { previousPrice(fetch("https://tw.stock.yahoo.com/quote/$code.$suffix/time-sales"),"$code.$suffix",auction) }.getOrNull()
                auction.copy(beforePrice=before,note=if(before==null)"撮合量已取得；紅綠方向待查核" else "紅綠依收盤價相對收盤前成交價判定")
            }
        }.getOrElse { ClosingAuction(note="來源暫時無法取得，稍後掃描重試") }
        cache[key]=System.currentTimeMillis() to result
        return result
    }
    private fun fetch(url:String):String {
        val c=URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout=8000;c.readTimeout=8000;c.setRequestProperty("User-Agent","Mozilla/5.0 (Android) TWBoardingScanner/0.4.11")
            if(url.contains("mis.twse"))c.setRequestProperty("Referer","https://mis.twse.com.tw/stock/index")
            require(c.responseCode==200){"HTTP ${c.responseCode}"}
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
    companion object {
        fun parseQuote(raw:String,code:String,exchange:String,now:ZonedDateTime):ClosingAuction {
            val root=JSONObject(raw);require(root.optString("rtcode")=="0000")
            val arr=root.getJSONArray("msgArray")
            val q=(0 until arr.length()).map {arr.getJSONObject(it)}.firstOrNull {it.optString("c")==code&&it.optString("ex")==exchange}
                ?: return ClosingAuction(note="此股沒有官方成交資料")
            val date=runCatching{LocalDate.parse(q.getString("d"),DateTimeFormatter.BASIC_ISO_DATE)}.getOrNull()
                ?:return ClosingAuction(note="交易日期待查核")
            val today=now.withZoneSameInstant(RuleMetrics.TAIPEI).toLocalDate()
            if(date>today || date<today.minusDays(7))return ClosingAuction(date=date.toString(),note="交易日期過期或異常")
            val trade=q.optJSONObject("trade")
            val time=trade?.optString("t")?.takeIf{it.isNotBlank()} ?: q.optString("t")
            // Deferred closing is separate and clearly timestamped. Reject 14:30 after-hours trades.
            if(time !in listOf("13:30:00","13:33:00")) return ClosingAuction(date=date.toString(),note=if(date==today&&now.toLocalTime()<LocalTime.of(13,30))"尚未收盤撮合" else "未取得13:30／13:33收盤成交")
            if(date==today && now.toLocalTime()<LocalTime.parse(time))return ClosingAuction(date=date.toString(),note="尚未到收盤撮合時間")
            val lots=(if(trade!=null)trade.opt("v")?.toString() else q.optString("tv"))?.toLongOrNull()
            val price=(if(trade!=null)trade.optString("z") else q.optString("z")).toDoubleOrNull()
            if(lots==null||lots<=0||price==null||!price.isFinite()||price<=0)return ClosingAuction(date=date.toString(),time=time,note="收盤成交量／價格待查核")
            return ClosingAuction(date.toString(),time,lots,price,note="紅綠方向待查核")
        }
        fun previousPrice(html:String,symbol:String,auction:ClosingAuction):Double? {
            val doc=Jsoup.parse(html)
            val url=doc.selectFirst("meta[property=og:url]")?.attr("content") ?: return null
            if(!url.endsWith("/quote/$symbol/time-sales"))return null
            val marker="\"QuoteTimeSalesStore\":"
            val begin=html.indexOf(marker).takeIf{it>=0}?.plus(marker.length) ?: return null
            var depth=0;var quoted=false;var escape=false;var end=-1
            for(i in begin until html.length){val ch=html[i];if(escape){escape=false;continue};if(quoted&&ch=='\\'){escape=true;continue};if(ch=='\"'){quoted=!quoted;continue};if(!quoted){if(ch=='{')depth++;if(ch=='}'){depth--;if(depth==0){end=i+1;break}}}}
            if(end<0)return null
            val list=JSONObject(html.substring(begin,end)).getJSONObject("overviewPriceByTimes").getJSONObject("data").getJSONArray("list")
            val close=LocalDate.parse(auction.date).atTime(LocalTime.parse(auction.time)).atZone(RuleMetrics.TAIPEI).toInstant()
            val ticks=(0 until list.length()).mapNotNull {i -> val o=list.getJSONObject(i)
                val at=runCatching{Instant.parse(o.optString("detailedTime"))}.getOrNull() ?:return@mapNotNull null
                val price=o.optString("price").replace(",","").toDoubleOrNull()?.takeIf{it.isFinite()&&it>0} ?:return@mapNotNull null
                at to price
            }
            // Cross-source dates AND final price must match before using the preceding price.
            if(ticks.none{it.first==close&&it.second==auction.price})return null
            return ticks.filter {it.first<close && it.first.atZone(RuleMetrics.TAIPEI).toLocalDate().toString()==auction.date && it.first.atZone(RuleMetrics.TAIPEI).toLocalTime()<=LocalTime.of(13,25)}.maxByOrNull{it.first}?.second
        }
    }
}
