package com.rex.twboardingscanner.backtest

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.data.*
import com.rex.twboardingscanner.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StockPolicyTest{
    private val app get()=RuntimeEnvironment.getApplication()
    private val day=LocalDate.of(2026,8,3)
    @Before fun clear(){listOf("stock-entry-policy","backtest_robot","backtest_status","paper_trading_v1").forEach{app.getSharedPreferences(it,0).edit().clear().commit()}}
    private fun bar(d:LocalDate,p:Double,high:Double=p)=DailyBar(d.atTime(13,30).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),p,high,p,p,1000000)
    private fun prepared()=BtPrepared(listOf(BtSeries("2330","測試半導體",Market.TWSE,listOf(bar(day,100.0),bar(day.plusDays(1),110.0))),BtSeries("2317","測試傳產",Market.TWSE,listOf(bar(day,200.0),bar(day.plusDays(1),200.0)))),mapOf(day to listOf(BtSignal("2330","A"),BtSignal("2317","B")),day.plusDays(1) to listOf(BtSignal("2330","A"))),listOf(day,day.plusDays(1)))
    @Test fun banWinsOverEveryOverrideAndEqualityIsAllowed(){
        val p=StockPolicy(setOf("2330"),100.0,StockScope.entries.associateWith{setOf("2330","2317")})
        StockScope.entries.forEach{assertEquals(StockDecision.BANNED,p.decision("2330",80.0,it));assertEquals(StockDecision.ALLOW,p.decision("2317",200.0,it))}
        assertEquals(StockDecision.ALLOW,p.decision("2303",100.0,StockScope.SCANNER))
        assertEquals(StockDecision.LIMITED,p.decision("2303",100.1,StockScope.SCANNER))
        assertEquals(p,StockPolicy.read(p.json()))
        assertTrue(runCatching{StockPolicy(ceiling=Double.NaN).validate()}.isFailure)
        assertTrue(runCatching{StockPolicy(setOf("bad")).validate()}.isFailure)
    }
    @Test fun historicalDayPriceGatesEntriesButNeverBlocksExit(){
        val s=BtSettings(day,day.plusDays(1),stockPolicy=StockPolicy(ceiling=100.0))
        val run=BacktestEngine.run(prepared(),s)
        assertEquals(1,run.buys);assertEquals(1,run.closed.size);assertEquals(listOf(1,0),run.curve.map{it.selected})
        assertEquals(listOf("2317","2330"),run.limited.map{it.code})
        assertEquals(listOf(day,day.plusDays(1)),run.limited.map{it.date})
        assertTrue(run.closed.first().pnl>0)
        assertEquals(s,BtSettingsCodec.decode(BtSettingsCodec.encode(s)))
        val json=BacktestStore(app).runJson(run);assertEquals(2,json.getJSONArray("limited").length())
    }
    @Test fun overridesAreScopedAndBannedStocksNeverGetBuyOrders(){
        val policy=StockPolicy(setOf("2330"),100.0,mapOf(StockScope.ROBOT to setOf("2317")))
        val s=BtSettings(day,day.plusDays(1),stockPolicy=policy)
        val normal=BacktestEngine.run(prepared(),s);assertEquals(0,normal.buys);assertTrue(normal.curve.all{it.selected==0})
        val robot=BacktestEngine.run(prepared(),s.copy(stockScope=StockScope.ROBOT));assertEquals(listOf("2317"),robot.trades.filter{it.side=="BUY"}.map{it.code})
        assertTrue(robot.skipped.any{it.code=="2330"&&it.reason.contains("禁股")})
    }
    @Test fun savingRestrictionsPersistsAndFreezesRunningJobs(){
        val bt=BacktestStore(app);val id=java.util.UUID.randomUUID().toString();bt.begin(id,BtSettings(day,day.plusDays(1)))
        val robot=BtRobotStore(app);val batch=robot.create(BtRobotSpace.settings());val token=robot.resume(batch)
        StockPolicyStore(app).ban("2330","台積電")
        assertEquals(setOf("2330"),StockPolicyStore(app).read().banned)
        assertEquals("台積電",StockPolicyStore(app).name("2330"))
        assertEquals("CANCELED",bt.state());assertFalse(robot.current(batch,token));assertEquals("PAUSED",robot.session(batch)!!.getString("state"))
        assertTrue(BtSettingsCodec.decode(robot.session(batch)!!.getJSONObject("settings")).stockPolicy.banned.isEmpty())
    }
    @Test fun restrictedCheckpointKeepsPolicyAndRejectsDifferentTrialPolicy(){
        val p=StockPolicy(setOf("2330"),150.0,mapOf(StockScope.ROBOT to setOf("2317")))
        StockPolicyStore(app).change{p}
        val store=BtRobotStore(app);val s=BtRobotSpace.settings().copy(stockPolicy=p,stockScope=StockScope.ROBOT)
        val id=store.create(s);val token=store.resume(id)
        val c=store.next(id)!!
        assertTrue(runCatching{store.commit(id,token,c,BtResult(s.copy(rules=BtRobotSpace.rules(c.key),stockPolicy=StockPolicy()),BtRun(s.capital),0,0,emptyList(),"fixture"),0)}.isFailure)
        val file=File(app.cacheDir,"policy-checkpoint.zip");BtRobotCheckpoint.export(app,id,file)
        ZipFile(file).use{assertEquals(3,JSONObject(it.getInputStream(it.getEntry("manifest.json")).bufferedReader().readText()).getInt("version"))}
        val restored=file.inputStream().use{BtRobotCheckpoint.restore(app,it)}
        assertEquals(s,BtSettingsCodec.decode(store.session(restored)!!.getJSONObject("settings")))
    }
    @Test fun compactHomeAndRestrictionScreensRender(){
        StockDirectory(app).remember(listOf(MarketStock("2330","測試半導體",Market.TWSE,StockSector.UNKNOWN,100.0,102.0,99.0,101.0,1.0,12000,null,null)))
        val ctl=Robolectric.buildActivity(StockToolsActivity::class.java,Intent(app,StockToolsActivity::class.java).putExtra("mode","BAN")).setup();val a=ctl.get()
        val input=a.window.decorView.findViewWithTag<EditText>("stock-code-input");input.setText("2330")
        assertNotNull(a.window.decorView.findViewWithTag<View>("add-ban"));capture(a.window.decorView,"stock-ban-360.png")
        a.window.decorView.findViewWithTag<View>("add-ban").performClick();assertTrue("2330" in StockPolicyStore(app).read().banned)
        ctl.pause().stop().destroy()
        val policy=StockPolicyStore(app);policy.change{it.copy(ceiling=150.0)}
        policy.candidates(StockScope.SCANNER,JSONArray().put(JSONObject().put("date","2026-10-08").put("code","2317").put("name","示範公司").put("price",212.5).put("radar","A/C")),"畫面驗證示範 · 非真實行情",policy.read())
        val limits=Robolectric.buildActivity(StockToolsActivity::class.java,Intent(app,StockToolsActivity::class.java).putExtra("mode","LIMIT")).setup();capture(limits.get().window.decorView,"stock-limit-360.png");limits.pause().stop().destroy()
        val home=Robolectric.buildActivity(MainActivity::class.java).setup();val h=home.get();val text=h.findViewById<TextView>(com.rex.twboardingscanner.R.id.versionText).text.toString();assertTrue(text.contains("雷允澤"));assertTrue(text.contains("0.4.30"));capture(h.window.decorView,"home-policy-360.png");home.pause().stop().destroy()
    }
    private fun capture(v:View,name:String){v.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1600,View.MeasureSpec.EXACTLY));v.layout(0,0,720,1600);val b=Bitmap.createBitmap(720,1600,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));val f=File("build/ui-previews/$name");f.parentFile!!.mkdirs();f.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
}
