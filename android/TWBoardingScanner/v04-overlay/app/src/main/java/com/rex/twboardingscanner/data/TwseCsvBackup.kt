package com.rex.twboardingscanner.data

import android.content.Context
import com.rex.twboardingscanner.domain.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.UUID

/**
 * Official TWSE CSV alternate for openapi STOCK_DAY_ALL.
 * No proxy, third-party estimates or manufactured prices.
 */
object TwseCsvBackup {
    const val URL="https://www.twse.com.tw/exchangeReport/STOCK_DAY_ALL?response=open_data"
    internal fun fields(line:String):List<String>{
        val out=mutableListOf<String>();val cell=StringBuilder();var quoted=false;var i=0
        while(i<line.length){
            val c=line[i]
            when{
                c=='"'&&quoted&&i+1<line.length&&line[i+1]=='"'->{cell.append('"');i++}
                c=='"'->quoted=!quoted
                c==','&&!quoted->{out+=cell.toString().trim();cell.setLength(0)}
                else->cell.append(c)
            }
            i++
        }
        out+=cell.toString().trim()
        return out
    }
    /** Fixed column order is verified against a matching official CSV header. */
    fun parse(raw:String,sector:Map<String,Pair<String,StockSector>> = emptyMap(),
              eps:Map<String,Double> = emptyMap(),revenue:Map<String,Double> = emptyMap()):List<MarketStock>{
        val lines=raw.removePrefix("\uFEFF").lineSequence().filter{it.isNotBlank()}.iterator()
        if(!lines.hasNext())return emptyList()
        val header=fields(lines.next())
        val expected=listOf("證券代號","證券名稱","成交股數","成交金額","開盤價","最高價","最低價","收盤價","漲跌價差","成交筆數")
        if(header.size<expected.size||header.take(expected.size)!=expected)return emptyList()
        val rows=mutableListOf<MarketStock>()
        while(lines.hasNext()){
            val f=fields(lines.next())
            if(f.size<10)continue
            val code=f[0]
            if(!code.matches(Regex("[1-9][0-9]{3}")))continue
            fun amount(i:Int)=f[i].replace(",","").replace("+","").trim().toDoubleOrNull()
            val close=amount(7)?.takeIf{it.isFinite()&&it>0}?:continue
            val open=amount(4)?:continue;val high=amount(5)?:continue;val low=amount(6)?:continue
            if(!listOf(open,high,low).all{it.isFinite()&&it>0})continue
            val shares=amount(2)?.toLong()?:continue
            if(shares<0)continue
            val change=amount(8)?:0.0
            val prev=close-change
            val percent=if(prev>0.0)change/prev*100.0 else 0.0
            val meta=sector[code]
            rows+=MarketStock(code,meta?.first?:f[1].ifBlank{code},Market.TWSE,
                meta?.second?:StockSector.UNKNOWN,open,high,low,close,percent,
                (shares/1000).toInt(),eps[code],revenue[code])
        }
        return rows.distinctBy{it.code}
    }

    fun load(context:Context,sector:Map<String,Pair<String,StockSector>>,
             eps:Map<String,Double>,revenue:Map<String,Double>):List<MarketStock>{
        val file=File(context.cacheDir,"scanner_official_v1/twse-stock-day.csv")
        val now=System.currentTimeMillis()
        if(file.exists()&&MarketDataProvider.cacheFresh(file.lastModified(),now,
                MarketDataProvider.referenceTtlMs(URL,now))){
            val cached=runCatching{parse(file.readText(),sector,eps,revenue)}.getOrDefault(emptyList())
            if(cached.size>=100)return cached
        }
        val raw=runCatching{
            val connection=java.net.URL(URL).openConnection() as HttpURLConnection
            try{
                connection.connectTimeout=6000;connection.readTimeout=10000
                connection.setRequestProperty("User-Agent","Mozilla/5.0 (Android)")
                check(connection.responseCode==200){"官方行情 HTTP ${connection.responseCode}"}
                val bytes=connection.inputStream.use{it.readBytes()}
                val utf8=bytes.toString(Charsets.UTF_8)
                if(utf8.contains("證券代號"))utf8 else bytes.toString(Charset.forName("Big5"))
            }finally{connection.disconnect()}
        }.getOrNull()?:return emptyList()
        val result=parse(raw,sector,eps,revenue)
        if(result.size>=100)runCatching{
            file.parentFile?.mkdirs()
            val temp=File(file.path+".${UUID.randomUUID()}.tmp")
            temp.writeText(raw)
            if(!temp.renameTo(file))temp.delete()
        }
        return result
    }
}
