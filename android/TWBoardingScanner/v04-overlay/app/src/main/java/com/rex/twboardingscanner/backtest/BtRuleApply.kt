package com.rex.twboardingscanner.backtest

import android.content.Context
import com.rex.twboardingscanner.domain.*
import org.json.JSONObject
import java.time.LocalDate

/** Validate everything before changing either the scanner or the next backtest draft. */
object BtRuleApply {
    const val REVISION="applied_snapshot_revision"
    const val BACKTEST_REVISION="backtest_snapshot_revision"
    fun revision(c:Context):String {
        val prefs=c.getSharedPreferences("scanner_filters",0)
        return prefs.getString(REVISION,"").orEmpty()+":"+prefs.getString(BACKTEST_REVISION,"").orEmpty()
    }
    fun apply(c:Context,snapshot:JSONObject):BtSettings = applyTo(c,snapshot,null,false)
    /** A null target preserves the older snapshot dialog's combined apply behavior. */
    fun applyTo(c:Context,snapshot:JSONObject,target:StockScope?,restorePolicy:Boolean=true):BtSettings {
        require(target==null||target==StockScope.SCANNER||target==StockScope.BACKTEST)
        val archived=JSONObject(snapshot.toString())
        if(restorePolicy)require(archived.optJSONObject("rules")!=null&&archived.optJSONObject("stockPolicy")!=null&&archived.has("exitRules")){"日誌缺少完整條件，無法同步"}
        val policy=com.rex.twboardingscanner.data.StockPolicyStore(c)
        val decoded=BtSettingsCodec.decode(archived)
        val s=decoded.copy(stockPolicy=if(restorePolicy)decoded.stockPolicy else policy.read(),stockScope=target?:StockScope.BACKTEST)
        require(s.strategyVersion==4){"此舊版日誌缺少目前交易限制，無法完整套用"}
        require(s.capital.isFinite()&&s.capital in 50000.0..100000000.0){"快照本金無效"}
        require(s.start>=LocalDate.of(2016,1,1)&&s.start<=s.end&&s.end<LocalDate.now(RuleMetrics.TAIPEI)&&java.time.temporal.ChronoUnit.DAYS.between(s.start,s.end)<=730){"快照日期無效"}
        require(s.sectors.isNotEmpty()){ "快照沒有產業範圍" }
        require(s.codes.split(Regex("[,，\\s]+")).filter{it.isNotBlank()}.all{it.matches(Regex("[1-9][0-9]{3}"))}){"快照股號格式無效"}
        s.stockPolicy.validate()
        if(restorePolicy&&policy.read()!=s.stockPolicy)policy.change{s.stockPolicy}
        if(target!=StockScope.SCANNER)check(BacktestStore(c).saveDraft(s.copy(stockScope=StockScope.BACKTEST))){"無法儲存回測設定"}
        val exits=com.rex.twboardingscanner.data.ExitRuleStore(c)
        (target?.let{listOf(it)}?:listOf(StockScope.SCANNER,StockScope.BACKTEST)).forEach{scope->
            exits.save(scope,s.exitRules);exits.saveProfitTrendHold(scope,s.holdProfitUntilMa5Break)
        }
        val edit=c.getSharedPreferences("scanner_filters",0).edit()
        if(target==StockScope.BACKTEST){
            check(edit.putString(BACKTEST_REVISION,java.util.UUID.randomUUID().toString()).commit()){"無法更新回測設定"}
            return s
        }
        edit.putStringSet("enabled_radars",s.enabledRadars.map{it.name}.toSet())
            .putString("d_pattern_config",s.dConfig.json().toString()).putString("e_pattern_config",s.eConfig.json().toString()).putString("f_pattern_config",s.fConfig.json().toString()).putString("b_pattern_config",s.bConfig.json().toString())
            .putString("scan_exclusions",ExclusionConditions.json(s.exclusions).toString())
        s.rules.forEach{(type,ids)->edit.putStringSet("rules_${ScanConditions.VERSION}_${type.name}",ids.toSet())}
        edit.putStringSet("enabled_sectors",s.sectors.map{it.name}.toSet())
        if(target==null)edit.putString(REVISION,java.util.UUID.randomUUID().toString())
        check(edit.commit()){"無法儲存主頁條件"}
        return s
    }
}
