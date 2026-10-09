package com.rex.twboardingscanner.backtest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.paper.PaperEngine
import com.rex.twboardingscanner.ui.BacktestActivity
import com.rex.twboardingscanner.ui.BacktestJournalUi
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BacktestLimitsTest {
    private val first=LocalDate.of(2025,8,1)
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun clean(){
        listOf("backtest_status","scanner_filters").forEach{app.getSharedPreferences(it,0).edit().clear().commit()}
        File(app.filesDir,"backtests").deleteRecursively();File(app.filesDir,"backtest-journal").deleteRecursively()
    }
    private fun bar(day:LocalDate,price:Double=100.0,high:Double=price)=DailyBar(day.atTime(9,0).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),price,high,price,price,1000000)
    private fun fixture(count:Int,days:List<LocalDate>):BtPrepared {
        val series=(0 until count).map{BtSeries("${2000+it}","持倉測試$it",Market.TWSE,days.map{d->bar(d)})}
        return BtPrepared(series,days.associateWith{series.reversed().map{BtSignal(it.code,"A")}},days)
    }
    @Test fun defaultCapCountsUniqueStocksAndAllowsExistingLots(){
        val days=listOf(first,first.plusDays(3));val p=fixture(27,days)
        val run=BacktestEngine.run(p,BtSettings(first,days.last(),100000000.0))
        assertEquals(50,run.buys);assertEquals(25,run.holdings.map{it.code}.distinct().size)
        assertEquals((2000..2024).map{it.toString()},run.holdings.map{it.code}.distinct())
        assertEquals(4,run.skipped.size);assertTrue(run.skipped.all{it.reason.contains("最高持倉 25 檔")})
        assertTrue(run.curve.all{it.buys==25&&it.skipped==2})
        val old=BacktestEngine.run(p,BtSettings(first,days.last(),100000000.0,strategyVersion=3))
        assertEquals(54,old.buys);assertEquals(27,old.holdings.map{it.code}.distinct().size)
    }
    @Test fun sellingOneOfTwoLotsDoesNotFreeSlotButLastSaleDoes(){
        val days=listOf(first,first.plusDays(3),first.plusDays(4),first.plusDays(5))
        val p=fixture(2,days)
        val a=p.series[0].copy(bars=listOf(bar(days[0]),bar(days[1],90.0),bar(days[2],90.0,95.0),bar(days[3],108.0)))
        val signals=mapOf(days[0] to listOf(BtSignal("2000","A")),days[1] to listOf(BtSignal("2000","A")),days[2] to listOf(BtSignal("2001","B")),days[3] to listOf(BtSignal("2001","B")))
        val run=BacktestEngine.run(p.copy(series=listOf(a,p.series[1]),signals=signals),BtSettings(first,days.last(),maxHoldingStocks=1))
        assertEquals(3,run.buys);assertEquals(2,run.closed.size)
        assertEquals(days[2],run.closed.first().date);assertEquals("${days[1]}-2000",run.closed.first().lotId)
        assertEquals(days[2],run.skipped.single().date)
        assertEquals("2001",run.holdings.single().code);assertEquals(days[3],run.holdings.single().entryDate)
        assertEquals(listOf("SELL","BUY"),run.trades.filter{it.date==days[3]}.map{it.side})
    }
    @Test fun customTargetsChangeExitDayAndKeepPerLotNetAccounting(){
        val days=listOf(first,first.plusDays(3),first.plusDays(4));val p=fixture(1,days)
        val s=p.series.single().copy(bars=listOf(bar(days[0]),bar(days[1],100.0,104.0),bar(days[2],100.0,111.0)))
        val prepared=p.copy(series=listOf(s),signals=mapOf(first to listOf(BtSignal("2000","C"))))
        val settings=BtSettings(first,days.last(),targetNetPct=7.5)
        val run=BacktestEngine.run(prepared,settings);val sell=run.closed.single();val buy=run.trades.first()
        val cost=buy.price*1000+buy.fee
        assertEquals(days[2],sell.date);assertTrue(sell.pnl>=cost*.075-1e-8)
        assertEquals(BacktestEngine.targetPrice(cost,.075),sell.price,.001);assertTrue(sell.reason.contains("7.5%"))
        assertEquals(days[1],BacktestEngine.run(prepared,settings.copy(targetNetPct=1.25)).closed.single().date)
        listOf(.01,1.25,7.5,1000.0).forEach{pct->
            val target=BacktestEngine.targetPrice(cost,pct/100)
            assertTrue(PaperEngine.netSell(target)+1e-8>=cost*(1+pct/100))
        }
    }
    @Test fun daySixBreakEvenStillAppliesToCustomProfitTarget(){
        val days=listOf(first,first.plusDays(5),first.plusDays(6));val p=fixture(1,days)
        val s=p.series.single().copy(bars=listOf(bar(days[0]),bar(days[1],100.0,102.0),bar(days[2],100.0,102.0)))
        val run=BacktestEngine.run(p.copy(series=listOf(s),signals=mapOf(first to listOf(BtSignal("2000","A")))),BtSettings(first,days.last(),targetNetPct=10.0))
        val sale=run.closed.single();val cost=run.trades.first().let{it.price*1000+it.fee}
        assertEquals(days[2],sale.date);assertTrue(sale.pnl>=0&&sale.pnl<cost*.1);assertTrue(sale.reason.contains("保本"))
    }
    @Test fun snapshotsRoundTripRemainFrozenAndRejectInvalidTradingInputs(){
        val settings=BtSettings(first,first.plusDays(3),maxHoldingStocks=12,targetNetPct=4.25)
        val json=BtSettingsCodec.encode(settings);assertEquals(settings,BtSettingsCodec.decode(json))
        assertTrue(BacktestJournalUi.describe(json).contains("最高持倉 12 檔"));assertTrue(BacktestJournalUi.describe(json).contains("4.25%"))
        val store=BacktestStore(app);store.begin("limits-frozen",settings)
        store.saveDraft(settings.copy(maxHoldingStocks=7,targetNetPct=2.5))
        assertEquals(settings,BtSettingsCodec.decode(store.log("limits-frozen")!!.getJSONObject("settings")))
        val p=fixture(1,listOf(first,first.plusDays(3)));val run=BacktestEngine.run(p,settings)
        assertTrue(store.save("limits-frozen",BtResult(settings,run,1,1,emptyList(),"test")))
        store.resetCurrent()
        assertEquals(settings,BtSettingsCodec.decode(store.log("limits-frozen")!!.getJSONObject("settings")))
        for(version in 2..3){
            val legacy=BtSettingsCodec.encode(settings.copy(strategyVersion=version,maxHoldingStocks=null,targetNetPct=3.0))
            legacy.remove("maxHoldingStocks");val decoded=BtSettingsCodec.decode(legacy)
            assertNull(decoded.maxHoldingStocks);assertEquals(3.0,decoded.targetNetPct,0.0)
        }
        listOf(0.0,-1.0,2.5,2147483648.0).forEach{bad->assertThrows(Exception::class.java){BtSettingsCodec.decode(JSONObject(json.toString()).put("maxHoldingStocks",bad))}}
        listOf(0.0,-1.0,1001.0).forEach{bad->assertThrows(Exception::class.java){BtSettingsCodec.decode(JSONObject(json.toString()).put("targetNetPct",bad))}}
        assertThrows(Exception::class.java){BacktestEngine.run(p,settings.copy(targetNetPct=Double.NaN))}
    }
    private fun find(v:View,match:(View)->Boolean):View? {
        if(match(v))return v
        if(v is ViewGroup)for(i in 0 until v.childCount){find(v.getChildAt(i),match)?.let{return it}}
        return null
    }
    @Test fun numericControlsPersistValidateAndFitSmallPhones(){
        val ctl=Robolectric.buildActivity(BacktestActivity::class.java).setup();val a=ctl.get();val decor=a.window.decorView
        val limit=find(decor){it.tag=="max-holding-stocks"} as EditText
        val target=find(decor){it.tag=="target-net-pct"} as EditText
        assertEquals("25",limit.text.toString());assertEquals("3",target.text.toString())
        limit.setText("12");target.setText("4.25")
        val store=BacktestStore(app);val saved=BtSettingsCodec.decode(store.configuration()!!)
        assertEquals(12,saved.maxHoldingStocks);assertEquals(4.25,saved.targetNetPct,0.0)
        store.begin("ui-frozen",saved)
        limit.setText("7");target.setText("2.5")
        assertEquals(saved,BtSettingsCodec.decode(store.log("ui-frozen")!!.getJSONObject("settings")))
        a.clearCurrent();assertEquals(7,BtSettingsCodec.decode(store.configuration()!!).maxHoldingStocks)
        val launch=find(decor){it is TextView&&it.text.toString()=="開始自動回測"}!!
        limit.setText("0");launch.performClick();assertNotNull(limit.error);assertEquals("",store.active())
        limit.setText("7");target.setText("0");launch.performClick();assertNotNull(target.error);assertEquals("",store.active())
        target.setText("2.5")
        // Revalidate without launching a worker before capturing.
        find(decor){it is TextView&&it.text.toString()=="查看完整回測條件"}!!.performClick()
        org.robolectric.shadows.ShadowDialog.getLatestDialog().dismiss()
        val panel=find(decor){it.tag=="backtest-trading-controls"}!!
        capture(panel)
        ctl.pause().stop().destroy()
        val reopened=Robolectric.buildActivity(BacktestActivity::class.java).setup()
        assertEquals("7",(find(reopened.get().window.decorView){it.tag=="max-holding-stocks"} as EditText).text.toString())
        assertEquals("2.5",(find(reopened.get().window.decorView){it.tag=="target-net-pct"} as EditText).text.toString())
        reopened.pause().stop().destroy()
    }
    private fun capture(v:View){
        v.measure(View.MeasureSpec.makeMeasureSpec(664,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1800,View.MeasureSpec.AT_MOST));v.layout(0,0,664,v.measuredHeight)
        fun verify(x:View){if(x is TextView&&x.layout!=null&&x.text.isNotEmpty())assertTrue("clipped: ${x.text}",x.layout.height<=x.height-x.compoundPaddingTop-x.compoundPaddingBottom+2);x.jumpDrawablesToCurrentState();if(x is ViewGroup)for(i in 0 until x.childCount)verify(x.getChildAt(i))}
        verify(v);val image=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(image))
        val file=File("build/ui-previews/backtest-trading-controls-360.png");file.parentFile.mkdirs();file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
}
