package com.rex.twboardingscanner.domain

import org.json.JSONArray
import org.json.JSONObject

enum class StockScope(val label:String){ SCANNER("主頁篩查器"), BACKTEST("歷史回測"), ROBOT("自動測試機器人") }
enum class StockDecision { ALLOW, BANNED, LIMITED }
/** Immutable entry policy. Limits use the quote on the simulated day, never today's quote. */
data class StockPolicy(val banned:Set<String> = emptySet(),val ceiling:Double?=null,val overrides:Map<StockScope,Set<String>> = emptyMap()) {
    fun validate(){
        require(ceiling==null||(ceiling.isFinite()&&ceiling>0)){"限價請填大於 0 的金額，留白代表不限"}
        require((banned+overrides.values.flatten()).all{it.matches(Regex("[1-9][0-9]{3}"))}){"股號須為 4 位數"}
    }
    fun decision(code:String,price:Double,scope:StockScope):StockDecision=when{
        code in banned->StockDecision.BANNED
        !price.isFinite()||price<=0->StockDecision.LIMITED
        ceiling!=null&&price>ceiling&&code !in overrides[scope].orEmpty()->StockDecision.LIMITED
        else->StockDecision.ALLOW
    }
    fun active()=banned.isNotEmpty()||ceiling!=null||overrides.values.any{it.isNotEmpty()}
    fun json():JSONObject { validate();return JSONObject().put("version",1).put("banned",JSONArray(banned.sorted())).put("ceiling",ceiling?:JSONObject.NULL)
        .put("overrides",JSONObject().apply{StockScope.entries.forEach{put(it.name,JSONArray(overrides[it].orEmpty().sorted()))}}) }
    fun summary()="禁股 ${banned.size} 檔 · 最高股價 "+(ceiling?.let{"${com.rex.twboardingscanner.backtest.btPercent(it)} 元"}?:"不限")+
        "\n"+"放行：主頁 ${overrides[StockScope.SCANNER].orEmpty().size} · 回測 ${overrides[StockScope.BACKTEST].orEmpty().size} · 機器人 ${overrides[StockScope.ROBOT].orEmpty().size} 檔"
    companion object {
        fun read(o:JSONObject?):StockPolicy {
            if(o==null)return StockPolicy()
            require(o.getInt("version")==1){"禁股／限價設定版本不相容"}
            fun codes(a:JSONArray?)=if(a==null)emptySet() else (0 until a.length()).map{a.getString(it)}.toSet()
            return StockPolicy(codes(o.getJSONArray("banned")),if(o.isNull("ceiling"))null else o.getDouble("ceiling"),
                StockScope.entries.mapNotNull{s->codes(o.getJSONObject("overrides").optJSONArray(s.name)).takeIf{it.isNotEmpty()}?.let{s to it}}.toMap()).also{it.validate()}
        }
    }
}
