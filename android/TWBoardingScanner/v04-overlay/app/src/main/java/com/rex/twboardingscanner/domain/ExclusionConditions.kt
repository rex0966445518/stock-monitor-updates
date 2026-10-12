package com.rex.twboardingscanner.domain

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Exact daily event, shared by entry vetoes and independent risk exits. */
object BearishTurn{
    const val ID="macd_zero_down_rsi_high_drop"
    const val LABEL="MACD 訊號線下穿零軸 ＋ RSI(14) 從70以上跌破70（兩項同日成立）"
    data class Evidence(val previousMacd:Double,val macd:Double,val previousRsi:Double,val rsi:Double){
        val passes get()=previousMacd>=0.0&&macd<0.0&&previousRsi>=70.0&&rsi<70.0
        fun format()=String.format(Locale.TAIWAN,"MACD 訊號線 %.4f → %.4f · RSI(14) %.2f → %.2f\n%s",previousMacd,macd,previousRsi,rsi,if(passes)"命中零軸下穿＋RSI高檔下墜" else "未命中兩項同日轉弱")
    }
    fun inspect(closes:List<Double>):Evidence?{
        if(closes.size<31||closes.any{!it.isFinite()||it<=0.0})return null
        val calc=TechnicalCalculator();val now=calc.macd(closes)?:return null;val prior=calc.macd(closes.dropLast(1))?:return null
        val rsi=RsiGoldenCross.values(closes,14)
        return Evidence(prior.signal,now.signal,rsi[rsi.lastIndex-1],rsi.last())
    }
}

object ExclusionConditions{
    fun empty():Map<RadarType,Set<String>> = RadarType.entries.associateWith{emptySet()}
    fun forRadar(type:RadarType)=listOf(ScanCondition(BearishTurn.ID,BearishTurn.LABEL,true){it.bearishTurn?.passes})
    fun validate(map:Map<RadarType,Set<String>>){
        require(map.keys==RadarType.entries.toSet()){"排除區資料不完整"}
        map.forEach{(type,ids)->require(ids.all{id->forRadar(type).any{it.id==id}}){"排除條件版本不符"}}
    }
    fun json(map:Map<RadarType,Set<String>>):JSONObject{validate(map);return JSONObject().apply{map.forEach{(type,ids)->put(type.name,JSONArray(ids.sorted()))}}}
    fun read(o:JSONObject?):Map<RadarType,Set<String>> = RadarType.entries.associateWith{type->
        val a=o?.optJSONArray(type.name)
        if(a==null)emptySet() else (0 until a.length()).map{a.getString(it)}.toSet()
    }.also(::validate)
    /** Missing selected evidence blocks admission instead of fabricating a negative result. */
    fun allowed(snapshot:StockSnapshot,type:RadarType,ids:Set<String>):Boolean{
        val metrics=RuleMetrics(snapshot)
        return forRadar(type).filter{it.id in ids}.all{it.test(metrics)==false}
    }
}
