package com.rex.twboardingscanner.backtest

import android.content.Context
import com.rex.twboardingscanner.domain.*
import org.json.JSONObject
import java.time.LocalDate

/** Validate everything before changing either the scanner or the next backtest draft. */
object BtRuleApply {
    const val REVISION="applied_snapshot_revision"
    fun apply(c:Context,snapshot:JSONObject):BtSettings {
        val s=BtSettingsCodec.decode(JSONObject(snapshot.toString())).copy(stockPolicy=com.rex.twboardingscanner.data.StockPolicyStore(c).read(),stockScope=StockScope.BACKTEST)
        require(s.strategyVersion==4){"此舊版日誌缺少目前交易限制，無法完整套用"}
        require(s.capital.isFinite()&&s.capital in 50000.0..100000000.0){"快照本金無效"}
        require(s.start>=LocalDate.of(2016,1,1)&&s.start<=s.end&&s.end<LocalDate.now(RuleMetrics.TAIPEI)&&java.time.temporal.ChronoUnit.DAYS.between(s.start,s.end)<=730){"快照日期無效"}
        require(s.sectors.isNotEmpty()){ "快照沒有產業範圍" }
        require(s.codes.split(Regex("[,，\\s]+")).filter{it.isNotBlank()}.all{it.matches(Regex("[1-9][0-9]{3}"))}){"快照股號格式無效"}
        check(BacktestStore(c).saveDraft(s)){"無法儲存回測設定"}
        com.rex.twboardingscanner.data.ExitRuleStore(c).save(StockScope.SCANNER,s.exitRules)
        com.rex.twboardingscanner.data.ExitRuleStore(c).save(StockScope.BACKTEST,s.exitRules)
        com.rex.twboardingscanner.data.ExitRuleStore(c).saveProfitTrendHold(StockScope.SCANNER,s.holdProfitUntilMa5Break)
        com.rex.twboardingscanner.data.ExitRuleStore(c).saveProfitTrendHold(StockScope.BACKTEST,s.holdProfitUntilMa5Break)
        val edit=c.getSharedPreferences("scanner_filters",0).edit()
        edit.putStringSet("enabled_radars",s.enabledRadars.map{it.name}.toSet())
            .putString("d_pattern_config",s.dConfig.json().toString()).putString("e_pattern_config",s.eConfig.json().toString()).putString("f_pattern_config",s.fConfig.json().toString())
            .putString("scan_exclusions",ExclusionConditions.json(s.exclusions).toString())
        s.rules.forEach{(type,ids)->edit.putStringSet("rules_${ScanConditions.VERSION}_${type.name}",ids.toSet())}
        check(edit.putStringSet("enabled_sectors",s.sectors.map{it.name}.toSet()).putString(REVISION,java.util.UUID.randomUUID().toString()).commit()){"無法儲存主頁條件"}
        return s
    }
}
