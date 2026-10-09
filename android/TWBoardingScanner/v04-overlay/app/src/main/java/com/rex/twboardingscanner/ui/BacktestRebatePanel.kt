package com.rex.twboardingscanner.ui

import android.content.Context
import android.widget.LinearLayout
import org.json.JSONObject
import java.util.Locale

internal class BacktestRebatePanel(c:Context,r:JSONObject):LinearLayout(c){
    private fun money(v:Double)=String.format(Locale.TAIWAN,"%,.2f",v)
    init{
        orientation=VERTICAL;setPadding(NeonUi.dp(c,12),NeonUi.dp(c,12),NeonUi.dp(c,12),NeonUi.dp(c,12));background=NeonUi.panel(c,NeonUi.amber)
        addView(NeonUi.label(c,"折讓金 · 每月明細",17f,NeonUi.ink,true))
        addView(NeonUi.label(c,"+${money(r.optDouble("rebateAccrued",0.0))} 元",25f,NeonUi.amber,true))
        addView(NeonUi.label(c,"估計應收 · 已計總利潤，不加入可用資金",11f))
        val months=r.optJSONArray("rebateMonths")
        if(months==null||months.length()==0)addView(NeonUi.label(c,"目前無成交",12f))
        else for(i in 0 until months.length()){
            val m=months.getJSONObject(i)
            addView(NeonUi.gap(c,10))
            addView(NeonUi.label(c,"${m.getString("month")} · ${if(m.getDouble("rate")>=0.001)"0.1%" else "0.05%"} · +${money(m.getDouble("amount"))} 元",14f,NeonUi.amber,true))
            addView(NeonUi.label(c,"買 ${m.getInt("buyTrades")} 筆 ${money(m.getDouble("buyAmount"))} 元\n賣 ${m.getInt("sellTrades")} 筆 ${money(m.getDouble("sellAmount"))} 元",11f))
            addView(NeonUi.label(c,"合計 ${money(m.getDouble("turnover"))} 元",12f,NeonUi.ink))
        }
        addView(NeonUi.gap(c,8))
        addView(NeonUi.label(c,"超過 5,000 萬適用整月 0.1%，否則 0.05%。僅計本次回測成交，未滿月按截止日累計估算。",10f))
    }
}
