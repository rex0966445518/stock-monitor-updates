package com.rex.twboardingscanner.domain

import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

data class MarketIndexBar(val date:LocalDate,val close:Double,val change:Double)
data class MarketGuardDecision(val state:String,val asOf:LocalDate?=null,val close:Double?=null,val change:Double?=null,
    val ma5:Double?=null,val risingDays:Int=0,val trigger:LocalDate?=null,val reason:String=""){
    val canBuy:Boolean get()=state=="NORMAL"
    val title:String get()=when(state){"NORMAL"->"大盤保護 · 可買入";"PAUSED"->"大盤保護 · 暫停買入";else->"大盤保護 · 待確認，暫停買入"}
    fun detail():String {
        val quote=asOf?.let{d->"依據 $d 收盤 ${String.format(Locale.TAIWAN,"%,.2f",close)} 點（${String.format(Locale.TAIWAN,"%+,.2f",change)}）"}.orEmpty()
        return listOf(quote,reason,if(!canBuy&&asOf!=null)"連漲 $risingDays / 2 日 · 5 日線 ${ma5?.let{String.format(Locale.TAIWAN,"%,.2f",it)}?:"資料不足"}" else "").filter{it.isNotBlank()}.joinToString("\n")
    }
    fun json()=JSONObject().put("state",state).put("asOf",asOf?.toString()?:JSONObject.NULL).put("close",close?:JSONObject.NULL)
        .put("change",change?:JSONObject.NULL).put("ma5",ma5?:JSONObject.NULL).put("risingDays",risingDays)
        .put("trigger",trigger?.toString()?:JSONObject.NULL).put("reason",reason).put("title",title).put("detail",detail())
}

/** Only completed sessions BEFORE the execution date may change its permission. */
object MarketCrashGuard {
    const val VERSION=1
    const val SUMMARY="加權指數單日收跌超過 1,000 點，次一交易日起暫停新買入；連續 2 個交易日收漲且收盤高於 5 日均線，次一交易日恢復。保護期間仍執行原有賣出規則。"
    fun unknown(reason:String)=MarketGuardDecision("UNKNOWN",reason=reason)
    fun timeline(raw:List<MarketIndexBar>):Map<LocalDate,MarketGuardDecision>{
        require(raw.map{it.date}.distinct().size==raw.size){"大盤日期重複"}
        val history=mutableListOf<MarketIndexBar>();val result=linkedMapOf<LocalDate,MarketGuardDecision>()
        var state="UNKNOWN";var trigger:LocalDate?=null;var rising=0
        raw.sortedBy{it.date}.forEach{bar->
            require(bar.close.isFinite()&&bar.close>0&&bar.change.isFinite()){"大盤指數無效"}
            val prior=history.lastOrNull()
            if(prior!=null&&abs((bar.close-prior.close)-bar.change)>0.03){
                // Missing/changed source rows cannot silently release an existing protection.
                history.clear();rising=0;if(state!="PAUSED")state="UNKNOWN"
            }
            history+=bar
            rising=if(bar.change>0)rising+1 else 0
            val ma=if(history.size>=5)history.takeLast(5).map{it.close}.average() else null
            var reason="未觸發暴跌保護"
            if(bar.change < -1000.0){state="PAUSED";trigger=bar.date;rising=0}
            else if(rising>=2&&ma!=null&&bar.close>ma){
                reason=if(state!="NORMAL")"已確認連漲 2 日且站回 5 日線，次一交易日起恢復" else reason
                state="NORMAL"
            }
            if(state=="PAUSED")reason="${trigger?:"先前交易日"} 跌逾 1,000 點；尚未滿足恢復條件"
            if(state=="UNKNOWN")reason="歷史暖機或資料連續性不足，等待可確認的回升訊號"
            result[bar.date]=MarketGuardDecision(state,bar.date,bar.close,bar.change,ma,rising,trigger,reason)
        }
        return result
    }
    fun forDays(bars:List<MarketIndexBar>,days:List<LocalDate>):Map<LocalDate,MarketGuardDecision>{
        val states=timeline(bars);val ordered=states.keys.sorted();val tradingDays=ordered.toSet()
        return days.associateWith{day->
            if(day !in tradingDays)unknown("缺少 $day 大盤交易日資料，無法確認前一交易日")
            else ordered.lastOrNull{it<day}?.let{states.getValue(it)}?:unknown("缺少前一交易日大盤資料")
        }
    }
    fun before(bars:List<MarketIndexBar>,day:LocalDate,expected:LocalDate):MarketGuardDecision{
        val known=bars.filter{it.date<day}
        if(known.maxOfOrNull{it.date}!=expected)return unknown("前一交易日 $expected 指數未更新，暫停買入")
        return timeline(known).values.lastOrNull()?:unknown("尚無大盤資料")
    }
}
