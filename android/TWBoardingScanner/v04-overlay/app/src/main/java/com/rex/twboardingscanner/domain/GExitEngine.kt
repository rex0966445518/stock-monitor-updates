package com.rex.twboardingscanner.domain

/**
 * G3–G5 inspect COMPLETED daily bars only. A decision at close t can be executed
 * no earlier than t+1's opening auction. No look-ahead to today's high or low.
 */
enum class GAction { STOP, G5_EXIT, G4_REDUCE, G3_REDUCE, G4_REBUY, G3_REBUY }
data class GDecision(val action:GAction,val signalDate:java.time.LocalDate,val detail:String)
object GExitEngine {
    fun decide(bars:List<DailyBar>,cfg:GPatternConfig,entry:Double,remaining:Int,
               g3Reduced:Int=0,g4Reduced:Int=0):GDecision? {
        cfg.validated()
        if(remaining<=0 || !entry.isFinite() || entry<=0 || bars.size<5)return null
        // Drop invalid/missing bars rather than silently interpolating a time series.
        if(bars.takeLast(maxOf(5,cfg.g5Days+4)).any{!it.close.isFinite()||it.close<=0})return null
        val closes=bars.map{it.close};val last=bars.last()
        val ma5=closes.takeLast(5).average();if(ma5<=0)return null
        val distance=(last.close/ma5-1)*100
        val gainPct=(last.close/entry-1)*100
        val date=RuleMetrics.tradingDate(last.time)
        fun signal(action:GAction,detail:String)=GDecision(action,date,detail)
        if(cfg.stopEnabled&&gainPct<=-cfg.stopLossPct+1e-8)
            return signal(GAction.STOP,"G 額外停損：完成日K虧損達 ${cfg.stopLossPct}%")
        if(cfg.g5Enabled&&bars.size>=cfg.g5Days+4&&
            (0 until cfg.g5Days).all{i->
                val j=bars.lastIndex-i
                bars[j].close<closes.subList(j-4,j+1).average()-1e-8
            })return signal(GAction.G5_EXIT,"G5 連續 ${cfg.g5Days} 根完整日K收盤跌破5MA")
        if(cfg.g4Enabled&&g4Reduced==0&&last.close<ma5-1e-8&&remaining>1)
            return signal(GAction.G4_REDUCE,"G4 完整日K收盤跌破5MA，次一日開盤減碼")
        if(cfg.g3Enabled&&g3Reduced==0&&gainPct>=cfg.g3ProfitPct-1e-8
            &&distance>=cfg.g3DistancePct-1e-8&&remaining>1)
            return signal(GAction.G3_REDUCE,"G3 獲利 ${"%.2f".format(gainPct)}%／乖離 ${"%.2f".format(distance)}%")
        if(cfg.g4RebuyEnabled&&g4Reduced>0&&last.close>=ma5-1e-8)
            return signal(GAction.G4_REBUY,"G4 收盤重新站穩5MA，次一開盤回補")
        if(cfg.g3RebuyEnabled&&g3Reduced>0&&distance>=0.0&&distance<=cfg.g3RebuyMaxPct+1e-8)
            return signal(GAction.G3_REBUY,"G3 回踩5MA，收盤上方 ${"%.2f".format(distance)}%")
        return null
    }
}
