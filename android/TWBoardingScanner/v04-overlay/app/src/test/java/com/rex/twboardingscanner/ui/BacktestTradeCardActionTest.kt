package com.rex.twboardingscanner.ui

import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class BacktestTradeCardActionTest {
    private fun find(v:View,tag:String):View? {
        if(v.tag==tag)return v
        if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),tag)?.let{return it}
        return null
    }
    @Test fun dailySellCardShowsExactReasonAndOpensSelectedStockInChipK(){
        val ctl=Robolectric.buildActivity(BacktestActivity::class.java).setup();val a=ctl.get()
        val intent=ChipKLauncher.stockIntent("5475")
        Shadows.shadowOf(a.packageManager).addResolveInfoForIntent(intent,ResolveInfo().apply{
            activityInfo=ActivityInfo().apply{packageName=ChipKLauncher.PACKAGE;name="ChartActivity"}
        })
        val reason="獲利達標後續抱；2026-09-02 日K收盤190跌破5日線199，下一交易日開盤賣出"
        val card=BacktestTradeCard(a,JSONObject().put("side","SELL").put("code","5475").put("name","德宏").put("reason",reason),"電子零組件")
        assertEquals("賣出理由",(find(card,"trade-reason-title") as TextView).text.toString())
        assertEquals(reason,(find(card,"trade-reason") as TextView).text.toString())
        assertTrue(card.performClick())
        val opened=Shadows.shadowOf(a).nextStartedActivity
        assertEquals(ChipKLauncher.PACKAGE,opened.`package`)
        assertEquals(intent.data,opened.data)
        ctl.pause().stop().destroy()
    }
    @Test fun legacySellWithoutReasonDoesNotInventAnExitTrigger(){
        val ctl=Robolectric.buildActivity(BacktestActivity::class.java).setup()
        val card=BacktestTradeCard(ctl.get(),JSONObject().put("side","SELL").put("code","5475").put("name","德宏"),"電子零組件")
        assertEquals("舊紀錄未保存原因",(find(card,"trade-reason") as TextView).text.toString())
        ctl.pause().stop().destroy()
    }
}
