package com.rex.twboardingscanner.backtest

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BacktestRadarProfitTest {
    @Test fun sellsAndOpenLotsGroupByEntryRadarWithoutCountingBuyTwice(){
        val run=JSONObject().put("profit",190500.0)
            .put("trades",JSONArray().put(JSONObject().put("side","BUY").put("radar","A").put("pnl",-500000))
                .put(JSONObject().put("side","SELL").put("radar","A").put("pnl",190000))
                .put(JSONObject().put("side","SELL").put("radar","B/C").put("pnl",-2000)))
            .put("holdings",JSONArray().put(JSONObject().put("radar","E").put("unrealized",2000)))
        val result=BacktestRadarProfit.summarize(run);val values=result.getJSONObject("values")
        assertEquals(190000.0,values.getDouble("A"),.001)
        assertEquals(-1000.0,values.getDouble("B"),.001);assertEquals(-1000.0,values.getDouble("C"),.001)
        assertEquals(0.0,values.getDouble("D"),.001);assertEquals(2000.0,values.getDouble("E"),.001)
        assertEquals(500.0,result.getDouble("otherProfit"),.001)
    }
    @Test fun missingLegacyAmountsStayUnknownAndUnclassifiedTradesRemainSeparate(){
        val result=BacktestRadarProfit.summarize(JSONObject().put("profit",7).put("trades",JSONArray()
            .put(JSONObject().put("side","SELL").put("radar","?").put("pnl",7)))
            .put("holdings",JSONArray().put(JSONObject().put("radar","D"))))
        assertTrue(result.getJSONObject("values").isNull("D"))
        assertEquals(7.0,result.getDouble("unclassified"),.001)
        assertTrue(result.isNull("otherProfit"))
        assertTrue(BacktestRadarProfit.summarize(JSONObject()).getJSONObject("values").isNull("A"))
    }
}
