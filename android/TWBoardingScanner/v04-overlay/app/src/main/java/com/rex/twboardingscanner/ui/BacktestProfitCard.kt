package com.rex.twboardingscanner.ui

import android.content.Context
import android.widget.LinearLayout
import org.json.JSONObject
import java.util.Locale

internal class BacktestProfitCard(c:Context,t:JSONObject,realized:Boolean,cutoff:String):LinearLayout(c){
    private fun n(t:JSONObject,k:String)=t.optDouble(k,Double.NaN).takeIf{!t.isNull(k)&&it.isFinite()}
    private fun value(t:JSONObject,k:String,fallback:String="未記錄")=if(t.isNull(k))fallback else t.optString(k).ifBlank{fallback}
    private fun amount(v:Double?,digits:Int=0)=v?.let{String.format(Locale.TAIWAN,"%,.${digits}f",it)}?:"—"
    private fun signed(v:Double?,digits:Int=0)=v?.let{(if(it<0)"−" else "+")+amount(kotlin.math.abs(it),digits)}?:"—"
    private fun tint(v:Double?)=if(v==null||v==0.0)NeonUi.ink else if(v>0)NeonUi.pink else NeonUi.mint
    init {
        val pnl=n(t,if(realized)"netProfit" else "unrealized")
        val pct=n(t,if(realized)"netProfitPct" else "unrealizedPct")
        val accent=tint(pnl)
        orientation=VERTICAL;background=NeonUi.panel(c,accent);setPadding(NeonUi.dp(c,12),NeonUi.dp(c,12),NeonUi.dp(c,12),NeonUi.dp(c,12))
        addView(NeonUi.label(c,"${value(t,"code")}  ${value(t,"name")}",19f,NeonUi.ink,true))
        val qty=n(t,"shares")
        addView(NeonUi.label(c,"${if(realized)"已賣出" else "留倉"} · ${amount(qty)} 股 · ${value(t,"radar","—")}",11f,NeonUi.cyan))
        addView(NeonUi.label(c,"買入 ${value(t,if(realized)"buyDate" else "entryDate")} ${value(t,if(realized)"buyTime" else "entryTime","")}",11f))
        if(realized){
            addView(NeonUi.label(c,"賣出 ${value(t,"date")} ${value(t,"time","")}",11f))
            addView(NeonUi.label(c,value(t,"timeKind","舊版未記錄時間性質"),10f))
        }else{
            val marked=value(t,"markDate")
            addView(NeonUi.label(c,"回測截止 $cutoff · 價格日期 $marked",11f))
            if(marked!="未記錄"&&marked<cutoff)addView(NeonUi.label(c,"截止日無新報價，沿用上列日期價格",11f,NeonUi.amber))
        }
        addView(NeonUi.gap(c,10))
        addView(NeonUi.row(c,listOf(
            NeonUi.tile(c,"買入成交價",amount(n(t,if(realized)"buyPrice" else "entry"),2),"元／股 · 未含手續費",NeonUi.ink),
            NeonUi.tile(c,if(realized)"賣出成交價" else "截止日現價",amount(n(t,if(realized)"sellPrice" else "mark"),2),if(realized)"元／股 · 模擬成交" else "元／股 · 歷史估值",NeonUi.cyan)
        )))
        val change=n(t,if(realized)"sellChange" else "holdingChange")
        val changePct=n(t,if(realized)"sellChangePct" else "holdingChangePct")
        addView(NeonUi.gap(c,10))
        addView(NeonUi.label(c,"${if(realized)"賣出時漲跌" else "持有漲跌"} ${signed(change,2)} 元",16f,tint(change),true))
        addView(NeonUi.label(c,"${signed(changePct,2)}%",23f,tint(change),true))
        addView(NeonUi.label(c,if(change==null){if(realized)"缺賣出價或前收資料，無法計算" else "缺買入價或估值資料，無法計算"}else if(realized)"賣出模擬成交價相對前收 ${amount(n(t,"previousClose"),2)} 元" else "截止估值價相對買入成交價 · 未扣費稅",10f))
        if(realized)addView(NeonUi.label(c,"買賣價差 ${signed(n(t,"tradePriceChange"),2)} 元（${signed(n(t,"tradePriceChangePct"),2)}%）",11f))
        addView(NeonUi.gap(c,10))
        addView(NeonUi.label(c,if(realized)"已實現損益" else "未實現損益",12f,NeonUi.ink,true))
        addView(NeonUi.label(c,"${signed(pnl)} 元",27f,accent,true))
        addView(NeonUi.label(c,"淨報酬率 ${signed(pct,2)}%",13f,accent))
        addView(NeonUi.label(c,if(realized)"已扣買賣費稅 · 不含股息與折讓金" else "已計買入費與預估賣出費稅 · 尚未賣出",10f))
        addView(NeonUi.label(c,"含買入費總成本 ${amount(n(t,if(realized)"buyCost" else "cost"))} 元",11f))
        if(realized){
            val source=value(t,"costSource","")
            if(source.isNotBlank()&&source!="原買入紀錄")addView(NeonUi.label(c,source,10f,NeonUi.amber))
        }
        if(pnl==null)addView(NeonUi.label(c,"此筆舊紀錄缺少損益資料",11f,NeonUi.amber))
        contentDescription="${value(t,"code")} ${value(t,"name")} ${if(realized)"已實現" else "未實現"}損益 ${signed(pnl)} 元"
    }
}
