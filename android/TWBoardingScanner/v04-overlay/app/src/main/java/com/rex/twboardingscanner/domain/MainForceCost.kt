package com.rex.twboardingscanner.domain

import java.time.LocalDate

/**
 * A transparent 20-session volume-weighted *proxy*, not a broker's actual
 * large-account cost and not the proprietary figure from another application.
 *
 * Typical daily price (high + low + close) / 3 is weighted by executed shares.
 * Only dated daily OHLCV bars already available to the signal are considered;
 * partial, zero-volume or invalid candles yield UNKNOWN, never a free pass.
 */
object MainForceCost {
    const val DAYS=20

    data class Estimate(val price:Double,val from:LocalDate,val to:LocalDate,val totalShares:Double) {
        fun distancePct(lastClose:Double):Double?=
            if(lastClose.isFinite()&&lastClose>0.0)
                (lastClose/price-1.0)*100.0 else null
    }

    fun estimate(dailyBars:List<DailyBar>,days:Int=DAYS):Estimate? {
        require(days>0)
        val known=dailyBars.sortedBy{it.time}.distinctBy{RuleMetrics.tradingDate(it.time)}
        if(known.size<days)return null
        val last=known.takeLast(days)
        var totalShares=0.0
        var amount=0.0
        for(bar in last){
            if(!listOf(bar.open,bar.high,bar.low,bar.close).all{it.isFinite()&&it>0.0}||
                bar.volumeShares<=0L||bar.high<bar.low||
                bar.high<maxOf(bar.open,bar.close)||bar.low>minOf(bar.open,bar.close))return null
            val typical=(bar.high+bar.low+bar.close)/3.0
            val volume=bar.volumeShares.toDouble()
            totalShares+=volume
            amount+=typical*volume
        }
        if(!totalShares.isFinite()||totalShares<=0.0||!amount.isFinite())return null
        val price=amount/totalShares
        if(!price.isFinite()||price<=0.0)return null
        return Estimate(price,RuleMetrics.tradingDate(last.first().time),
            RuleMetrics.tradingDate(last.last().time),totalShares)
    }

    fun below(close:Double,estimated:Estimate?):Boolean? {
        if(!close.isFinite()||close<=0.0)return null
        return estimated?.let{close<it.price}
    }
}
