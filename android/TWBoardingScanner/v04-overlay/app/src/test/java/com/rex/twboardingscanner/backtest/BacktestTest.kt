package com.rex.twboardingscanner.backtest

import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.paper.PaperEngine
import com.rex.twboardingscanner.ui.BacktestActivity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.io.File
import org.json.JSONObject
import org.json.JSONArray

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BacktestTest {
    private val first=LocalDate.of(2025,1,2)
    private fun bar(day:LocalDate,open:Double=100.0,close:Double=100.0,volume:Long=1000000)=DailyBar(day.atTime(9,0).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),open,maxOf(open,close)+1,minOf(open,close)-1,close,volume)
    private fun days(n:Int):List<LocalDate>{val out=mutableListOf<LocalDate>();var d=first;while(out.size<n){if(d.dayOfWeek.value<=5)out+=d;d=d.plusDays(1)};return out}
    private fun prepared(n:Int=110):BtPrepared {
        val dates=days(n);val bars=dates.mapIndexed{i,d->bar(d,100.0+(i%10),100.0+((i+1)%10))}
        return BtPrepared(listOf(BtSeries("1234","測試公司",Market.TWSE,bars)),dates.associateWith{listOf(BtSignal("1234","A",2500,true,60.0))},dates)
    }
    @Test fun openEntryCannotReadSameDayCloseHighOrVolume(){
        val prev=first;val day=first.plusDays(1);val base=BtSeries("1234","測試",Market.TWSE,listOf(bar(prev),bar(day)))
        val signal=listOf(BtSignal("1234","C",1000,true,60.0))
        fun run(today:DailyBar):BtRun{val b=BtRun(3000000.0);BacktestEngine.advance(b,day,BtParams(),signal,prev,mapOf("1234" to mapOf(prev to bar(prev),day to today)),mapOf("1234" to base));return b}
        val a=run(bar(day));val b=run(bar(day,100.0,50.0,0))
        assertEquals(a.trades,b.trades);assertEquals(1,a.trades.size);assertEquals(prev,a.trades.single().signalDate)
        assertEquals(1000,a.trades.single().shares);assertNotEquals(a.equity,b.equity,0.0)
    }
    @Test fun stopUsesPriorCloseAndGapOpeningPriceWithCosts(){
        val dates=days(3);val s=BtSeries("1234","測試",Market.TWSE,listOf(bar(dates[0]),bar(dates[1],100.0,96.0),bar(dates[2],94.0,95.0)))
        val indexed=mapOf(s.code to s.bars.associateBy{RuleMetrics.tradingDate(it.time)});val meta=mapOf(s.code to s)
        val b=BtRun(3000000.0);val sig=listOf(BtSignal(s.code,"A",1000,true,60.0))
        BacktestEngine.advance(b,dates[1],BtParams(),sig,dates[0],indexed,meta)
        assertEquals(1,b.trades.size) // same-day fall does not cause a fictional intraday exit
        BacktestEngine.advance(b,dates[2],BtParams(),sig,dates[1],indexed,meta)
        assertEquals(2,b.trades.size);assertTrue(b.holdings.isEmpty())
        val sell=b.trades.last();assertEquals(PaperEngine.execution(94.0,false),sell.price,.001)
        assertEquals(sell.pnl,b.profit,.001);assertTrue(b.cash>=0);assertTrue(sell.pnl<0)
    }
    @Test fun incompleteLiquidityAndCashDoNotCreateOrders(){
        val p=prepared(2);val indexed=p.series.associate{it.code to it.bars.associateBy{RuleMetrics.tradingDate(it.time)}}
        val b=BtRun(50000.0);BacktestEngine.advance(b,p.days[1],BtParams(),p.signals[p.days[0]]!!,p.days[0],indexed,p.series.associateBy{it.code});assertTrue(b.trades.isEmpty())
        val nothing=BtPrepared(p.series,emptyMap(),p.days);assertTrue(BacktestEngine.run(nothing,BtSettings(p.days.first(),p.days.last(),optimize=false)).first.trades.isEmpty())
    }
    @Test fun forwardTrainingEndsBeforeValidationAndFutureCannotSelectFirstParameters(){
        val p=prepared();val s=BtSettings(p.days.first(),p.days.last())
        val result=BacktestEngine.run(p,s).first
        assertEquals(p.days[60],result.curve.first().date);assertEquals(3,result.folds.size)
        assertTrue(result.folds.all{it.trainEnd<it.start&&it.trials==9})
        assertTrue(result.trades.all{it.signalDate<it.date&&it.date>=p.days[60]})
        val changed=p.copy(series=p.series.map{series->series.copy(bars=series.bars.map{if(RuleMetrics.tradingDate(it.time)>=p.days[60])it.copy(open=it.open*4,high=it.high*4,low=it.low*4,close=it.close*4) else it})})
        assertEquals(result.folds.first(),BacktestEngine.run(changed,s).first.folds.first())
        val tiny=prepared(10);assertTrue(BacktestEngine.run(tiny,BtSettings(tiny.days.first(),tiny.days.last())).first.folds.isEmpty())
    }
    @Test fun dividendIsAccruedButNotSpendableAndMissingDayDoesNotFill(){
        val p=prepared(4);val s=p.series[0].copy(dividends=mapOf(p.days[2] to 2.0))
        val indexed=mapOf(s.code to s.bars.associateBy{RuleMetrics.tradingDate(it.time)});val b=BtRun(3000000.0)
        BacktestEngine.advance(b,p.days[1],BtParams(),p.signals[p.days[0]]!!,p.days[0],indexed,mapOf(s.code to s));val cash=b.cash
        BacktestEngine.advance(b,p.days[2],BtParams(),emptyList(),p.days[1],indexed,mapOf(s.code to s))
        assertEquals(2000.0,b.dividendAccrued,.001);assertEquals(cash,b.cash,.001)
        val noQuotes=mapOf(s.code to emptyMap<LocalDate,DailyBar>())
        BacktestEngine.advance(b,p.days[3],BtParams(),emptyList(),p.days[2],noQuotes,mapOf(s.code to s));assertEquals(1,b.trades.size);assertEquals(p.days[2],b.holdings[0].markDate)
    }
    @Test fun signalsBeforeCutoffUnaffectedByFutureBarsAndCurrentStockFields(){
        val dates=days(100);val prices=dates.mapIndexed{i,d->bar(d,100+i*.1,100+i*.1)}
        val series=BtSeries("1234","測試",Market.TWSE,prices)
        val settings=BtSettings(dates[60],dates.last(),optimize=false)
        val before=BacktestEngine.prepare(listOf(series),settings)
        val altered=series.copy(bars=prices.mapIndexed{i,b->if(i>=85)b.copy(close=b.close*2,high=b.high*2) else b})
        val after=BacktestEngine.prepare(listOf(altered),settings)
        assertEquals(before.signals.filterKeys{it<dates[85]},after.signals.filterKeys{it<dates[85]})
    }
    @Test fun historyParserTrimsFutureAndRejectsSplitsWrongSymbol(){
        val d=days(90);val timestamps=JSONArray(d.map{it.atTime(9,0).atZone(RuleMetrics.TAIPEI).toEpochSecond()})
        val quote=JSONObject().put("open",JSONArray(List(90){100})).put("high",JSONArray(List(90){102})).put("low",JSONArray(List(90){99})).put("close",JSONArray(List(90){101})).put("volume",JSONArray(List(90){1000000}))
        val result=JSONObject().put("meta",JSONObject().put("symbol","1234.TW")).put("timestamp",timestamps).put("indicators",JSONObject().put("quote",JSONArray(listOf(quote))))
        fun raw()=JSONObject().put("chart",JSONObject().put("error",JSONObject.NULL).put("result",JSONArray(listOf(result)))).toString()
        val parsed=BacktestData.parse(raw(),"1234.TW","測試",Market.TWSE,d.first(),d[70]);assertEquals(71,parsed.bars.size)
        assertThrows(Exception::class.java){BacktestData.parse(raw(),"5678.TW","測試",Market.TWSE,d.first(),d[70])}
        result.put("events",JSONObject().put("splits",JSONObject().put("123",JSONObject())))
        assertThrows(Exception::class.java){BacktestData.parse(raw(),"1234.TW","測試",Market.TWSE,d.first(),d[70])}
    }
    @Test fun persistedReportAndSmallPhoneUiRender(){
        val p=prepared();val settings=BtSettings(p.days.first(),p.days.last());val (run,base)=BacktestEngine.run(p,settings)
        val context=RuntimeEnvironment.getApplication();val store=BacktestStore(context);store.begin("ui-test",settings)
        store.save("ui-test",BtResult(settings,run,base,100,97,listOf("5678：歷史資料不足"),"版面測試 · 現存股票樣本，存在存活偏差"))
        val json=store.result()!!;assertEquals(run.profit,json.getJSONObject("run").getDouble("profit"),.001)
        val ctl=Robolectric.buildActivity(BacktestActivity::class.java).setup();val v=ctl.get().window.decorView
        v.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1600,View.MeasureSpec.EXACTLY));v.layout(0,0,720,1600)
        fun check(view:View){if(view is TextView&&view.layout!=null&&view.text.isNotEmpty())assertTrue("clipped: ${view.text}",view.layout.height<=view.height-view.compoundPaddingTop-view.compoundPaddingBottom+2);if(view is ViewGroup)for(i in 0 until view.childCount)check(view.getChildAt(i))}
        check(v);val bitmap=Bitmap.createBitmap(720,1600,Bitmap.Config.ARGB_8888);v.draw(Canvas(bitmap));val f=File("build/ui-previews/backtest-360.png");f.parentFile.mkdirs();f.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        ctl.pause().stop().destroy();context.getSharedPreferences("backtest_status",0).edit().clear().commit()
    }
}
