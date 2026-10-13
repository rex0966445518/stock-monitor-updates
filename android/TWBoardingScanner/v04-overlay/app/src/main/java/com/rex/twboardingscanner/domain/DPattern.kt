package com.rex.twboardingscanner.domain

import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * D 主力進場: historical price/volume evidence, not a claim to identify an actual institution.
 * All ranges are TRADING DAYS; limit-up is only an exchange tick-estimate (special
 * stocks / unusual price limits require separate official confirmation).
 */
data class DPatternConfig(
    val lookback:Int=20,
    val minimumLimitHits:Int=1,
    val bullishBars:Int=4,
    val volumeDays:Int=3,
    val volumeBaseDays:Int=20,
    val volumeMultiple:Double=2.0
){
    fun validated():DPatternConfig {
        require(lookback in 4..120){"觀察交易日限 4～120"}
        require(minimumLimitHits in 1..lookback){"漲停次數須介於 1 和觀察交易日"}
        require(bullishBars in 2..lookback){"連陽根數須介於 2 和觀察交易日"}
        require(volumeDays in 1..10){"連續放量日數限 1～10"}
        require(volumeBaseDays in 5..120){"放量前均量基期限 5～120 交易日"}
        require(volumeMultiple.isFinite()&&volumeMultiple in 1.0..20.0){"放量倍數限 1～20 倍"}
        return this
    }
    fun json()=JSONObject().put("lookback",lookback).put("minimumLimitHits",minimumLimitHits)
        .put("bullishBars",bullishBars).put("volumeDays",volumeDays)
        .put("volumeBaseDays",volumeBaseDays).put("volumeMultiple",volumeMultiple)
    companion object{
        val DEFAULT=DPatternConfig()
        fun read(o:JSONObject?):DPatternConfig=if(o==null)DEFAULT else
            DPatternConfig(o.optInt("lookback",20),o.optInt("minimumLimitHits",1),
                o.optInt("bullishBars",4),o.optInt("volumeDays",3),
                o.optInt("volumeBaseDays",20),o.optDouble("volumeMultiple",2.0)).validated()
    }
}
data class DGap(val date:LocalDate,val lower:Double,val upper:Double,val unfilled:Boolean)
data class DCandles(val from:LocalDate,val to:LocalDate,val fromPrice:Double,val toPrice:Double)
data class DDayVolume(val date:LocalDate,val ratio:Double)
data class DPatternEvidence(
    val limitDates:List<LocalDate>,
    val bullish:DCandles?,
    val latestGap:DGap?,
    val latestUnfilledGap:DGap?,
    val volumes:List<DDayVolume>,
    val benchmarkOutperformance:Boolean?,
    val stockGainPct:Double?,
    val marketGainPct:Double?,
    val config:DPatternConfig,
    val enoughLimitHistory:Boolean,
    val enoughBullishHistory:Boolean,
    val enoughGapHistory:Boolean,
    val enoughVolumeHistory:Boolean
){
    fun format():String{
        fun p(n:Double)=String.format(Locale.TAIWAN,"%.2f",n)
        val gap=latestUnfilledGap?:latestGap
        val bullishText=bullish?.let{"${it.from} → ${it.to}（股價 ${p(it.fromPrice)} → ${p(it.toPrice)}）"}?:"未偵測"
        val gapText=gap?.let{"${it.date} · 下緣 ${p(it.lower)}／上緣 ${p(it.upper)} · ${if(it.unfilled)"尚未完全回補" else "已回補"}"}?:"未偵測"
        return listOf(
            "D｜主力進場技術形態（不代表已確認主力買超）",
            "估算漲停收盤：${limitDates.joinToString("、").ifBlank{"未偵測"}}",
            "連陽起訖：$bullishText",
            "向上缺口：$gapText",
            "最近 ${config.volumeDays} 個交易日量比：" + volumes.joinToString("／"){"${it.date} ×${p(it.ratio)}"}.ifBlank{"資料不足"},
            "連陽個股／大盤漲幅：${stockGainPct?.let{p(it)+"%"}?:"無資料"}／${marketGainPct?.let{p(it)+"%"}?:"待查核"}",
            "漲停以日 K 收盤達一般漲停估算價判斷，不計只盤中觸及後回落；特殊漲跌幅限制需官方核對；大盤比較僅用同期已收盤資料"
        ).joinToString("\n")
    }
}
object DPattern {
    const val LIMIT="d_limit";const val GREEN="d_green";const val GAP="d_gap"
    const val UNFILLED="d_unfilled";const val VOLUME="d_volume";const val RELATIVE="d_relative"
    val DEFAULT_ENABLED=setOf(LIMIT,GREEN,GAP,VOLUME)
    private fun date(b:DailyBar)=RuleMetrics.tradingDate(b.time)
    fun inspect(raw:List<DailyBar>,settings:DPatternConfig=DPatternConfig.DEFAULT,
                benchmark:Map<LocalDate,Double>?=null):DPatternEvidence {
        val c=settings.validated()
        val bars=raw.sortedBy{it.time}.distinctBy(::date)
        val limitValid=bars.size>=c.lookback+1
        val window=bars.takeLast(c.lookback)
        val positions=bars.withIndex().associate{date(it.value) to it.index}
        val limitDates=window.mapNotNull{day->
            val i=positions.getValue(date(day))
            val previous=bars.getOrNull(i-1)?.close?.takeIf{it>0}?:return@mapNotNull null
            if(day.close+1e-7>=RuleMetrics.estimatedLimit(previous))date(day) else null
        }
        val greenValid=bars.size>=c.lookback
        // latest qualifying run wholly inside lookback; stores actual first/last K dates.
        var bullish:DCandles?=null
        var count=0
        var first=0
        window.forEachIndexed{i,b->
            if(b.close>b.open){
                if(count==0)first=i
                count++
                if(count>=c.bullishBars){
                    val initial=window[first]
                    bullish=DCandles(date(initial),date(b),initial.open,b.close)
                }
            }else count=0
        }
        val gapValid=bars.size>=c.lookback+1
        val gaps=window.mapNotNull{today->
            val idx=positions.getValue(date(today))
            val older=bars.getOrNull(idx-1)?:return@mapNotNull null
            if(today.low<=older.high+1e-9)return@mapNotNull null
            // The gap is fully filled if ANY LATER low touches its lower edge.
            val open=bars.drop(idx+1).none{it.low<=older.high+1e-9}
            DGap(date(today),older.high,today.low,open)
        }
        val volValid=bars.size>=c.volumeBaseDays+c.volumeDays
        val volumes=if(!volValid)emptyList() else {
            val baseline=bars.dropLast(c.volumeDays).takeLast(c.volumeBaseDays)
                .map{it.volumeShares.toDouble()}.average()
            if(baseline<=0)emptyList() else bars.takeLast(c.volumeDays)
                .map{DDayVolume(date(it),it.volumeShares/baseline)}
        }
        val benchmarkPeriod=bullish?.let{
            val before=bars.getOrNull((positions[it.from]?:0)-1)?.close
            val after=bars.getOrNull(positions[it.to]?:-1)?.close
            val beforeMarket=benchmark?.let{map->
                // Compare the session immediately before first green vs end of streak.
                val prevDate=bars.getOrNull((positions[it.from]?:0)-1)?.let(::date)
                if(prevDate==null)null else map[prevDate]
            }
            val afterMarket=benchmark?.get(it.to)
            val stock=if(before!=null&&after!=null&&before>0)(after/before-1)*100 else null
            val market=if(beforeMarket!=null&&afterMarket!=null&&beforeMarket>0)
                (afterMarket/beforeMarket-1)*100 else null
            Triple(stock,market,if(stock!=null&&market!=null)stock>market else null)
        }
        return DPatternEvidence(limitDates,bullish,gaps.lastOrNull(),gaps.lastOrNull{it.unfilled},
            volumes,benchmarkPeriod?.third,benchmarkPeriod?.first,benchmarkPeriod?.second,c,
            limitValid,greenValid,gapValid,volValid)
    }
    fun checks(e:DPatternEvidence):Map<String,Boolean?> = mapOf(
        LIMIT to if(!e.enoughLimitHistory)null else e.limitDates.size>=e.config.minimumLimitHits,
        GREEN to if(!e.enoughBullishHistory)null else e.bullish!=null,
        GAP to if(!e.enoughGapHistory)null else e.latestGap!=null,
        UNFILLED to if(!e.enoughGapHistory)null else e.latestUnfilledGap!=null,
        VOLUME to if(!e.enoughVolumeHistory||e.volumes.size!=e.config.volumeDays)null
            else e.volumes.all{it.ratio+1e-9>=e.config.volumeMultiple},
        RELATIVE to e.benchmarkOutperformance
    )
}
