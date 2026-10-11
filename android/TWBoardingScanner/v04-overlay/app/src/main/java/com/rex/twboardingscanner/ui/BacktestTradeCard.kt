package com.rex.twboardingscanner.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.rex.twboardingscanner.data.StockDirectory
import com.rex.twboardingscanner.data.StockPolicyStore
import org.json.JSONObject
import java.util.Locale

internal class BacktestTradeCard(c:Context,t:JSONObject,industry:String?=null):LinearLayout(c){
    private val code=t.optString("code").trim()
    private val name=t.optString("name").trim()
    private val store=StockPolicyStore(c)
    private val prefs=c.applicationContext.getSharedPreferences("stock-entry-policy",0)
    private var banButton:MaterialButton?=null
    private val policyListener=SharedPreferences.OnSharedPreferenceChangeListener{_,key->
        if(key==null||key=="policy")refreshBan()
    }
    private fun refreshBan(){
        banButton?.let{b->
            val valid=code.matches(Regex("[1-9][0-9]{3}"))
            val banned=code in store.read().banned
            b.text=if(banned)"已禁買" else if(valid)"一鍵禁買" else "股號缺漏"
            b.isEnabled=valid&&!banned
            b.contentDescription=if(banned)"$code $name 已在所有禁買名單" else "$code $name 加入主頁、歷史回測及自動機器人共用禁買名單"
        }
    }
    override fun onAttachedToWindow(){
        super.onAttachedToWindow()
        prefs.registerOnSharedPreferenceChangeListener(policyListener)
        refreshBan()
    }
    override fun onDetachedFromWindow(){
        prefs.unregisterOnSharedPreferenceChangeListener(policyListener)
        super.onDetachedFromWindow()
    }
    private fun n(t:JSONObject,k:String)=t.optDouble(k,Double.NaN).takeIf{!t.isNull(k)&&it.isFinite()}
    private fun amount(v:Double?,places:Int=0)=v?.let{String.format(Locale.TAIWAN,"%,.${places}f",it)}?:"—"
    private fun signed(v:Double?,places:Int=0)=v?.let{(if(it>=0)"+" else "−")+amount(kotlin.math.abs(it),places)}?:"—"
    private fun text(t:JSONObject,k:String,fallback:String="未記錄")=if(t.isNull(k))fallback else t.optString(k).ifBlank{fallback}
    init{
        val sell=t.optString("side")=="SELL";val accent=if(sell)NeonUi.cyan else NeonUi.pink
        val profit=n(t,"netProfit");val tint=if(profit==null||profit==0.0)NeonUi.ink else if(profit>0)NeonUi.pink else NeonUi.mint
        orientation=VERTICAL;background=NeonUi.panel(c,accent);setPadding(NeonUi.dp(c,10),NeonUi.dp(c,12),NeonUi.dp(c,10),NeonUi.dp(c,12))
        val sector=industry?.takeIf{it.isNotBlank()}?:StockDirectory(c).industryLabels()[code]?:"待分類"
        val title=NeonUi.label(c,"${if(sell)"賣出" else "買入"}  ${text(t,"code")}  ${text(t,"name")}",17f,accent,true).apply{tag="trade-title"}
        val tagView=NeonUi.label(c,sector,10f,NeonUi.cyan,true).apply{
            tag="trade-industry";setPadding(NeonUi.dp(c,8),NeonUi.dp(c,5),NeonUi.dp(c,8),NeonUi.dp(c,5))
            background=GradientDrawable().apply{cornerRadius=NeonUi.dp(c,8).toFloat()
                setColor(android.graphics.Color.rgb(17,46,65));setStroke(NeonUi.dp(c,1),NeonUi.cyan)}
        }
        addView(LinearLayout(c).apply{
            gravity=Gravity.CENTER_VERTICAL
            addView(title,LayoutParams(0,-2,1f))
            addView(tagView,LayoutParams(-2,-2).apply{marginStart=NeonUi.dp(c,6)})
        })
        val shares=n(t,"shares")
        val meta=NeonUi.vertical(c).apply{
            addView(NeonUi.label(c,"${text(t,"time","時間未記錄")} · ${amount(shares)} 股${if(shares!=null&&shares%1000==0.0)"（${(shares/1000).toInt()} 張）" else ""} · ${text(t,"radar","—")}",11f))
            addView(NeonUi.label(c,text(t,"timeKind","舊版未記錄時間性質"),10f))
        }
        banButton=NeonUi.button(c,"一鍵禁買",NeonUi.amber){
            try{
                store.ban(code,name);refreshBan()
                Toast.makeText(c,"$code $name 已同步至主頁、回測與機器人禁買名單",Toast.LENGTH_LONG).show()
            }catch(e:Exception){
                refreshBan();Toast.makeText(c,"禁買儲存失敗，請重試",Toast.LENGTH_LONG).show()
            }
        }.apply{tag="trade-ban";textSize=12f}
        addView(LinearLayout(c).apply{
            gravity=Gravity.CENTER_VERTICAL
            addView(meta,LayoutParams(0,-2,1f))
            addView(banButton,LayoutParams(NeonUi.dp(c,108),-2).apply{marginStart=NeonUi.dp(c,8)})
        })
        refreshBan()
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
        addView(NeonUi.gap(c,10))
        addView(BacktestFiveMinutePanel(c,code,t.optString("date"),if(sell)"SELL" else "BUY",n(t,"price"),t.optString("time"),t.optString("timeKind")))
        contentDescription="${if(sell)"賣出" else "買入"} ${text(t,"code")} ${text(t,"name")} 產業分類 $sector 含費成本價 ${amount(n(t,"costPerShare"),3)} 賣出價 ${amount(n(t,"sellPrice"),2)} 淨利 ${if(sell)signed(profit)else "尚未實現"}"
    }
}
