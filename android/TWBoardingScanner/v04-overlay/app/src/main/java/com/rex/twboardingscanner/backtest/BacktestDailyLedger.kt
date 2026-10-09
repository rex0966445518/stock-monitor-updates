package com.rex.twboardingscanner.backtest

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Read-time enrichment only. Never rewrite archived reports or fetch today's prices. */
object BacktestDailyLedger {
    val csvFields=listOf("date","selected","buys","sells","skipped","realized","holdingCost","holdingLots","holdingValue","dayProfit","cash","dividendAccrued","rebateChange","rebateAccrued","equity","staleLots","accountingSource","accountingNote")
    const val csvHeader="日期,入選檔數,買入張數,賣出張數,未買次數,當日已實現淨利,收盤留倉成本含買入費,收盤留倉張數,留倉估值扣估計賣費稅,當日總損益含留倉及股息,可用資金,累計應收股息,當日折讓金變動,累計應收折讓金,淨資產,沿用舊價張數,數值來源,補算說明"
    private fun number(o:JSONObject,key:String):Double?=if(!o.has(key)||o.isNull(key))null else o.optDouble(key,Double.NaN).takeIf{it.isFinite()}
    fun rows(report:JSONObject):JSONArray {
        val run=report.optJSONObject("run")?:return JSONArray()
        val curve=run.optJSONArray("curve")?:return JSONArray()
        val rawTrades=run.optJSONArray("trades")
        val trades=if(rawTrades==null)emptyList() else (0 until rawTrades.length()).map{rawTrades.getJSONObject(it)}.sortedBy{it.optString("date")}
        val dated=trades.all{it.optString("date").matches(Regex("\\d{4}-\\d{2}-\\d{2}"))}
        var cash=number(report.optJSONObject("settings")?:JSONObject(),"capital")?:Double.NaN
        var cost=0.0;var shares=0;var nextTrade=0
        var ledgerKnown=rawTrades!=null&&dated&&cash.isFinite()
        var previousEquity=cash
        val finalDividend=number(run,"dividend")
        val legacyRebate=report.optInt("strategyVersion",1)<3
        val out=JSONArray()
        for(i in 0 until curve.length()){
            val row=JSONObject(curve.getJSONObject(i).toString());val day=row.getString("date")
            var dailyRealized=0.0;var realizedKnown=rawTrades!=null&&dated;var buys=0;var sells=0
            while(nextTrade<trades.size&&trades[nextTrade].optString("date")<=day){
                val t=trades[nextTrade++];val today=t.optString("date")==day;val side=t.optString("side")
                val quantity=number(t,"shares")?:1000.0
                val price=number(t,"price");val fee=number(t,"fee");val tax=number(t,"tax");val pnl=number(t,"pnl")
                if(today&&side=="BUY")buys++
                if(today&&side=="SELL"){sells++;if(pnl==null)realizedKnown=false else dailyRealized+=pnl}
                if(side !in listOf("BUY","SELL")||price==null||price<=0||fee==null||fee<0||quantity<=0||quantity%1000!=0.0){ledgerKnown=false;continue}
                val amount=price*quantity
                if(side=="BUY"){cash-=amount+fee;cost+=amount+fee;shares+=quantity.toInt()}
                else if(tax==null||tax<0||pnl==null){ledgerKnown=false}
                else{val proceeds=amount-fee-tax;cash+=proceeds;cost-=proceeds-pnl;shares-=quantity.toInt()}
                if(cost< -0.01||shares<0||cash< -0.01)ledgerKnown=false
            }
            fun missing(key:String)=number(row,key)==null
            val snapshot=!missing("realized")&&!missing("holdingCost")&&!missing("holdingValue")
            if(missing("realized")&&realizedKnown)row.put("realized",dailyRealized)
            if(!row.has("buys")&&rawTrades!=null&&dated)row.put("buys",buys)
            if(!row.has("sells")&&rawTrades!=null&&dated)row.put("sells",sells)
            if(ledgerKnown){
                if(missing("cash"))row.put("cash",cash)
                if(missing("holdingCost"))row.put("holdingCost",cost.coerceAtLeast(0.0))
                if(missing("holdingLots"))row.put("holdingLots",shares/1000)
            }
            if(missing("dividendAccrued")){
                if(finalDividend!=null&&abs(finalDividend)<1e-8)row.put("dividendAccrued",0.0)
                else if(i==curve.length()-1&&finalDividend!=null)row.put("dividendAccrued",finalDividend)
            }
            if(legacyRebate){if(missing("rebateAccrued"))row.put("rebateAccrued",0.0);if(missing("rebateChange"))row.put("rebateChange",0.0)}
            val rebate=number(row,"rebateAccrued")
            val equity=number(row,"equity");val dayCash=number(row,"cash");val dividend=number(row,"dividendAccrued")
            if(missing("holdingValue")){
                if(ledgerKnown&&shares==0)row.put("holdingValue",0.0)
                else if(equity!=null&&dayCash!=null&&dividend!=null&&rebate!=null){
                    val value=equity-dayCash-dividend-rebate
                    if(value>= -0.01)row.put("holdingValue",value.coerceAtLeast(0.0))
                }
            }
            if(missing("dayProfit")&&equity!=null&&previousEquity.isFinite())row.put("dayProfit",equity-previousEquity)
            previousEquity=equity?:Double.NaN
            row.put("accountingSource",if(snapshot)"當日帳本" else "舊日誌交易補算")
            row.put("accountingNote",when{
                snapshot->""
                missing("holdingCost")||missing("realized")->"舊紀錄不足，部分金額無法補算"
                missing("holdingValue")->"缺逐日應收款資料，無法還原留倉估值"
                else->"依當次保存的交易與淨資產補算"
            })
            out.put(row)
        }
        return out
    }
    fun enrich(report:JSONObject)=JSONObject(report.toString()).apply{optJSONObject("run")?.put("curve",rows(report))}
}
