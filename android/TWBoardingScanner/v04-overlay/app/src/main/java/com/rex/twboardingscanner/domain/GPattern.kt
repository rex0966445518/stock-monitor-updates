package com.rex.twboardingscanner.domain

import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale
import kotlin.math.max

/** G1/G2 are entry filters. G3/G4/G5 manage existing positions only. */
data class GPatternConfig(
    val eventDays:Int=10, val eventCount:Int=1, val eventGainPct:Double=5.0,
    val pullbackDays:Int=5, val touchAbovePct:Double=1.0, val touchBelowPct:Double=0.5,
    val maxAbovePct:Double=2.0, val volumeDays:Int=20, val volumeMultiple:Double=1.5,
    val maRiseDays:Int=3, val minRsi:Double=50.0, val minPrice:Double=50.0, val minLots:Int=1000,
    val g3Enabled:Boolean=true, val g3DistancePct:Double=6.0, val g3ProfitPct:Double=4.0,
    val g3SellPct:Double=50.0, val g3RebuyEnabled:Boolean=true, val g3RebuyMaxPct:Double=1.0,
    val g4Enabled:Boolean=true, val g4SellPct:Double=50.0, val g4RebuyEnabled:Boolean=true,
    val g5Enabled:Boolean=true, val g5Days:Int=3, val stopEnabled:Boolean=false, val stopLossPct:Double=6.0
){
    fun validated():GPatternConfig {
        require(eventDays in 2..120 && eventCount in 1..eventDays && pullbackDays in 1..eventDays){"G1/G2 天數與次數不正確"}
        require(eventGainPct.isFinite()&&eventGainPct in 0.1..30.0){"G1 漲幅須在 0.1%～30%"}
        require(touchAbovePct.isFinite()&&touchAbovePct in 0.0..10.0&&touchBelowPct.isFinite()&&touchBelowPct in 0.0..10.0&&maxAbovePct.isFinite()&&maxAbovePct in touchAbovePct..20.0){"G2 回踩容差不正確"}
        require(volumeDays in 5..120&&volumeMultiple.isFinite()&&volumeMultiple in 0.1..20.0){"G1 量比門檻不正確"}
        require(maRiseDays in 1..20&&minRsi.isFinite()&&minRsi in 0.0..100.0){"G2 均線／RSI 設定不正確"}
        require(minPrice.isFinite()&&minPrice in 0.01..100000.0&&minLots in 1..10000000){"G 股價或張數門檻不正確"}
        require(g3DistancePct.isFinite()&&g3DistancePct in 0.0..100.0&&g3ProfitPct.isFinite()&&g3ProfitPct in 0.0..1000.0){"G3 乖離或獲利門檻不正確"}
        require(g3SellPct.isFinite()&&g3SellPct in 1.0..100.0&&g4SellPct.isFinite()&&g4SellPct in 1.0..100.0){"G3/G4 減碼比例不正確"}
        require(g3RebuyMaxPct.isFinite()&&g3RebuyMaxPct in 0.0..20.0&&g5Days in 1..20){"G3 回補或 G5 天數不正確"}
        require(stopLossPct.isFinite()&&stopLossPct in 0.1..100.0){"G 停損門檻不正確"}
        return this
    }
    fun json()=JSONObject().apply{
        put("eventDays",eventDays);put("eventCount",eventCount);put("eventGainPct",eventGainPct)
        put("pullbackDays",pullbackDays);put("touchAbovePct",touchAbovePct);put("touchBelowPct",touchBelowPct)
        put("maxAbovePct",maxAbovePct);put("volumeDays",volumeDays);put("volumeMultiple",volumeMultiple)
        put("maRiseDays",maRiseDays);put("minRsi",minRsi);put("minPrice",minPrice);put("minLots",minLots)
        put("g3Enabled",g3Enabled);put("g3DistancePct",g3DistancePct);put("g3ProfitPct",g3ProfitPct)
        put("g3SellPct",g3SellPct);put("g3RebuyEnabled",g3RebuyEnabled);put("g3RebuyMaxPct",g3RebuyMaxPct)
        put("g4Enabled",g4Enabled);put("g4SellPct",g4SellPct);put("g4RebuyEnabled",g4RebuyEnabled)
        put("g5Enabled",g5Enabled);put("g5Days",g5Days);put("stopEnabled",stopEnabled);put("stopLossPct",stopLossPct)
    }
    companion object {
        val DEFAULT=GPatternConfig()
        fun read(o:JSONObject?):GPatternConfig {
            if(o==null)return DEFAULT
            val d=DEFAULT
            return d.copy(
                eventDays=o.optInt("eventDays",d.eventDays),eventCount=o.optInt("eventCount",d.eventCount),
                eventGainPct=o.optDouble("eventGainPct",d.eventGainPct),pullbackDays=o.optInt("pullbackDays",d.pullbackDays),
                touchAbovePct=o.optDouble("touchAbovePct",d.touchAbovePct),touchBelowPct=o.optDouble("touchBelowPct",d.touchBelowPct),
                maxAbovePct=o.optDouble("maxAbovePct",d.maxAbovePct),volumeDays=o.optInt("volumeDays",d.volumeDays),
                volumeMultiple=o.optDouble("volumeMultiple",d.volumeMultiple),maRiseDays=o.optInt("maRiseDays",d.maRiseDays),
                minRsi=o.optDouble("minRsi",d.minRsi),minPrice=o.optDouble("minPrice",d.minPrice),
                minLots=o.optInt("minLots",d.minLots),g3Enabled=o.optBoolean("g3Enabled",d.g3Enabled),
                g3DistancePct=o.optDouble("g3DistancePct",d.g3DistancePct),g3ProfitPct=o.optDouble("g3ProfitPct",d.g3ProfitPct),
                g3SellPct=o.optDouble("g3SellPct",d.g3SellPct),g3RebuyEnabled=o.optBoolean("g3RebuyEnabled",d.g3RebuyEnabled),
                g3RebuyMaxPct=o.optDouble("g3RebuyMaxPct",d.g3RebuyMaxPct),g4Enabled=o.optBoolean("g4Enabled",d.g4Enabled),
                g4SellPct=o.optDouble("g4SellPct",d.g4SellPct),g4RebuyEnabled=o.optBoolean("g4RebuyEnabled",d.g4RebuyEnabled),
                g5Enabled=o.optBoolean("g5Enabled",d.g5Enabled),g5Days=o.optInt("g5Days",d.g5Days),
                stopEnabled=o.optBoolean("stopEnabled",d.stopEnabled),stopLossPct=o.optDouble("stopLossPct",d.stopLossPct)
            ).validated()
        }
    }
}

object GPattern{
    const val EVENT="g_strong_candle"
    const val PULLBACK="g_ma5_pullback"
    const val EVENT_VOLUME="g_event_volume"
    const val MA_RISE="g_ma5_rising"
    const val DIF_RISE="g_dif_rising"
    const val RSI_MIN="g_rsi_min"
    const val PRICE="g_min_price"
    const val LOTS="g_min_lots"
    val REQUIRED_IDS=setOf(EVENT,PULLBACK)
    fun rules()=listOf(
        ScanCondition(EVENT,"G1 近期大陽線（天數／次數／漲幅% 可調）"){it.gPattern.eventPass},
        ScanCondition(PULLBACK,"G2 大陽線後回踩 5 日線（回踩天數／容差% 可調）"){it.gPattern.pullbackPass},
        ScanCondition(PRICE,"G 基礎：股價 ≥ 指定元數",true){it.gPattern.pricePass},
        ScanCondition(LOTS,"G 基礎：成交量 ≥ 指定張數",true){it.gPattern.volumePass},
        ScanCondition(EVENT_VOLUME,"G1 大陽線成交量 ≥ 先前 N 日均量指定倍數",true){it.gPattern.eventVolumePass},
        ScanCondition(MA_RISE,"G2 5 日均線連續 N 日上揚",true){it.gPattern.maRisePass},
        ScanCondition(DIF_RISE,"G2 DIF 高於前一交易日",true){if(it.s.bars.size<35)null else it.s.difRising},
        ScanCondition(RSI_MIN,"G2 RSI(14) ≥ 指定門檻",true){it.s.rsi?.let{r->r>=it.s.gConfig.minRsi}}
    )
    class Evidence(private val bars:List<DailyBar>,val config:GPatternConfig){
        private fun valid(b:DailyBar)=b.open.isFinite()&&b.open>0&&b.high.isFinite()&&b.low.isFinite()&&b.close.isFinite()&&b.close>0&&b.volumeShares>0&&b.low>0&&b.high>=b.low
        private val ready=bars.size>=config.eventDays+2&&bars.all(::valid)
        private val last=bars.lastOrNull()
        private fun ma5At(i:Int):Double?=if(i<4)null else bars.subList(i-4,i+1).map{it.close}.average()
        val ma5:Double? get()=if(!ready)null else ma5At(bars.lastIndex)
        private val events:List<Int> by lazy(LazyThreadSafetyMode.NONE) {
            if(!ready)emptyList() else (max(1,bars.lastIndex-config.eventDays)..bars.lastIndex-1).filter{i->
                val p=bars[i-1];val c=bars[i]
                c.close>c.open&&(c.close/p.close-1)*100>=config.eventGainPct-1e-8
            }
        }
        val eventPass:Boolean? get()=if(!ready)null else events.size>=config.eventCount
        private val anchor:Int? get()=events.lastOrNull{bars.lastIndex-it in 1..config.pullbackDays}
        val eventDate:LocalDate? get()=anchor?.let{RuleMetrics.tradingDate(bars[it].time)}
        val eventGain:Double? get()=anchor?.let{(bars[it].close/bars[it-1].close-1)*100}
        val touchDistance:Double? get()=if(!ready||ma5==null)null else (last!!.low/ma5!!-1)*100
        val distance:Double? get()=if(!ready||ma5==null)null else (last!!.close/ma5!!-1)*100
        val pullbackPass:Boolean? get() {
            if(!ready)return null
            val moving=ma5?:return null
            if(anchor==null)return false
            val low=last!!.low;val close=last.close
            return low>=moving*(1-config.touchBelowPct/100.0)-1e-8 &&
                low<=moving*(1+config.touchAbovePct/100.0)+1e-8 &&
                close>=moving-1e-8 && close<=moving*(1+config.maxAbovePct/100.0)+1e-8
        }
        val pricePass:Boolean? get()=if(!ready)null else last!!.close>=config.minPrice-1e-8
        val volumePass:Boolean? get()=if(!ready)null else last!!.volumeShares>=config.minLots.toLong()*1000
        val eventVolumeRatio:Double? get(){
            val i=anchor?:return null
            if(i<config.volumeDays)return null
            val prior=bars.subList(i-config.volumeDays,i).map{it.volumeShares.toDouble()}
            if(prior.any{it<=0.0})return null
            val avg=prior.average()
            return if(avg<=0.0)null else bars[i].volumeShares/avg
        }
        val eventVolumePass:Boolean? get()=if(!ready)null else if(anchor==null)false else eventVolumeRatio?.let{it>=config.volumeMultiple-1e-8}
        val maRisePass:Boolean? get(){
            if(!ready||bars.size<config.maRiseDays+5)return null
            return (0 until config.maRiseDays).all{i->ma5At(bars.lastIndex-i)!!>ma5At(bars.lastIndex-i-1)!!}
        }
        fun format():String{
            fun number(v:Double?)=v?.let{String.format(Locale.TAIWAN,"%.2f",it)}?:"待查核"
            return "G 大陽線・5MA回踩 · 大陽線 "+(eventDate?.toString()?:"待出現")+
                "（漲幅 "+number(eventGain)+"%）\n5MA "+number(ma5)+
                " · 收盤乖離 "+number(distance)+"% · 最低價乖離 "+number(touchDistance)+
                "%\nG1 "+(eventPass?.toString()?:"待查核")+" · G2 "+(pullbackPass?.toString()?:"待查核")+
                " · 大陽線量比 "+number(eventVolumeRatio)+" 倍"
        }
    }
    fun inspect(bars:List<DailyBar>,config:GPatternConfig=GPatternConfig.DEFAULT)=Evidence(bars,config.validated())
}
