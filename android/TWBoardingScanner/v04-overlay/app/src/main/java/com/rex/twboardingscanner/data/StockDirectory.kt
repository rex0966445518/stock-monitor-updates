package com.rex.twboardingscanner.data

import android.content.Context
import com.rex.twboardingscanner.domain.MarketStock
import com.rex.twboardingscanner.domain.StockSector
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class StockInfo(val code:String,val name:String,val market:String,val sector:String,val price:Double,val change:Double,val volume:Int,val fetchedAt:Long)
/** Small local directory for immediate exact-code lookup; refresh never starts a scan. */
class StockDirectory(c:Context){
    private val context=c.applicationContext
    private val file=File(context.filesDir,"stock-directory.json")
    companion object{private val lock=Any()}
    /** One lookup snapshot per list. Bundled official metadata also covers old/offline logs. */
    fun industryLabels():Map<String,String>{
        val labels=runCatching{
            val raw=context.assets.open("stock-industries.json").bufferedReader().use{JSONObject(it.readText()).getJSONObject("industries")}
            raw.keys().asSequence().associateWith{StockSector.fromIndustry(raw.getString(it)).label}.toMutableMap()
        }.getOrDefault(mutableMapOf())
        cached().forEach{stock->
            if(stock.sector.isNotBlank()&&stock.sector!=StockSector.UNKNOWN.label)labels[stock.code]=stock.sector
        }
        return labels
    }
    fun cached():List<StockInfo> = synchronized(lock){runCatching{val a=JSONArray(file.readText());(0 until a.length()).map{val o=a.getJSONObject(it);StockInfo(o.getString("code"),o.getString("name"),o.getString("market"),o.getString("sector"),o.getDouble("price"),o.getDouble("change"),o.getInt("volume"),o.getLong("fetchedAt"))}}.getOrDefault(emptyList())}
    fun remember(stocks:List<MarketStock>)=synchronized(lock){
        if(stocks.isEmpty())return@synchronized
        val now=System.currentTimeMillis();val all=cached().associateBy{it.code}.toMutableMap()
        stocks.forEach{all[it.code]=StockInfo(it.code,it.name,it.market.name,it.sector.label,it.close,it.changePct,it.volumeLots,now)}
        val a=JSONArray(all.values.sortedBy{it.code}.map{JSONObject().put("code",it.code).put("name",it.name).put("market",it.market).put("sector",it.sector).put("price",it.price).put("change",it.change).put("volume",it.volume).put("fetchedAt",it.fetchedAt)})
        val temp=File(file.path+".tmp");temp.writeText(a.toString());check(temp.renameTo(file))
    }
    fun refresh():List<StockInfo>{remember(MarketDataProvider(context).loadBacktestUniverse());return cached()}
}
