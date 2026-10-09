package com.rex.twboardingscanner.ui

import android.content.Context
import android.widget.LinearLayout
import org.json.JSONObject
import java.util.Locale

internal class BacktestDailyCard(c:Context,d:JSONObject):LinearLayout(c){
    private fun number(d:JSONObject,key:String)=d.optDouble(key,Double.NaN).takeIf{d.has(key)&&!d.isNull(key)&&it.isFinite()}
    private fun amount(v:Double?)=v?.let{String.format(Locale.TAIWAN,"%,.0f",it)}?:"—"
    private fun signed(v:Double?)=v?.let{(if(it>=0)"+" else "−")+amount(kotlin.math.abs(it))}?:"—"
    private fun count(d:JSONObject,key:String)=number(d,key)?.toInt()?.toString()?:"—"
    init {
        orientation=VERTICAL;setPadding(NeonUi.dp(c,12),NeonUi.dp(c,12),NeonUi.dp(c,12),NeonUi.dp(c,12));background=NeonUi.panel(c,NeonUi.blue)
        val realized=number(d,"realized");val change=number(d,"dayProfit");val value=number(d,"holdingValue")
        fun tint(v:Double?)=if(v==null||v==0.0)NeonUi.ink else if(v>0)NeonUi.pink else NeonUi.mint
        addView(NeonUi.label(c,d.getString("date"),16f,NeonUi.ink,true))
        addView(NeonUi.label(c,"入選 ${count(d,"selected")} 檔 · 買 ${count(d,"buys")} / 賣 ${count(d,"sells")} 張 · 未買 ${count(d,"skipped")}",11f))
        addView(NeonUi.gap(c,8))
        addView(NeonUi.row(c,listOf(
            NeonUi.tile(c,"當日淨利",signed(realized),"已實現 · 扣買賣費稅",tint(realized)),
            NeonUi.tile(c,"留倉金額",amount(number(d,"holdingCost")),"含費成本 · ${count(d,"holdingLots")} 張",NeonUi.cyan)
        )))
        addView(NeonUi.gap(c,8))
        addView(NeonUi.label(c,"留倉估值 ${amount(value)} 元 · 扣估計賣出費稅",11f,NeonUi.cyan))
        addView(NeonUi.label(c,"當日總損益 ${signed(change)} 元 · 含留倉與應收款",12f,tint(change),true))
        number(d,"rebateChange")?.let{if(it!=0.0)addView(NeonUi.label(c,"當日折讓金 ${signed(it)} 元 · 已含在總損益",11f,NeonUi.amber))}
        val stale=number(d,"staleLots")?.toInt()?:0
        if(stale>0)addView(NeonUi.label(c,"${stale} 張缺當日報價，沿用最近估值",10f,NeonUi.amber))
        val note=d.optString("accountingNote")
        if(note.isNotBlank())addView(NeonUi.label(c,note,10f,if(value==null)NeonUi.amber else NeonUi.muted))
        contentDescription="${d.getString("date")} 當日已實現淨利 ${signed(realized)} 元 留倉含費成本 ${amount(number(d,"holdingCost"))} 元"
    }
}
