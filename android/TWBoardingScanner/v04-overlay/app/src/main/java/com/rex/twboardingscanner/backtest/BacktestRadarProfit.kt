package com.rex.twboardingscanner.backtest

import com.rex.twboardingscanner.domain.RadarType
import org.json.JSONObject

/** Attribute each saved lot once; joint entry signals share its profit equally. */
object BacktestRadarProfit {
    const val VERSION=1
    fun summarize(run:JSONObject):JSONObject {
        val sums=RadarType.entries.associate{it.name.take(1) to 0.0}.toMutableMap()
        val missing=mutableSetOf<String>()
        val trades=run.optJSONArray("trades");val holdings=run.optJSONArray("holdings")
        if(trades==null||holdings==null)missing.addAll(sums.keys)
        var unclassified=0.0
        fun add(row:JSONObject,field:String){
            val types=row.optString("radar").split('/').map{it.trim()}.distinct()
            val codes=types.mapNotNull{raw->RadarType.entries.firstOrNull{it.name==raw||it.name.take(1)==raw}?.name?.take(1)}.distinct()
            val value=if(row.isNull(field))Double.NaN else row.optDouble(field,Double.NaN)
            if(!value.isFinite()){if(codes.isEmpty())missing.addAll(sums.keys)else missing.addAll(codes);return}
            if(codes.isEmpty()||codes.size!=types.size){unclassified+=value;return}
            codes.forEach{ sums[it]=sums.getValue(it)+value/codes.size }
        }
        if(trades!=null)for(i in 0 until trades.length()){
            val row=trades.getJSONObject(i)
            if(row.optString("side")=="SELL")add(row,"pnl")
        }
        if(holdings!=null)for(i in 0 until holdings.length())add(holdings.getJSONObject(i),"unrealized")
        val values=JSONObject();sums.forEach{(code,value)->values.put(code,if(code in missing)JSONObject.NULL else value)}
        val total=run.optDouble("profit",Double.NaN)
        return JSONObject().put("version",VERSION).put("values",values).put("unclassified",unclassified)
            .put("otherProfit",if(total.isFinite()&&missing.isEmpty())total-sums.values.sum()-unclassified else JSONObject.NULL)
    }
}
