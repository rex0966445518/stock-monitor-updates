package com.rex.twboardingscanner.ui

import android.app.Activity
import android.widget.LinearLayout
import android.widget.TextView
import com.rex.twboardingscanner.R
import com.rex.twboardingscanner.data.BacktestFiveMinuteRepository
import com.rex.twboardingscanner.data.StockPolicyStore
import com.rex.twboardingscanner.domain.RuleMetrics
import com.rex.twboardingscanner.domain.StockScope
import com.rex.twboardingscanner.domain.StockDecision
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
class BacktestFiveMinuteFeatureTest{
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun clean(){app.getSharedPreferences("stock-entry-policy",0).edit().clear().commit()}
    private fun trade(side:String)=JSONObject().put("side",side).put("code","2330").put("name","台積電")
        .put("date","2025-08-01").put("time",if(side=="BUY")"13:30" else "09:00–13:30")
        .put("timeKind",if(side=="BUY")"收盤模型，非逐筆成交" else "盤中觸價，確切時間未知")
        .put("price",100.0).put("shares",1000).put("radar","A")
    @Test fun sharedBanWorksForBothBuyAndSellAndArchivedLotsWithoutChangingTransactions(){
        val a=Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        a.setTheme(R.style.Theme_TWBoardingScanner)
        val row=trade("BUY");val original=row.toString()
        val buy=BacktestTradeCard(a,row,"半導體");val sell=BacktestTradeCard(a,trade("SELL"),"半導體")
        val sold=BacktestProfitCard(a,trade("SELL"),true,"2025-08-01","半導體")
        a.setContentView(LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;addView(buy);addView(sell);addView(sold)})
        fun tradeButton(card:BacktestTradeCard)=card.findViewWithTag<TextView>("trade-ban")
        val first=tradeButton(buy);val second=tradeButton(sell)
        assertEquals("半導體",buy.findViewWithTag<TextView>("trade-industry").text.toString())
        assertEquals("半導體",sell.findViewWithTag<TextView>("trade-industry").text.toString())
        assertTrue(first.isEnabled);assertTrue(second.isEnabled)
        first.performClick()
        assertEquals("已禁買",first.text.toString());assertEquals("已禁買",second.text.toString())
        assertFalse(second.isEnabled)
        assertEquals("已禁買",sold.findViewWithTag<TextView>("holding-ban").text.toString())
        StockScope.entries.forEach{assertEquals(StockDecision.BANNED,StockPolicyStore(a).read().decision("2330",100.0,it))}
        assertEquals(original,row.toString())
        assertEquals("已禁買",tradeButton(BacktestTradeCard(a,trade("BUY"),"半導體")).text.toString())
    }
    @Test fun fiveMinuteParserRetainsMultipleCandlesButRejectsWrongDayAndInvalidPrices(){
        val date=LocalDate.of(2026,10,9)
        val first=date.atTime(9,0).atZone(RuleMetrics.TAIPEI).toEpochSecond()
        val q=JSONObject().put("open",JSONArray(listOf(100,101,102,103,104)))
            .put("high",JSONArray(listOf(102,103,104,105,99)))
            .put("low",JSONArray(listOf(99,100,101,102,98)))
            .put("close",JSONArray(listOf(101,102,103,104,103)))
            .put("volume",JSONArray(listOf(2000,3000,4000,5000,6000)))
        val result=JSONObject().put("meta",JSONObject().put("symbol","2330.TW"))
            .put("timestamp",JSONArray(listOf(first,first+300,first+600,first+86400,first+900)))
            .put("indicators",JSONObject().put("quote",JSONArray().put(q)))
        val source=JSONObject().put("chart",JSONObject().put("error",JSONObject.NULL)
            .put("result",JSONArray().put(result))).toString()
        val bars=BacktestFiveMinuteRepository.parse(source,"2330.TW",date)
        assertEquals(3,bars.size)
        assertEquals(300_000L,bars[1].time-bars[0].time)
        assertEquals(0,BacktestFiveMinuteChartView.markerIndex(bars,"SELL","09:00"))
        assertEquals(2,BacktestFiveMinuteChartView.markerIndex(bars,"BUY","13:30"))
        assertNull(BacktestFiveMinuteChartView.markerIndex(bars,"SELL","09:00–13:30"))
        assertNull(BacktestFiveMinuteChartView.markerIndex(bars,"SELL","時間未記錄"))
    }
    @Test fun oldBacktestCannotSynthesizeMinuteCandlesAndOnlyUsesCachedRealData(){
        val historical=LocalDate.of(2025,1,1)
        val today=LocalDate.of(2026,10,11)
        assertTrue(BacktestFiveMinuteRepository.retentionReason(historical,today)!!.contains("60 天"))
        assertNull(BacktestFiveMinuteRepository.retentionReason(today.minusDays(30),today))
        assertTrue(BacktestFiveMinuteRepository.retentionReason(today.plusDays(1),today)!!.contains("尚未"))
    }
}
