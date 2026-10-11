package com.rex.twboardingscanner.domain

import java.time.LocalDate

/** Daily RSI(14)/MA5 exit: evaluate only completed trading days, never future close. */
object ProfitTrendHold {
    data class State(val date:LocalDate,val close:Double,val ma5:Double,val rsi14:Double) {
        val canHoldOnTarget:Boolean get()=rsi14>50.0&&close>=ma5
        val closedBelowMa5:Boolean get()=close<ma5
    }
    fun fromCompleted(bars:List<DailyBar>):State? {
        val ordered=bars.sortedBy{it.time}.distinctBy{RuleMetrics.tradingDate(it.time)}
        if(ordered.size<15)return null
        val recent=ordered.takeLast(15)
        if(recent.any{!it.close.isFinite()||it.close<=0.0})return null
        val closes=recent.map{it.close}
        val ma5=closes.takeLast(5).average()
        val rsi14=TechnicalCalculator().rsi(closes,14)?:return null
        if(!ma5.isFinite()||!rsi14.isFinite())return null
        return State(RuleMetrics.tradingDate(recent.last().time),closes.last(),ma5,rsi14)
    }
}
