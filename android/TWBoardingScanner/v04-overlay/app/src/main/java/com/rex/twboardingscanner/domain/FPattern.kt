package com.rex.twboardingscanner.domain

import org.json.JSONObject
import java.util.Locale

data class FPatternConfig(val days:Int=1,val volumePct:Double=80.0,val averageDays:Int=5,val averagePct:Double=80.0,val minLots:Int=1000,val maDays:Int=5,val gainDays:Int=5,val maxGainPct:Double=10.0){
    fun validated():FPatternConfig{
        require(days in 1..60 && averageDays in 1..120 && maDays in 1..120 && gainDays in 1..120){"天數須在有效範圍內（連續1～60，其餘1～120）"}
        require(volumePct.isFinite()&&volumePct>0&&volumePct<100){"前日量比例須大於0且小於100%"}
        require(averagePct.isFinite()&&averagePct>0&&averagePct<=100){"均量比例須大於0且不超過100%"}
        require(minLots in 1..10000000 && maxGainPct.isFinite()&&maxGainPct in 0.0..1000.0){"最低張數或漲幅上限不正確"}
        return this
    }
    fun json()=JSONObject().put("days",days).put("volumePct",volumePct).put("averageDays",averageDays).put("averagePct",averagePct).put("minLots",minLots).put("maDays",maDays).put("gainDays",gainDays).put("maxGainPct",maxGainPct)
    companion object{
        val DEFAULT=FPatternConfig()
        fun read(o:JSONObject?)=if(o==null)DEFAULT else FPatternConfig(o.optInt("days",1),o.optDouble("volumePct",80.0),o.optInt("averageDays",5),o.optDouble("averagePct",80.0),o.optInt("minLots",1000),o.optInt("maDays",5),o.optInt("gainDays",5),o.optDouble("maxGainPct",10.0)).validated()
    }
}
object FPattern{
    const val EVENT="f_price_up_volume_down"
    fun rules()=listOf(
        ScanCondition(EVENT,"連續 N 日收盤上漲，且每日量 ≤ 前日指定比例"){it.fPattern.passes},
        ScanCondition("f_average","當日量 ≤ 前 N 日均量指定比例（不含當日）",true){it.fPattern.averagePass},
        ScanCondition("f_min_volume","當日成交量 ≥ 設定最低張數",true){it.fPattern.minimumPass},
        ScanCondition("f_dif","DIF 高於前一交易日",true){it.fPattern.difPass},
        ScanCondition("f_ma","收盤站上 N 日均線，且均線向上",true){it.fPattern.maPass},
        ScanCondition("f_gain","近 N 交易日累計漲幅不超過設定上限",true){it.fPattern.gainPass}
    )
    class Evidence(val bars:List<DailyBar>,val config:FPatternConfig){
        private fun valid(b:DailyBar)=b.close.isFinite()&&b.close>0&&b.volumeShares>0
        private fun recent(n:Int)=bars.takeIf{it.size>=n}?.takeLast(n)?.takeIf{it.all(::valid)}
        private fun pair(p:DailyBar,c:DailyBar)=c.close>p.close&&c.volumeShares.toDouble()<=p.volumeShares.toDouble()*config.volumePct/100+1e-8
        val passes:Boolean? get()=recent(config.days+1)?.zipWithNext()?.all{(p,c)->pair(p,c)}
        val streak:Int get()=bars.zipWithNext().asReversed().takeWhile{(p,c)->valid(p)&&valid(c)&&pair(p,c)}.size
        val gain:Double? get()=recent(2)?.let{(it.last().close/it.first().close-1)*100}
        val shrink:Double? get()=recent(2)?.let{(1-it.last().volumeShares.toDouble()/it.first().volumeShares)*100}
        val averageRatio:Double? get()=recent(config.averageDays+1)?.let{it.last().volumeShares/it.dropLast(1).map{b->b.volumeShares.toDouble()}.average()*100}
        val averagePass:Boolean? get()=averageRatio?.let{it<=config.averagePct+1e-9}
        val minimumPass:Boolean? get()=recent(1)?.let{it.last().volumeShares>=config.minLots.toLong()*1000}
        val difPass:Boolean? get(){if(bars.size<31||bars.any{!it.close.isFinite()||it.close<=0})return null;val closes=bars.map{it.close};return TechnicalCalculator().macd(closes)?.difRising}
        val maPass:Boolean? get()=recent(config.maDays+1)?.let{val old=it.dropLast(1).map{b->b.close}.average();val now=it.drop(1).map{b->b.close}.average();it.last().close>now&&now>old}
        val gainPass:Boolean? get()=recent(config.gainDays+1)?.let{(it.last().close/it.first().close-1)*100<=config.maxGainPct+1e-9}
        fun format():String{
            fun pct(v:Double?)=v?.let{String.format(Locale.TAIWAN,"%.2f%%",it)}?:"待查核"
            return "F 縮量上漲 · 完整日K ${bars.lastOrNull()?.let{RuleMetrics.tradingDate(it.time)}}\n漲幅 ${pct(gain)} · 較前日縮量 ${pct(shrink)} · 相對前${config.averageDays}日均量 ${pct(averageRatio)}\n連續符合 $streak 日／要求 ${config.days} 日；每日量 ≤ 前日 ${config.volumePct}%"
        }
    }
    fun inspect(bars:List<DailyBar>,config:FPatternConfig=FPatternConfig.DEFAULT)=Evidence(bars,config.validated())
}
