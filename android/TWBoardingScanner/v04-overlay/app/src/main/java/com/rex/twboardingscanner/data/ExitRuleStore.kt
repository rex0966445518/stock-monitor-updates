package com.rex.twboardingscanner.data

import android.content.Context
import com.rex.twboardingscanner.domain.*
import org.json.JSONArray

class ExitRuleStore(c:Context) {
    private val prefs=c.getSharedPreferences("exit_rules_v1",0)
    fun read(scope:StockScope)=prefs.getString(scope.name,null)?.let{ExitRules.read(JSONArray(it))}?:emptyList()
    fun save(scope:StockScope,rules:List<ExitRule>){check(prefs.edit().putString(scope.name,ExitRules.json(rules).toString()).commit()){"無法儲存下車條件"}}
}
