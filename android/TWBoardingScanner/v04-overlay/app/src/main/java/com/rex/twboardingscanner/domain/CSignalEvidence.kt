package com.rex.twboardingscanner.domain

import java.util.Locale

/** Explain the exact candle and user-selected gates, rather than the category name alone. */
object CSignalEvidence {
    fun format(m:RuleMetrics,selected:Set<String>):String {
        fun number(v:Double?,suffix:String="")=v?.takeIf{it.isFinite()}?.let{String.format(Locale.TAIWAN,"%.2f%s",it,suffix)}?:"待查核"
        val last=m.last
        val prior=m.bars.dropLast(1).takeLast(20)
        val avg=prior.takeIf{it.size==20&&it.all{b->b.volumeShares>0}}?.map{it.volumeShares/1000.0}?.average()
        val ratio=avg?.takeIf{it>0}?.let{last?.volumeShares?.takeIf{v->v>0}?.div(1000.0)?.div(it)}
        val position=last?.takeIf{it.high>it.low}?.let{(it.close-it.low)/(it.high-it.low)*100}
        val core=ScanConditions.forRadar(RadarType.C_LONG_RED_VOLUME).filter{it.id in setOf("red","gain","body","burst","closeHigh")}
        val disabled=core.filter{it.id !in selected}.joinToString("、"){it.label}
        val states=core.joinToString("\n"){rule->
            val state=if(rule.id !in selected)"未啟用，不作篩選" else when(rule.test(m)){true->"通過";false->"未通過";null->"待查核"}
            "${rule.label}：$state"
        }
        return "C 判定日：${last?.let{RuleMetrics.tradingDate(it.time)}?:"無資料"}（日K來源：Yahoo）\n"+
            "開 ${number(last?.open)}／高 ${number(last?.high)}／低 ${number(last?.low)}／收 ${number(last?.close)}\n"+
            "日漲幅 ${number(m.dayGain,"%")}；紅K實體 ${number(m.bodyGain,"%")}；收盤位置 ${number(position,"%")}\n"+
            "觸發日量 ${number(last?.volumeShares?.div(1000.0))} 張／前20日均量 ${number(avg)} 張＝${number(ratio)} 倍（不含觸發日）\n"+
            "量能倍數比較前20日均量，不是單純比較較前一日的量增百分比。\n"+
            (if(disabled.isNotEmpty())"自訂C條件：以下核心條件未啟用，入選不代表完整長紅爆量。\n" else "預設C核心條件全部啟用。\n")+states
    }
}
