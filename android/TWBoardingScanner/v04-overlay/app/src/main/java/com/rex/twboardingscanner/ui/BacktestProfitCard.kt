package com.rex.twboardingscanner.ui

import android.content.Context
import android.content.SharedPreferences
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.rex.twboardingscanner.data.StockPolicyStore
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

internal class BacktestProfitCard(c:Context,t:JSONObject,realized:Boolean,cutoff:String):LinearLayout(c){
    private val code=t.optString("code").trim()
    private val stockName=t.optString("name").trim()
    private val policyStore=StockPolicyStore(c)
    private val policyPrefs=c.applicationContext.getSharedPreferences("stock-entry-policy",0)
    private var banButton:MaterialButton?=null
    private val policyListener=SharedPreferences.OnSharedPreferenceChangeListener{_,key->
        if(key=="policy"||key==null)refreshBan()
    }
    private fun refreshBan(){
        banButton?.let{button->
            val valid=code.matches(Regex("[1-9][0-9]{3}"))
            val banned=code in policyStore.read().banned
            button.text=if(banned)"已禁買" else if(valid)"一鍵禁買" else "股號未記錄"
            button.isEnabled=valid&&!banned
            button.contentDescription=if(banned)"$code $stockName 已加入所有禁買名單" else "$code $stockName 一鍵加入主篩查器、歷史回測、自動測試機器人禁買名單"
        }
    }
    override fun onAttachedToWindow(){
        super.onAttachedToWindow()
        if(banButton!=null){policyPrefs.registerOnSharedPreferenceChangeListener(policyListener);refreshBan()}
    }
    override fun onDetachedFromWindow(){
        policyPrefs.unregisterOnSharedPreferenceChangeListener(policyListener)
        super.onDetachedFromWindow()
    }
    /** Format only recorded dates/times. A missing or ranged time is never an exact fill. */
    private fun purchaseTime(t:JSONObject):String{
        val date=runCatching{LocalDate.parse(t.optString("entryDate"))}.getOrNull()
        val day=date?.let{"${it.monthValue}月${it.dayOfMonth}日"}?:"日期未記錄"
        val time=Regex("^([01][0-9]|2[0-3]):([0-5][0-9])(?:\\s*[（(].*[）)])?$").matchEntire(t.optString("entryTime").trim())
        return "買入 $day "+(time?.let{"${it.groupValues[1]}時${it.groupValues[2]}分"}?:"時間未記錄")
    }
    private fun priceTile(c:Context,title:String,price:String,subtitle:String,accent:Int,time:String?)=
        NeonUi.tile(c,title,price,subtitle,accent).apply{
            if(time!=null){
                addView(NeonUi.label(c,time,10f).apply{tag="holding-purchase-time";setPadding(0,NeonUi.dp(c,5),0,0)},1)
                contentDescription="$title $time $price $subtitle"
            }
        }
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
        val title=NeonUi.label(c,"${value(t,"code")}  ${value(t,"name")}",19f,NeonUi.ink,true)
        if(realized)addView(title) else{
            banButton=NeonUi.button(c,"一鍵禁買",NeonUi.amber){
                try{
                    policyStore.ban(code,stockName)
                    refreshBan()
                    Toast.makeText(c,"$code $stockName 已同步至三區禁買名單",Toast.LENGTH_LONG).show()
                }catch(e:Exception){
                    refreshBan()
                    Toast.makeText(c,"禁買儲存失敗，請重試",Toast.LENGTH_LONG).show()
                }
            }.apply{tag="holding-ban";textSize=12f}
            addView(LinearLayout(c).apply{
                gravity=Gravity.CENTER_VERTICAL
                addView(title,LayoutParams(0,-2,1f))
                addView(banButton,LayoutParams(NeonUi.dp(c,108),-2).apply{marginStart=NeonUi.dp(c,8)})
            })
            refreshBan()
        }
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
            priceTile(c,"買入成交價",amount(n(t,if(realized)"buyPrice" else "entry"),2),"元／股 · 未含手續費",NeonUi.ink,if(realized)null else purchaseTime(t)),
            priceTile(c,if(realized)"賣出成交價" else "截止日現價",amount(n(t,if(realized)"sellPrice" else "mark"),2),if(realized)"元／股 · 模擬成交" else "元／股 · 歷史估值",NeonUi.cyan,if(realized)null else purchaseTime(t))
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
