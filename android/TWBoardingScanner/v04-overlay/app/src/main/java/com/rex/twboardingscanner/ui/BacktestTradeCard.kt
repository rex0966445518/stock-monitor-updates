package com.rex.twboardingscanner.ui

import android.content.Context
import android.widget.LinearLayout
import org.json.JSONObject
import java.util.Locale

internal class BacktestTradeCard(c:Context,t:JSONObject):LinearLayout(c){
    private fun n(t:JSONObject,k:String)=t.optDouble(k,Double.NaN).takeIf{!t.isNull(k)&&it.isFinite()}
    private fun amount(v:Double?,places:Int=0)=v?.let{String.format(Locale.TAIWAN,"%,.${places}f",it)}?:"—"
    private fun signed(v:Double?,places:Int=0)=v?.let{(if(it>=0)"+" else "−")+amount(kotlin.math.abs(it),places)}?:"—"
    private fun text(t:JSONObject,k:String,fallback:String="未記錄")=if(t.isNull(k))fallback else t.optString(k).ifBlank{fallback}
    init{
        val sell=t.optString("side")=="SELL";val accent=if(sell)NeonUi.cyan else NeonUi.pink
        val profit=n(t,"netProfit");val tint=if(profit==null||profit==0.0)NeonUi.ink else if(profit>0)NeonUi.pink else NeonUi.mint
        orientation=VERTICAL;background=NeonUi.panel(c,accent);setPadding(NeonUi.dp(c,10),NeonUi.dp(c,12),NeonUi.dp(c,10),NeonUi.dp(c,12))
        addView(NeonUi.label(c,"${if(sell)"賣出" else "買入"}  ${text(t,"code")}  ${text(t,"name")}",17f,accent,true))
        val shares=n(t,"shares")
        addView(NeonUi.label(c,"${text(t,"time","時間未記錄")} · ${amount(shares)} 股${if(shares!=null&&shares%1000==0.0)"（${(shares/1000).toInt()} 張）" else ""} · ${text(t,"radar","—")}",11f))
        addView(NeonUi.label(c,text(t,"timeKind","舊版未記錄時間性質"),10f))
        val change=n(t,"dayChange");val changePct=n(t,"dayChangePct")
        val dayTint=if(change==null||change==0.0)NeonUi.ink else if(change>0)NeonUi.pink else NeonUi.mint
        addView(NeonUi.gap(c,6))
        addView(NeonUi.label(c,"日漲跌 ${signed(change,2)} 元（${signed(changePct,2)}%）",16f,dayTint,true))
        addView(NeonUi.label(c,if(change==null)"缺當日或前交易日收盤資料" else "收盤 ${amount(n(t,"dayClose"),2)} · 前收 ${amount(n(t,"previousClose"),2)} 元",10f))
        addView(NeonUi.gap(c,8))
        addView(NeonUi.row(c,listOf(
            NeonUi.tile(c,"成本價／股",amount(n(t,"costPerShare"),3),"含買入手續費",NeonUi.ink),
            NeonUi.tile(c,"賣出價／股",amount(n(t,"sellPrice"),2),if(sell)"模擬成交價" else "當日尚未賣出",NeonUi.cyan)
        )))
        addView(NeonUi.gap(c,10))
        val pct=n(t,"netProfitPct")?.let{"（${String.format(Locale.TAIWAN,"%+.2f",it)}%）"}.orEmpty()
        addView(NeonUi.label(c,if(sell)"本筆淨利 ${signed(profit)} 元 $pct" else "本筆淨利  尚未實現",18f,if(sell)tint else NeonUi.muted,true))
        addView(NeonUi.label(c,"買入成交 ${amount(n(t,"buyPrice"),2)} 元／股 · 含費成本 ${amount(n(t,"buyCost"))} 元",11f))
        if(sell){
            addView(NeonUi.label(c,"買賣價差 ${signed(n(t,"tradePriceChange"),2)} 元（${signed(n(t,"tradePriceChangePct"),2)}%）· 未扣費稅",11f))
            addView(NeonUi.label(c,"原買入 ${text(t,"buyDate")} ${text(t,"buyTime","時間未記錄")}",11f))
            addView(NeonUi.label(c,"買費 ${amount(n(t,"buyFee"))} · 賣費 ${amount(n(t,"fee"))} · 交易稅 ${amount(n(t,"tax"))} 元",10f))
        }else addView(NeonUi.label(c,"買入手續費 ${amount(n(t,"buyFee"))} 元",10f))
        val source=text(t,"costSource","")
        if(source!="原買入紀錄"&&source.isNotBlank())addView(NeonUi.label(c,source,10f,NeonUi.amber))
        if(t.optString("reason").isNotBlank())addView(NeonUi.label(c,t.optString("reason"),10f))
        contentDescription="${if(sell)"賣出" else "買入"} ${text(t,"code")} ${text(t,"name")} 含費成本價 ${amount(n(t,"costPerShare"),3)} 賣出價 ${amount(n(t,"sellPrice"),2)} 淨利 ${if(sell)signed(profit)else "尚未實現"}"
    }
}
