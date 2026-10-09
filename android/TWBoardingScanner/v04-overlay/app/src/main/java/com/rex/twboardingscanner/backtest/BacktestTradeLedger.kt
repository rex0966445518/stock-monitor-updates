package com.rex.twboardingscanner.backtest

import org.json.JSONArray
import org.json.JSONObject

/** Enrich saved fills by lot, without fetching prices or changing historical performance. */
object BacktestTradeLedger {
    val csvFields=listOf("date","time","timeKind","lotId","signalDate","dataDate","code","name","radar","side","shares","price","buyDate","buyTime","buyTimeKind","buyPrice","buyFee","buyCost","costPerShare","sellPrice","netProfit","netProfitPct","profitState","costSource","dayClose","previousClose","dayChange","dayChangePct","tradePriceChange","tradePriceChangePct","target","turnover","rebateMonth","fee","tax","pnl","reason")
    const val csvHeader="交易日期,時間或時段,時間性質,批次,篩選日期,日線截止日,股號,股名,區域,方向,股數,本筆成交價,原買入日期,原買入時間,原買入時間性質,買入成交價每股,買入手續費,含費總成本,含費成本每股,賣出成交價每股,已實現淨利扣費稅不含折讓,淨利率百分比,利潤狀態,成本來源,當日收盤價,前交易日收盤價,當日漲跌金額,當日漲跌幅百分比,買賣價差每股未扣費稅,買賣價差百分比未扣費稅,出場目標,成交金額,折讓歸屬月份,本筆手續費,本筆交易稅,原始損益欄位,理由"
    private fun n(o:JSONObject,k:String):Double?=if(!o.has(k)||o.isNull(k))null else o.optDouble(k,Double.NaN).takeIf{it.isFinite()}
    private fun s(o:JSONObject,k:String)=if(o.isNull(k))"" else o.optString(k)
    fun rows(raw:JSONArray):JSONArray {
        val copies=(0 until raw.length()).map{JSONObject(raw.getJSONObject(it).toString())}
        val openByCode=mutableMapOf<String,MutableList<JSONObject>>()
        copies.sortedBy{s(it,"date")}.forEach{t->
            val code=s(t,"code");val side=s(t,"side");val lot=s(t,"lotId");val date=s(t,"date")
            val qty=n(t,"shares")?.takeIf{it>0}?:if(!t.has("shares"))1000.0 else null
            val price=n(t,"price")?.takeIf{it>0};val fee=n(t,"fee")?.takeIf{it>=0};val tax=n(t,"tax")?.takeIf{it>=0}
            val candidates=openByCode.getOrPut(code){mutableListOf()}
            // Never merge same-stock lots or silently assume FIFO for an ambiguous legacy sale.
            val match=if(side=="SELL")candidates.filter{
                (if(lot.isNotBlank())s(it,"lotId")==lot else s(it,"lotId").isBlank())&&
                    s(it,"date")<=date&&n(it,"shares")==qty
            }.singleOrNull() else null
            val buy=if(side=="BUY")t else match
            val buyPrice=buy?.let{n(it,"price")?.takeIf{p->p>0}}
            val buyFee=buy?.let{n(it,"fee")?.takeIf{f->f>=0}}
            val matchedCost=if(buyPrice!=null&&qty!=null&&buyFee!=null)buyPrice*qty+buyFee else null
            val proceeds=if(side=="SELL"&&price!=null&&qty!=null&&fee!=null&&tax!=null)price*qty-fee-tax else null
            val recordedPnl=if(side=="SELL")n(t,"pnl")else null
            val inferredCost=if(proceeds!=null&&recordedPnl!=null)(proceeds-recordedPnl).takeIf{it>0}else null
            val conflict=matchedCost!=null&&inferredCost!=null&&kotlin.math.abs(matchedCost-inferredCost)>.02
            val cost=if(conflict)null else matchedCost?:inferredCost
            val pnl=recordedPnl?:if(proceeds!=null&&cost!=null)proceeds-cost else null
            fun put(key:String,v:Any?){t.put(key,v?:JSONObject.NULL)}
            put("shares",qty);put("buyPrice",buyPrice);put("buyFee",buyFee);put("buyCost",cost)
            put("costPerShare",if(cost!=null&&qty!=null)cost/qty else null)
            put("buyDate",buy?.let{s(it,"date").ifBlank{null}});put("buyTime",buy?.let{s(it,"time").ifBlank{null}})
            put("buyTimeKind",buy?.let{s(it,"timeKind").ifBlank{null}})
            put("sellPrice",if(side=="SELL")price else null);put("netProfit",if(side=="SELL")pnl else null)
            put("netProfitPct",if(side=="SELL"&&pnl!=null&&cost!=null&&cost>0)pnl/cost*100 else null)
            put("profitState",if(side=="SELL")"已實現" else "當日新倉，尚未實現")
            put("costSource",when{conflict->"成本紀錄不一致";matchedCost!=null->"原買入紀錄";inferredCost!=null->"依賣出淨收與原淨利反推";else->"缺原始成本資料"})
            put("turnover",if(price!=null&&qty!=null)price*qty else null)
            val close=n(t,"dayClose")?.takeIf{it>0};val previous=n(t,"previousClose")?.takeIf{it>0}
            val change=if(close!=null&&previous!=null)close-previous else null
            val spread=if(side=="SELL"&&price!=null&&buyPrice!=null)price-buyPrice else null
            put("dayChange",change);put("dayChangePct",if(change!=null&&previous!=null)change/previous*100 else null)
            put("tradePriceChange",spread);put("tradePriceChangePct",if(spread!=null&&buyPrice!=null)spread/buyPrice*100 else null)
            put("rebateMonth",date.take(7));put("tradeDetailsVersion",1)
            if(side=="BUY")candidates.add(t) else if(match!=null)candidates.remove(match)
        }
        return JSONArray(copies)
    }
    fun byDate(rows:JSONArray):Map<String,List<JSONObject>> = (0 until rows.length()).map{rows.getJSONObject(it)}.groupBy{s(it,"date")}
}
