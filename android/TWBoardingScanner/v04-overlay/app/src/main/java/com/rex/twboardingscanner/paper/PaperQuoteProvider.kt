package com.rex.twboardingscanner.paper

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.*
import java.time.format.DateTimeFormatter

class PaperQuoteProvider {
    fun load(symbols:List<Pair<String,String>>):Map<String,PaperQuote> = symbols.distinct().chunked(40).flatMap { group ->
        val channels=group.joinToString("|"){"${it.second}_${it.first}.tw"}
        val c=URL("https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=${java.net.URLEncoder.encode(channels,"UTF-8")}&json=1&delay=0").openConnection() as HttpURLConnection
        try {
            c.connectTimeout=8000;c.readTimeout=8000
            c.setRequestProperty("User-Agent","Mozilla/5.0 (Android) TWBoardingScanner/0.4.13")
            c.setRequestProperty("Referer","https://mis.twse.com.tw/stock/index")
            require(c.responseCode==200)
            parse(c.inputStream.bufferedReader().use{it.readText()},group).values.toList()
        } finally {c.disconnect()}
    }.associateBy{it.code}
    companion object {
        fun parse(raw:String,requested:List<Pair<String,String>>):Map<String,PaperQuote> {
            val root=JSONObject(raw);require(root.optString("rtcode")=="0000")
            val rows=root.getJSONArray("msgArray")
            return (0 until rows.length()).mapNotNull {i-> runCatching {
                val q=rows.getJSONObject(i);val code=q.getString("c")
                require((code to q.getString("ex")) in requested)
                require(q.optString("ip")=="0") // reject pre-open trial prices / unknown state
                val date=LocalDate.parse(q.getString("d"),DateTimeFormatter.BASIC_ISO_DATE)
                val at=date.atTime(LocalTime.parse(q.getString("t"))).atZone(TAIPEI).toInstant().toEpochMilli()
                fun num(key:String)=q.getString(key).substringBefore('_').toDouble().also{require(it.isFinite())}
                PaperQuote(code,at,num("z"),num("b"),num("a"),num("g"),num("f"),q.getString("v").toLong(),num("u"),num("w")).also{
                    require(it.last>0 && it.bid>0 && it.ask>=it.bid && it.lower>0 && it.upper>=it.lower && it.last in it.lower..it.upper)
                }
            }.getOrNull()}.associateBy{it.code}
        }
    }
}
