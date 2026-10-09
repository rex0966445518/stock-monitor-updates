package com.rex.twboardingscanner.backtest

import org.json.JSONArray
import org.json.JSONObject

/** Display the archived cutoff valuation, never a newly fetched quote or a rewritten result. */
object BacktestHoldingLedger {
    val csvFields=listOf("lotId","code","name","shares","entryDate","entryTime","entry","cost","target","markDate","mark","holdingChange","holdingChangePct","unrealized","unrealizedPct")
    const val csvHeader="批次,股號,股名,股數,買入日期,買入時間,買入成交價,含費總成本,初始獲利目標價,估值日期,截止估值價,持有價差每股,持有漲跌百分比,未實現損益扣預估賣費稅,未實現報酬率百分比"
    private fun number(o:JSONObject,k:String)=o.optDouble(k,Double.NaN).takeIf{!o.isNull(k)&&it.isFinite()}
    fun rows(raw:JSONArray)=JSONArray((0 until raw.length()).map{i->
        val h=JSONObject(raw.getJSONObject(i).toString())
        val entry=number(h,"entry")?.takeIf{it>0};val mark=number(h,"mark")?.takeIf{it>0}
        val cost=number(h,"cost")?.takeIf{it>0};val pnl=number(h,"unrealized")
        val change=if(entry!=null&&mark!=null)mark-entry else null
        h.put("holdingChange",change?:JSONObject.NULL)
            .put("holdingChangePct",if(change!=null&&entry!=null)change/entry*100 else JSONObject.NULL)
            .put("unrealizedPct",if(pnl!=null&&cost!=null)pnl/cost*100 else JSONObject.NULL)
    })
}
