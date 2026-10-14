package com.rex.twboardingscanner.backtest

import org.json.JSONObject

/** Read older summaries without requiring every display-only field introduced later. */
internal object BtTrialSummary {
    fun read(raw:String):JSONObject?=runCatching{
        JSONObject(raw).also{s->
            require(s.getString("key").isNotBlank()&&s.getLong("seq")>0&&s.getDouble("profit").isFinite())
            listOf("finishedAt","startedAt","buys","closed","pendingChecks").forEach{s.put(it,s.optLong(it,0))}
            listOf("drawdown","realized","unrealized").forEach{if(!s.optDouble(it,Double.NaN).isFinite())s.put(it,0.0)}
        }
    }.getOrNull()
}
