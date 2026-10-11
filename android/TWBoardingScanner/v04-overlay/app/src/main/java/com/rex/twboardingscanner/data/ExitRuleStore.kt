package com.rex.twboardingscanner.data

import android.content.Context
import com.rex.twboardingscanner.domain.*
import org.json.JSONArray

class ExitRuleStore(c:Context) {
    private val prefs=c.getSharedPreferences("exit_rules_v1",0)
    fun read(scope:StockScope)=prefs.getString(scope.name,null)?.let{ExitRules.read(JSONArray(it))}?:emptyList()
    fun save(scope:StockScope,rules:List<ExitRule>){check(prefs.edit().putString(scope.name,ExitRules.json(rules).toString()).commit()){"無法儲存下車條件"}}
    /** Stored per page; pre-existing strategies default to disabled. */
    fun readProfitTrendHold(scope:StockScope)=prefs.getBoolean("profit_trend_ma5_"+scope.name,false)
    fun saveProfitTrendHold(scope:StockScope,enabled:Boolean){
        check(prefs.edit().putBoolean("profit_trend_ma5_"+scope.name,enabled).commit()){"無法儲存 RSI／5 日線續抱規則"}
    }
}
