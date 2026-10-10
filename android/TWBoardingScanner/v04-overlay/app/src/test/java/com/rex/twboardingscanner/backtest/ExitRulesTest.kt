package com.rex.twboardingscanner.backtest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.EditText
import androidx.appcompat.view.ContextThemeWrapper
import com.rex.twboardingscanner.R
import com.rex.twboardingscanner.data.ExitRuleStore
import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.paper.*
import com.rex.twboardingscanner.ui.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.json.JSONArray
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExitRulesTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val entry=LocalDate.of(2026,8,3)
    @Before fun clear(){listOf("exit_rules_v1","backtest_status","backtest_robot","stock-entry-policy","paper_trading_v1").forEach{app.getSharedPreferences(it,0).edit().clear().commit()}}
    private fun bar(d:LocalDate,p:Double)=DailyBar(d.atTime(13,30).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),p,p,p,p,1000000)
    private fun match(r:List<ExitRule>,loss:Double=-2.0,d:LocalDate=entry.plusDays(16),bars:List<DailyBar> = emptyList())=ExitRules.match(r,entry,d,loss,bars)
    @Test fun blanksAndHalfComparisonsNeverBecomeAlwaysTrue(){
        assertNull(match(List(5){ExitRule()}))
        assertNull(match(listOf(ExitRule(compare=LossCompare.LT))))
        assertNull(match(listOf(ExitRule(lossPct=3.0))))
        assertEquals(0,match(listOf(ExitRule(days=15,lossPct=3.0)),loss=-8.0))
        assertNull(match(listOf(ExitRule(days=15)),loss=0.0))
        assertNull(match(listOf(ExitRule(days=15)),loss=2.0))
        assertNull(match(listOf(ExitRule(days=15)),loss=Double.NaN))
    }
    @Test fun strictDaysPositiveLossMagnitudeAndOrAcrossAllFiveRows(){
        val r=ExitRule(15,LossCompare.LT,3.0)
        assertNull(match(listOf(r),d=entry.plusDays(15)))
        assertEquals(0,match(listOf(r)))
        assertNull(match(listOf(r),loss=-3.0));assertNull(match(listOf(r),loss=-5.0))
        assertEquals(0,match(listOf(r.copy(compare=LossCompare.LTE)),loss=-3.0))
        assertEquals(0,match(listOf(r.copy(compare=LossCompare.GTE)),loss=-3.0))
        assertEquals(4,match(List(4){ExitRule(days=99)}+r))
        assertEquals(0,match(listOf(r,r)))
    }
    @Test fun fullExampleNeedsActualDownwardDifAndBelowAverage(){
        val date=entry.plusDays(16)
        val bars=(0..39).map{i->bar(date.minusDays((39-i).toLong()),120.0-i)}
        assertEquals(0,match(ExitRules.example(),d=date,bars=bars))
        assertNull(match(ExitRules.example(),d=date,bars=bars.takeLast(10)))
        assertNull(match(ExitRules.example(),d=date,bars=(0..39).map{i->bar(date.minusDays((39-i).toLong()),100.0)}))
        assertNull(match(listOf(ExitRule(macd=ExitMacd.UP)),d=date,bars=bars))
        assertNull(match(listOf(ExitRule(maDays=20)),d=date,bars=bars.dropLast(1)))
        assertEquals(0,match(ExitRules.example(),d=date,bars=bars+bar(date.plusDays(1),999.0)))
    }
    @Test fun invalidValuesRejectedAndRulesRoundTripWithEmptySlots(){
        listOf(ExitRule(days=-1),ExitRule(lossPct=Double.NaN),ExitRule(maDays=0),ExitRule(maDays=321)).forEach{assertTrue(runCatching{it.validate()}.isFailure)}
        assertTrue(runCatching{ExitRules.validate(List(6){ExitRule()})}.isFailure)
        assertTrue(runCatching{ExitRules.read(JSONArray("[{\"days\":1.5}]"))}.isFailure)
        val rules=ExitRules.example().toMutableList();rules[3]=ExitRule(lossPct=2.5)
        assertEquals(rules,ExitRules.read(ExitRules.json(rules)))
        val settings=BtSettings(entry,entry.plusDays(20),exitRules=rules)
        val json=BtSettingsCodec.encode(settings)
        assertEquals(settings,BtSettingsCodec.decode(json))
        json.remove("exitRules");assertTrue(BtSettingsCodec.decode(json).exitRules.isEmpty())
    }
    @Test fun previousCompletedCandleExitsAtNextOpenWithoutFutureLeakOrDuplicateSale(){
        val series=BtSeries("2330","測試股",Market.TWSE,listOf(bar(entry,100.0),bar(entry.plusDays(1),98.0),bar(entry.plusDays(2),97.0)))
        val p=BtPrepared(listOf(series),mapOf(entry to listOf(BtSignal("2330","A"))),series.bars.map{RuleMetrics.tradingDate(it.time)})
        val settings=BtSettings(entry,entry.plusDays(2),exitRules=listOf(ExitRule(compare=LossCompare.GT,lossPct=2.0)))
        val result=BacktestEngine.run(p,settings)
        assertEquals(1,result.buys);assertEquals(1,result.closed.size)
        val sell=result.closed.single();assertEquals(entry.plusDays(2),sell.date);assertEquals("09:00",sell.time)
        assertEquals(entry.plusDays(1),sell.dataDate);assertEquals(PaperEngine.execution(97.0,false),sell.price,0.0)
        assertTrue(sell.pnl<0);assertTrue(sell.reason.contains("下車條件 1"));assertTrue(result.holdings.isEmpty())
        assertEquals(result.capital+sell.pnl+result.rebateAccrued,result.equity,0.00001)
        val old=BacktestEngine.run(p,settings.copy(exitRules=emptyList()));assertEquals(0,old.closed.size);assertEquals(1,old.holdings.size)
        val cutoff=BacktestEngine.run(p,settings.copy(end=entry.plusDays(1)));assertEquals(0,cutoff.closed.size)
    }
    @Test fun scannerPaperHoldingsUseSameRulesAndRequireActualHistory(){
        val today=entry.plusDays(17);val now=today.atTime(10,0).atZone(TAIPEI).toInstant().toEpochMilli()
        val known=(0..39).map{i->bar(today.minusDays((40-i).toLong()),120.0-i)}
        val b=PaperBook(enabled=true);b.positions+=PaperPosition("2330","測試股","tse","A",82.0,82117.0,entry.atTime(13,0).atZone(TAIPEI).toInstant().toEpochMilli(),82.0,81.0,now)
        val q=PaperQuote("2330",now,81.0,81.0,81.0,10.0,10.0,10000,90.0,70.0)
        PaperEngine.step(b,mapOf("2330" to q),now,exitRules=ExitRules.example())
        assertEquals(1,b.positions.size)
        PaperEngine.step(b,mapOf("2330" to q),now,exitRules=ExitRules.example(),histories=mapOf("2330" to known))
        assertTrue(b.positions.isEmpty());assertTrue(b.trades.single().reason.contains("下車條件 1"))
        PaperEngine.step(b,mapOf("2330" to q),now,exitRules=ExitRules.example(),histories=mapOf("2330" to known));assertEquals(1,b.trades.size)
    }
    @Test fun scopedPersistenceAndRobotCheckpointPreserveFrozenRules(){
        val store=ExitRuleStore(app);store.save(StockScope.SCANNER,ExitRules.example())
        assertTrue(store.read(StockScope.BACKTEST).isEmpty());assertTrue(store.read(StockScope.ROBOT).isEmpty())
        assertEquals(ExitRules.example(),ExitRuleStore(app).read(StockScope.SCANNER))
        val s=BtRobotSpace.settings().copy(exitRules=ExitRules.example(),stockScope=StockScope.ROBOT)
        val robots=BtRobotStore(app);val id=robots.create(s)
        val file=File(app.cacheDir,"exit-checkpoint.zip");BtRobotCheckpoint.export(app,id,file)
        val imported=file.inputStream().use{BtRobotCheckpoint.restore(app,it)}
        assertEquals(s,BtSettingsCodec.decode(robots.session(imported)!!.getJSONObject("settings")))
    }
    @Test fun fiveCardsAllowPartialSaveClearAndReopenAndRender(){
        val c=ContextThemeWrapper(app,R.style.Theme_TWBoardingScanner)
        val form=ExitRulesEditor.Form(c,ExitRules.example())
        assertEquals(5,form.read().size);assertEquals(ExitRules.example(),form.read())
        form.findViewWithTag<EditText>("exit-0-days").setText("")
        assertNull(form.read()[0].days);assertEquals(20,form.read()[0].maDays)
        form.findViewWithTag<EditText>("exit-4-days").setText("30")
        val copied=ExitRulesEditor.Form(c,form.read());assertEquals(30,copied.read()[4].days)
        form.bind(List(5){ExitRule()});assertFalse(form.read().any{it.active})
        form.bind(ExitRules.example())
        form.measure(View.MeasureSpec.makeMeasureSpec(656,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));form.layout(0,0,656,form.measuredHeight)
        val first=form.findViewWithTag<View>("exit-card-0")
        val b=Bitmap.createBitmap(first.width,first.height,Bitmap.Config.ARGB_8888);first.draw(Canvas(b))
        val f=File("build/ui-previews/exit-rule-card-360.png");f.parentFile!!.mkdirs();f.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
    }
    @Test fun threeRequestedScreensExposeEditors(){
        val home=Robolectric.buildActivity(MainActivity::class.java).setup();assertNotNull(home.get().findViewById<View>(R.id.exitRulesButton));home.pause().stop().destroy()
        val bt=Robolectric.buildActivity(BacktestActivity::class.java).setup();assertNotNull(bt.get().window.decorView.findViewWithTag<View>("backtest-exit-rules"));bt.pause().stop().destroy()
        val robot=Robolectric.buildActivity(BtRobotActivity::class.java).setup();assertNotNull(robot.get().window.decorView.findViewWithTag<View>("robot-exit-rules"));robot.pause().stop().destroy()
    }
}
