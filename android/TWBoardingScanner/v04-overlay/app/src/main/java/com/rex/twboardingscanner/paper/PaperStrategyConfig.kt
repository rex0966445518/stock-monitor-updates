package com.rex.twboardingscanner.paper

import android.content.Context
import com.rex.twboardingscanner.backtest.BtSettings
import com.rex.twboardingscanner.backtest.BtSettingsCodec
import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.data.ExitRuleStore
import com.rex.twboardingscanner.data.StockPolicyStore
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Full set of editable backtest controls, interpreted for a live-only PAPER simulator.
 * Dates are the activation window for future simulated fills, not historical replay.
 * Every chosen ABC predicate must PASS: unknown financial facts never default to PASS.
 */
object PaperStrategyConfig {
    fun defaults(context:Context,capital:Double):BtSettings {
        val start=LocalDate.now(RuleMetrics.TAIPEI)
        val base=BtSettingsCodec.capture(context,start,start.plusDays(365),capital,"")
        return base.copy(
            stockScope=StockScope.SCANNER,
            stockPolicy=StockPolicyStore(context).read(),
            maxHoldingStocks=5,targetNetPct=6.0,
            exitRules=ExitRuleStore(context).read(StockScope.SCANNER),
            marketGuardVersion=MarketCrashGuard.VERSION,
            holdProfitUntilMa5Break=ExitRuleStore(context).readProfitTrendHold(StockScope.SCANNER)
        )
    }
    fun validate(settings:BtSettings,now:LocalDate=LocalDate.now(RuleMetrics.TAIPEI)){
        settings.validateTrading()
        require(settings.strategyVersion==4){"請使用最新交易模型"}
        require(settings.stockScope==StockScope.SCANNER){"模擬交易使用主頁共用禁買／限價名單"}
        require(settings.marketGuardVersion>0){"自買自投必須保留大盤暴跌保護"}
        require(settings.capital in 50000.0..100000000.0&&settings.capital.isFinite()){"本金需在 50,000～100,000,000 元"}
        require(settings.start>=LocalDate.of(2016,1,1)){"起始日期不得早於 2016 年"}
        require(settings.start<=settings.end){"結束日期不得早於起始日期"}
        require(ChronoUnit.DAYS.between(settings.start,settings.end)<=730){"日期區間最多 730 天"}
        // Do NOT reject dates before today: keep an imported backtest snapshot visible,
        // but no paper buys may occur outside its activation dates.
        require(settings.sectors.isNotEmpty()){"至少選擇一種產業"}
        require(settings.enabledRadars.any{settings.rules[it].orEmpty().isNotEmpty()}){"請至少開啟一個含條件的入場區域"}
        require(settings.rules.keys==RadarType.entries.toSet()){"ABCDEFG 條件不完整"}
        settings.rules.forEach{(type,ids)->
            require(ids.all{it in ScanConditions.forRadar(type).map{r->r.id}}){"ABCDEFG 條件版本不符"}
        }
        require(settings.codes.split(Regex("[,，\\s]+")).filter{it.isNotBlank()}
            .all{it.matches(Regex("[1-9][0-9]{3}"))}){"股號須為四位數字，請用逗號分隔"}
    }
    fun allowedCodes(settings:BtSettings):Set<String> =
        settings.codes.split(Regex("[,，\\s]+")).filter{it.isNotBlank()}.toSet()
    fun matches(r:SignalResult,settings:BtSettings):Boolean {
        if(r.radarType !in settings.enabledRadars)return false
        if(r.sector !in settings.sectors)return false
        val codes=allowedCodes(settings)
        if(codes.isNotEmpty()&&r.code !in codes)return false
        if(!ExclusionConditions.allowed(r.snapshot,r.radarType,settings.exclusions.getValue(r.radarType)))return false
        val selected=settings.rules[r.radarType].orEmpty()
        if(selected.isEmpty()||(r.radarType==RadarType.G_MA5_WAVE&&!selected.containsAll(GPattern.REQUIRED_IDS)))return false
        val checks=if(r.radarType==RadarType.B_DEEP_REVERSAL)ScoringEngine().evaluateB(r.snapshot.copy(bConfig=settings.bConfig),selected).checks else if(r.radarType==RadarType.E_DIF_TOUCH)ScoringEngine().evaluateE(r.snapshot.copy(eConfig=settings.eConfig),selected).checks else if(r.radarType==RadarType.F_VOLUME_CONTRACTION)ScoringEngine().evaluateF(r.snapshot.copy(fConfig=settings.fConfig),selected).checks else if(r.radarType==RadarType.G_MA5_WAVE)ScoringEngine().evaluateG(r.snapshot.copy(gConfig=settings.gConfig),selected).checks else r.checks
        val observed=checks.associateBy{it.id}
        return selected.all{observed[it]?.state==CheckState.PASS}
    }
    fun candidates(results:List<SignalResult>,settings:BtSettings,policy:StockPolicy,now:Long):List<PaperCandidate>{
        validate(settings)
        return results.asSequence().filter{matches(it,settings)}
            .filter{policy.decision(it.code,it.snapshot.sourceStock?.close?:it.snapshot.price,StockScope.SCANNER)==StockDecision.ALLOW}
            .mapNotNull{r->
                val market=r.snapshot.sourceStock?.market?:return@mapNotNull null
                if(!r.code.matches(Regex("[1-9][0-9]{3}")))return@mapNotNull null
                PaperCandidate(r.code,r.name,if(market==Market.TWSE)"tse" else "otc",r.radarType.name.take(1),100,now)
            }.distinctBy{it.code}.toList()
    }
}
