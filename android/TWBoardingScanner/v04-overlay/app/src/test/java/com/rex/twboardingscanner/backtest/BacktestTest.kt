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
    private fun bar(day:LocalDate,open:Double=100.0,close:Double=100.0,high:Double=maxOf(open,close)+1,volume:Long=1000000)=DailyBar(day.atTime(9,0).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),open,high,minOf(open,close)-1,close,volume)
    private fun days(n:Int):List<LocalDate>{val out=mutableListOf<LocalDate>();var d=first;while(out.size<n){if(d.dayOfWeek.value<=5)out+=d;d=d.plusDays(1)};return out}
    private fun prepared(n:Int=15,count:Int=1,signalEveryDay:Boolean=false):BtPrepared {
        val dates=days(n);val series=(0 until count).map{i->BtSeries("${1234+i}","測試公司$i",Market.TWSE,dates.map{bar(it)})}
        return BtPrepared(series,dates.filter{signalEveryDay||it==dates.first()}.associateWith{day->series.map{BtSignal(it.code,"A",day.minusDays(1))}},dates)
    }
    @Test fun sevenSignalsBuySevenLotsAtCloseAndNoSameDayHighExit(){
        val p=prepared(count=7).let{it.copy(series=it.series.map{s->s.copy(bars=s.bars.mapIndexed{i,b->if(i==0)b.copy(high=500.0)else b})})}
        val r=BacktestEngine.run(p,BtSettings(p.days.first(),p.days.first()))
        assertEquals(7,r.buys);assertEquals(7,r.holdings.size);assertTrue(r.closed.isEmpty())
        assertTrue(r.trades.all{it.shares==1000&&it.time=="13:30"&&it.timeKind.contains("模擬")&&it.signalDate==it.date})
        assertEquals(7,r.curve.single().selected);assertEquals(7,r.curve.single().buys)
        assertEquals(PaperEngine.execution(100.0,true),r.trades.first().price,.001)
    }
    @Test fun targetIsNetThreePercentAfterAllFeesAndTickRounding(){
        listOf(10.0,50.0,100.0,499.0,999.0,5000.0).forEach{entry->
            val cost=entry*1000+PaperEngine.fee(entry*1000);val target=BacktestEngine.targetPrice(cost);val trigger=BacktestEngine.triggerPrice(target)
            assertTrue(PaperEngine.netSell(target)>=cost*1.03-1e-8)
            assertTrue(PaperEngine.execution(trigger,false)>=target-1e-8)
        }
    }
    @Test fun nextDayIntradayTargetRecordsUnknownMinuteAndSellsOneThousand(){
        val p=prepared(3);val s=p.series.single().copy(bars=p.series.single().bars.mapIndexed{i,b->if(i==1)b.copy(high=110.0)else b})
        val r=BacktestEngine.run(p.copy(series=listOf(s)),BtSettings(p.days.first(),p.days.last()))
        assertEquals(1,r.closed.size);val buy=r.trades.first();val sell=r.closed.single()
        assertEquals(p.days[1],sell.date);assertEquals("09:00–13:30",sell.time);assertTrue(sell.timeKind.contains("未知"))
        assertEquals(buy.lotId,sell.lotId);assertEquals(1000,sell.shares)
        assertTrue(sell.pnl>=(buy.price*1000+buy.fee)*.03-1e-8)
        assertEquals(sell.pnl+r.rebateAccrued,r.profit,.001);assertTrue(r.holdings.isEmpty())
    }
    @Test fun openingGapUsesOpeningModelRatherThanFakeIntradayTime(){
        val p=prepared(2);val series=p.series.map{s->s.copy(bars=listOf(s.bars[0],bar(p.days[1],108.0,107.0,109.0)))}
        val r=BacktestEngine.run(p.copy(series=series),BtSettings(p.days.first(),p.days.last()));val sell=r.closed.single()
        assertEquals("09:00",sell.time);assertTrue(sell.timeKind.contains("模擬"));assertEquals(PaperEngine.execution(108.0,false),sell.price,.001)
    }
    @Test fun noStopLossTimeLimitOrForcedEndLiquidationAndFutureIsIgnored(){
        val p=prepared(20);val series=p.series.map{s->s.copy(bars=s.bars.mapIndexed{i,b->when{ i==19->bar(p.days[i],150.0,150.0,155.0);i>=2->bar(p.days[i],60.0,60.0,61.0);else->b}})}
        val r=BacktestEngine.run(p.copy(series=series),BtSettings(p.days.first(),p.days[18]))
        assertEquals(1,r.buys);assertTrue(r.closed.isEmpty());assertEquals(1,r.holdings.size)
        assertTrue(r.unrealized<0);assertEquals(0.0,r.realized,.001);assertEquals(r.realized+r.unrealized+r.dividendAccrued+r.rebateAccrued,r.profit,.001)
    }
    @Test fun sameStockMayAccumulateAcrossDaysButAbcDuplicatesOnlyBuyOnceDaily(){
        val p=prepared(3,signalEveryDay=true);val duplicated=p.copy(signals=p.signals.mapValues{(day,signals)->signals+BtSignal("1234","B",day.minusDays(1))+BtSignal("1234","C",day)})
        val r=BacktestEngine.run(duplicated,BtSettings(p.days.first(),p.days.last()))
        assertEquals(3,r.buys);assertEquals(3,r.holdings.size);assertEquals(3,r.holdings.map{it.lotId}.toSet().size)
        assertTrue(r.trades.all{it.radar=="A/B/C"});assertEquals(1,r.curve.first().selected)
    }
    @Test fun insufficientCashAndMissingQuoteAreRecorded(){
        val p=prepared(2,count=7);val r=BacktestEngine.run(p,BtSettings(p.days.first(),p.days.last(),150000.0))
        assertEquals(1,r.buys);assertEquals(6,r.skipped.size);assertTrue(r.cash>=0);assertTrue(r.skipped.all{it.reason.contains("資金")})
        val missing=p.copy(signals=mapOf(p.days[0] to listOf(BtSignal("9999","A"))))
        val no=BacktestEngine.run(missing,BtSettings(p.days.first(),p.days.last()));assertTrue(no.trades.isEmpty());assertTrue(no.skipped.single().reason.contains("收盤價"))
    }
    @Test fun independentLotsExitAtTheirOwnTargetsAndDividendIsNotReusableCash(){
        val p=prepared(3,signalEveryDay=true);val s=p.series.single().copy(bars=listOf(bar(p.days[0],100.0,100.0),bar(p.days[1],95.0,95.0,96.0),bar(p.days[2],99.0,99.0,101.0)),dividends=mapOf(p.days[2] to 2.0))
        val r=BacktestEngine.run(p.copy(series=listOf(s),signals=p.signals.filterKeys{it<p.days[2]}),BtSettings(p.days.first(),p.days.last()))
        assertEquals(1,r.closed.size);assertEquals("${p.days[1]}-1234",r.closed.single().lotId)
        assertEquals(1,r.holdings.size);assertEquals(p.days[0],r.holdings.single().entryDate);assertEquals(4000.0,r.dividendAccrued,.001)
        assertEquals(r.realized+r.unrealized+r.dividendAccrued+r.rebateAccrued,r.profit,.001)
    }
    @Test fun actualSelectedConditionsAreFrozenAndMissingFinancialsBlock(){
        val app=RuntimeEnvironment.getApplication();val prefs=app.getSharedPreferences("scanner_filters",0);prefs.edit().clear().commit()
        val dates=days(80);val series=BtSeries("1234","測試",Market.TWSE,dates.map{bar(it)})
        val empty=RadarType.entries.associateWith{emptySet<String>()};val rules=empty+mapOf(RadarType.A_EARLY_BREAKOUT to setOf("price"))
        val basic=BtSettings(dates[70],dates[71],rules=rules)
        assertTrue(BacktestEngine.prepare(listOf(series),basic).signals.isNotEmpty())
        assertTrue(BacktestEngine.prepare(listOf(series),basic.copy(rules=rules+mapOf(RadarType.A_EARLY_BREAKOUT to setOf("price","extra_eps")))).signals.isEmpty())
        RadarType.entries.forEach{prefs.edit().putStringSet("rules_${ScanConditions.VERSION}_${it.name}",rules[it]).commit()}
        val snapshot=BtSettingsCodec.capture(app,dates[70],dates[71],3000000.0,"");val encoded=BtSettingsCodec.encode(snapshot)
        prefs.edit().putStringSet("rules_${ScanConditions.VERSION}_${RadarType.A_EARLY_BREAKOUT.name}",setOf("extra_eps")).commit()
        assertEquals(setOf("price"),BtSettingsCodec.decode(encoded).rules[RadarType.A_EARLY_BREAKOUT]);prefs.edit().clear().commit()
    }
    @Test fun liveAbExcludesTodayWhileCIncludesTodayAndNoFutureIsUsed(){
        val dates=days(80);val prices=dates.mapIndexed{i,d->if(i==70)bar(d,49.0,49.0)else bar(d)}
        val series=BtSeries("1234","測試",Market.TWSE,prices)
        val rules=RadarType.entries.associateWith{if(it==RadarType.B_DEEP_REVERSAL)emptySet()else setOf("price")}
        val settings=BtSettings(dates[70],dates[72],rules=rules)
        val p=BacktestEngine.prepare(listOf(series),settings)
        assertEquals("A",p.signals.getValue(dates[70]).single().radar)
        assertEquals("C",p.signals.getValue(dates[71]).single().radar)
        val future=series.copy(bars=prices.mapIndexed{i,b->if(i>=73)b.copy(close=1.0,low=1.0)else b})
        assertEquals(p.signals,BacktestEngine.prepare(listOf(future),settings).signals)
    }
    @Test fun historyParserTrimsFutureAndRejectsSplitsWrongSymbol(){
        val d=days(90);val timestamps=JSONArray(d.map{it.atTime(9,0).atZone(RuleMetrics.TAIPEI).toEpochSecond()})
        val quote=JSONObject().put("open",JSONArray(List(90){100})).put("high",JSONArray(List(90){102})).put("low",JSONArray(List(90){99})).put("close",JSONArray(List(90){101})).put("volume",JSONArray(List(90){1000000}))
        val result=JSONObject().put("meta",JSONObject().put("symbol","1234.TW")).put("timestamp",timestamps).put("indicators",JSONObject().put("quote",JSONArray(listOf(quote))))
        fun raw()=JSONObject().put("chart",JSONObject().put("error",JSONObject.NULL).put("result",JSONArray(listOf(result)))).toString()
        assertEquals(71,BacktestData.parse(raw(),"1234.TW","測試",Market.TWSE,d.first(),d[70]).bars.size)
        assertThrows(Exception::class.java){BacktestData.parse(raw(),"5678.TW","測試",Market.TWSE,d.first(),d[70])}
        result.put("events",JSONObject().put("splits",JSONObject().put("123",JSONObject())))
        assertThrows(Exception::class.java){BacktestData.parse(raw(),"1234.TW","測試",Market.TWSE,d.first(),d[70])}
    }
    @Test fun persistedLotTimesAndSmallPhoneResultRemainReadable(){
        val p=prepared(count=7);val settings=BtSettings(p.days.first(),p.days.last());val run=BacktestEngine.run(p,settings)
        val context=RuntimeEnvironment.getApplication();val store=BacktestStore(context);store.begin("ui-test-v2",settings)
        store.save("ui-test-v2",BtResult(settings,run,7,7,emptyList(),"版面測試 · 同收盤模型與日線時間限制"))
        val json=store.result()!!;assertEquals(4,json.getInt("strategyVersion"));assertEquals("13:30",json.getJSONObject("run").getJSONArray("trades").getJSONObject(0).getString("time"))
        assertEquals(run.profit,json.getJSONObject("run").getDouble("profit"),.001)
        val ctl=Robolectric.buildActivity(BacktestActivity::class.java).setup();val v=ctl.get().window.decorView
        v.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1600,View.MeasureSpec.EXACTLY));v.layout(0,0,720,1600)
        fun check(view:View){if(view.visibility!=View.VISIBLE)return;if(view is TextView&&view.layout!=null&&view.text.isNotEmpty())assertTrue("clipped: ${view.text}",view.layout.height<=view.height-view.compoundPaddingTop-view.compoundPaddingBottom+2);if(view is ViewGroup)for(i in 0 until view.childCount)check(view.getChildAt(i))}
        check(v)
        fun screenshot(name:String){val b=Bitmap.createBitmap(720,1600,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));val f=File("build/ui-previews/$name.png");f.parentFile.mkdirs();f.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        screenshot("backtest-v2-360")
        val scroll=(ctl.get().findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as android.widget.ScrollView);scroll.scrollTo(0,1200);screenshot("backtest-v2-results-360")
        ctl.pause().stop().destroy();context.getSharedPreferences("backtest_status",0).edit().clear().commit()
    }
}
