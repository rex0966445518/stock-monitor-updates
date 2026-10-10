package com.rex.twboardingscanner.backtest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.rex.twboardingscanner.data.MarketIndexData
import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.paper.*
import com.rex.twboardingscanner.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarketCrashGuardTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private fun market():List<MarketIndexBar>{
        val closes=listOf(20000.0,20100.0,20200.0,20300.0,20400.0,20500.0,19500.0,18499.99,18600.0,18700.0,18800.0,18900.0,19000.0,17999.99,18000.0)
        var day=LocalDate.of(2026,9,17)
        return closes.mapIndexed{i,c->while(day.dayOfWeek.value>=6)day=day.plusDays(1)
            MarketIndexBar(day,c,if(i==0)100.0 else c-closes[i-1]).also{day=day.plusDays(1)}}
    }
    private fun stamp(d:LocalDate,hour:Int=13,minute:Int=30)=d.atTime(hour,minute).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli()
    private fun stock(days:List<LocalDate>)=BtSeries("2330","測試股",Market.TWSE,days.mapIndexed{i,d->val p=if(i==0)100.0 else 106.0;DailyBar(stamp(d),p,p,p,p,1000000)})
    @Test fun strictThresholdLatchRecoveryWeekendAndNoLookahead(){
        val bars=market();val days=bars.map{it.date};val g=MarketCrashGuard.forDays(bars,days)
        assertTrue(g.getValue(days[6]).canBuy)
        assertTrue("Exactly 1000 points does not trigger",g.getValue(days[7]).canBuy)
        assertEquals("PAUSED",g.getValue(days[8]).state)
        assertFalse("First rebound cannot release",g.getValue(days[9]).canBuy)
        assertFalse("Two rebounds below MA5 cannot release",g.getValue(days[10]).canBuy)
        assertFalse("Recovery close is not known earlier that day",g.getValue(days[11]).canBuy)
        assertEquals(1,days[12].dayOfWeek.value)
        assertTrue("Friday confirmation releases on Monday",g.getValue(days[12]).canBuy)
        assertTrue("Today's new crash affects tomorrow",g.getValue(days[13]).canBuy)
        assertEquals("PAUSED",g.getValue(days[14]).state)
        val changed=bars.mapIndexed{i,b->if(i>=12)b.copy(close=b.close+50000,change=0.0)else b}
        val next=MarketCrashGuard.forDays(changed,days)
        days.take(12).forEach{assertEquals(g[it],next[it])}
    }
    @Test fun missingOrStaleDataDoesNotUnlockAndARepeatCrashRestartsProtection(){
        val bars=market();val days=bars.map{it.date}
        val g=MarketCrashGuard.forDays(bars.filterIndexed{i,_->i!=8},days)
        assertEquals("UNKNOWN",g.getValue(days[8]).state)
        assertFalse(g.getValue(days[9]).canBuy)
        assertFalse(MarketCrashGuard.before(bars.take(8),days[12],days[11]).canBuy)
        assertEquals("UNKNOWN",MarketCrashGuard.before(bars.take(8),days[12],days[11]).state)
        val afterCrash=bars.take(8)+listOf(MarketIndexBar(days[8],17499.0,-1000.99),MarketIndexBar(days[9],18000.0,501.0))
        val decision=MarketCrashGuard.timeline(afterCrash).getValue(days[9])
        assertEquals(days[8],decision.trigger);assertEquals("PAUSED",decision.state)
    }
    @Test fun officialParserAndExchangeHolidayCalendar(){
        val raw=JSONObject().put("stat","OK").put("fields",JSONArray(listOf("日期","成交股數","成交金額","成交筆數","發行量加權股價指數","漲跌點數")))
            .put("data",JSONArray(listOf(listOf("114/04/07","0","0","0","19,232.35","-2,065.87"),listOf("114/04/08","0","0","0","18,459.95","-772.40"))))
        val bars=MarketIndexData.parse(raw.toString(),YearMonth.of(2025,4))
        assertEquals(LocalDate.of(2025,4,7),bars[0].date);assertEquals(-2065.87,bars[0].change,0.0001)
        assertEquals("PAUSED",MarketCrashGuard.timeline(bars).values.last().state)
        assertTrue(runCatching{MarketIndexData.parse(raw.toString(),YearMonth.of(2025,5))}.isFailure)
        assertTrue(runCatching{MarketIndexData.parse(raw.put("stat","查無資料").toString(),YearMonth.of(2025,4))}.isFailure)
        val calendar=JSONArray().put(JSONObject().put("Date","1151009").put("Name","國慶日"))
            .put(JSONObject().put("Date","1151008").put("Name","測試最後交易日"))
        assertEquals(LocalDate.of(2026,10,8),MarketIndexData.previousSession(LocalDate.of(2026,10,12),calendar))
        assertTrue(runCatching{MarketIndexData.previousSession(LocalDate.of(2027,1,4),calendar)}.isFailure)
    }
    @Test fun backtestBlocksEntriesAndStillSellsExistingLots(){
        val bars=market();val days=bars.drop(7).take(3).map{it.date};val series=stock(days)
        val settings=BtSettings(days.first(),days.last(),targetNetPct=3.0,marketGuardVersion=1)
        val guards=MarketCrashGuard.forDays(bars,days)
        val prepared=BtPrepared(listOf(series),days.associateWith{listOf(BtSignal("2330","A"))},days,marketGuards=guards)
        val run=BacktestEngine.run(prepared,settings)
        assertEquals(1,run.buys);assertEquals(1,run.closed.size)
        assertEquals(days[1],run.closed.first().date)
        assertEquals(2,run.skipped.size);assertTrue(run.skipped.all{it.reason.contains("大盤保護")})
        assertEquals("PAUSED",run.curve[1].marketGuard!!.state)
        val saved=BacktestStore(app).runJson(run).getJSONArray("curve").getJSONObject(1).getJSONObject("marketGuard")
        assertEquals(bars[7].date.toString(),saved.getString("trigger"))
        assertTrue(runCatching{BacktestEngine.run(prepared.copy(marketGuards=emptyMap()),settings)}.isFailure)
        // Legacy archives can still be decoded and inspected with their original semantics.
        assertEquals(0,BtSettingsCodec.decode(BtSettingsCodec.encode(settings).apply{remove("marketGuardVersion")}).marketGuardVersion)
        assertEquals(1,BtSettingsCodec.capture(app,days.first(),days.last(),3000000.0,"").marketGuardVersion)
    }
    @Test fun paperExecutionCannotBypassGuardWithOldCandidatesOrPriceOverrides(){
        val day=LocalDate.of(2026,9,28);val now=stamp(day,10,0)
        val candidate=PaperCandidate("3661","候選股","tse","C",100,now-60000)
        val quote=PaperQuote("3661",now,100.0,99.5,100.5,10.0,10.0,10000,110.0,90.0)
        for(guard in listOf(MarketCrashGuard.unknown("資料不足"),MarketGuardDecision("PAUSED",reason="暴跌保護"))){
            val book=PaperBook(enabled=true).apply{candidates+=candidate;positions+=PaperPosition("2330","原持股","tse","A",100.0,100143.0,now-86400000,100.0,100.0,now-1000)}
            val sell=PaperQuote("2330",now,96.0,96.0,96.5,10.0,10.0,10000,110.0,90.0)
            val policy=StockPolicy(ceiling=50.0,overrides=mapOf(StockScope.SCANNER to setOf("3661")))
            PaperEngine.step(book,mapOf("3661" to quote,"2330" to sell),now,policy,marketGuard=guard)
            assertEquals(listOf("SELL"),book.trades.map{it.side});assertTrue(book.status.contains("暫停買入"))
            PaperEngine.step(book,mapOf("3661" to quote),now+1000,policy,marketGuard=MarketGuardDecision("NORMAL"))
            assertEquals(1,book.trades.count{it.side=="BUY"})
        }
    }
    @Test fun cacheExpiresAndDoesNotCarryPermissionIntoANewDay(){
        val day=LocalDate.of(2026,10,8);val now=stamp(day,10,0)
        app.getSharedPreferences("market-crash-guard-v1",0).edit().putString("day",day.toString()).putLong("checked",now)
            .putString("decision",MarketGuardDecision("NORMAL").json().toString()).commit()
        val data=MarketIndexData(app)
        assertTrue(data.cached(now+1000).canBuy);assertFalse(data.cached(now+300001).canBuy);assertFalse(data.cached(now+86400000).canBuy)
    }
    @Test fun robotSnapshotRoundTripPreservesTheSameIndexAndGuardDecisions(){
        val bars=market();val series=stock(bars.map{it.date});val data=BacktestData.Loaded(listOf(series),1,emptyList(),bars)
        val file=File(app.cacheDir,"guard-robot.bin.gz");BtRobotDataset.write(file,data)
        val loaded=BtRobotDataset.read(file)
        assertEquals(bars,loaded.market)
        val days=bars.drop(5).map{it.date}
        assertEquals(MarketCrashGuard.forDays(bars,days),MarketCrashGuard.forDays(loaded.market,days))
        val settings=BtRobotSpace.settings().copy(marketGuardVersion=1)
        val store=BtRobotStore(app);val id=store.create(settings);val token=store.resume(id)
        BtRobotDataset.write(store.dataset(id),data);store.coverage(id,token,1,1,BtRobotCheckpoint.digest(store.dataset(id)));store.pause(id)
        val archive=File(app.cacheDir,"guard-checkpoint.zip");BtRobotCheckpoint.export(app,id,archive)
        val restored=archive.inputStream().use{BtRobotCheckpoint.restore(app,it)}
        assertEquals(1,BtSettingsCodec.decode(store.session(restored)!!.getJSONObject("settings")).marketGuardVersion)
        assertEquals(bars,BtRobotDataset.read(store.dataset(restored)).market)
    }
    @Test fun guardChangesAppearInJournalAndPhoneViewsWrap(){
        val day=LocalDate.of(2026,10,1);val old=BtSettings(day,day);val latest=old.copy(marketGuardVersion=1)
        assertTrue(BacktestSettingsDiff.compare(BtSettingsCodec.encode(latest),BtSettingsCodec.encode(old)).changes.any{it.text.contains("大盤暴跌保護")})
        val ctl=Robolectric.buildActivity(BacktestJournalActivity::class.java).create().start();val a=ctl.get()
        for(scale in listOf(1f,1.3f)){
            RuntimeEnvironment.setFontScale(scale)
            val panel=MarketGuardPanel(a);capture(panel,"market-guard-rules-$scale")
            val paused=MarketCrashGuard.timeline(market()).values.first{it.state=="PAUSED"}
            val card=BacktestDailyCard(a,JSONObject().put("date",day.toString()).put("marketGuard",paused.json()).put("selected",3).put("buys",0).put("sells",1).put("skipped",3))
            capture(card,"market-guard-day-$scale")
        }
        ctl.stop().destroy()
    }
    private fun capture(view:View,name:String){
        val width=NeonUi.dp(view.context,328);view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));view.layout(0,0,width,view.measuredHeight)
        fun walk(v:View){v.jumpDrawablesToCurrentState();if(v is TextView&&v.layout!=null)assertTrue("Clipped ${v.text}",v.layout.height<=v.height-v.compoundPaddingTop-v.compoundPaddingBottom+2);if(v is ViewGroup)(0 until v.childCount).forEach{walk(v.getChildAt(it))}}
        walk(view);val bmp=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bmp))
        val file=File("build/ui-previews/$name.png");file.parentFile.mkdirs();file.outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)};bmp.recycle()
    }
}
