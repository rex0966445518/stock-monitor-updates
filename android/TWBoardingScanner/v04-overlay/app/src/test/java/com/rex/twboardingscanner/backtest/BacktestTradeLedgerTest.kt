package com.rex.twboardingscanner.backtest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.paper.PaperEngine
import com.rex.twboardingscanner.ui.*
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
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BacktestTradeLedgerTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun clean(){File(app.filesDir,"backtests").deleteRecursively();File(app.filesDir,"backtest-journal").deleteRecursively();app.getSharedPreferences("backtest_status",0).edit().clear().commit();app.getSharedPreferences("scanner_filters",0).edit().clear().commit()}
    private fun fill(side:String,date:String,lot:String,price:Double,buyPrice:Double=price):JSONObject {
        val fee=PaperEngine.fee(price*1000);val tax=if(side=="SELL")PaperEngine.tax(price*1000)else 0.0
        val pnl=if(side=="SELL")price*1000-fee-tax-buyPrice*1000-PaperEngine.fee(buyPrice*1000)else 0.0
        return JSONObject().put("side",side).put("date",date).put("lotId",lot).put("code","2330").put("name","台積電").put("radar","A").put("shares",1000).put("price",price).put("fee",fee).put("tax",tax).put("pnl",pnl).put("time",if(side=="BUY")"13:30" else "09:00–13:30").put("timeKind","模型時段，非逐筆時間").put("dayClose",105.0).put("previousClose",102.0).put("reason",if(side=="BUY")"當日入選，收盤各買一張" else "持有超過 5 個日曆日，扣費稅後保本出場")
    }
    @Test fun repeatedStockSalesUseExactLotAndKeepDailyChangeSeparateFromReturn(){
        val raw=JSONArray().put(fill("BUY","2025-08-01","first",100.0)).put(fill("BUY","2025-08-04","second",90.0)).put(fill("SELL","2025-08-07","second",94.0,90.0)).put(fill("SELL","2025-08-08","first",104.0,100.0))
        val before=raw.toString();val enriched=BacktestTradeLedger.rows(raw);assertEquals(before,raw.toString())
        val sale=enriched.getJSONObject(2);val cost=90000.0+PaperEngine.fee(90000.0)
        assertEquals(90.0,sale.getDouble("buyPrice"),.001);assertEquals(cost,sale.getDouble("buyCost"),.001);assertEquals(cost/1000,sale.getDouble("costPerShare"),.000001)
        assertEquals("2025-08-04",sale.getString("buyDate"));assertEquals("13:30",sale.getString("buyTime"))
        assertEquals(raw.getJSONObject(2).getDouble("pnl"),sale.getDouble("netProfit"),.001)
        assertEquals(sale.getDouble("netProfit")/cost*100,sale.getDouble("netProfitPct"),.000001)
        assertEquals(3.0,sale.getDouble("dayChange"),.001);assertEquals(3.0/102*100,sale.getDouble("dayChangePct"),.000001)
        assertEquals(4.0,sale.getDouble("tradePriceChange"),.001);assertEquals(4.0/90*100,sale.getDouble("tradePriceChangePct"),.000001)
        assertTrue(enriched.getJSONObject(0).isNull("sellPrice"));assertTrue(enriched.getJSONObject(0).isNull("netProfit"))
        assertEquals(100.0,enriched.getJSONObject(3).getDouble("buyPrice"),.001)
        assertEquals(enriched.toString(),BacktestTradeLedger.rows(enriched).toString())
        assertEquals(4,BacktestTradeLedger.byDate(enriched).values.sumOf{it.size})
    }
    @Test fun ambiguousLegacyLotsRecoverOnlyProvableCostWithoutInventingBuyTime(){
        val a=fill("BUY","2025-08-01","",100.0);val b=fill("BUY","2025-08-04","",90.0);val sale=fill("SELL","2025-08-07","",94.0,90.0)
        val rows=BacktestTradeLedger.rows(JSONArray().put(a).put(b).put(sale));val row=rows.getJSONObject(2)
        assertEquals(90000.0+PaperEngine.fee(90000.0),row.getDouble("buyCost"),.001)
        assertTrue(row.isNull("buyPrice"));assertTrue(row.isNull("buyDate"));assertTrue(row.isNull("buyTime"));assertTrue(row.isNull("tradePriceChange"))
        assertTrue(row.getString("costSource").contains("反推"))
        val unique=BacktestTradeLedger.rows(JSONArray().put(b).put(sale)).getJSONObject(1)
        assertEquals(90.0,unique.getDouble("buyPrice"),.001)
        sale.remove("fee");sale.remove("dayClose");sale.remove("previousClose")
        val missing=BacktestTradeLedger.rows(JSONArray().put(sale)).getJSONObject(0)
        assertTrue(missing.isNull("buyCost"));assertTrue(missing.isNull("dayChange"));assertTrue(missing.isNull("dayChangePct"))
    }
    @Test fun historicalQuotesPersistAndMissingPriorCloseDoesNotBecomeZero(){
        val first=LocalDate.of(2025,8,1);val last=first.plusDays(3)
        fun bar(day:LocalDate,close:Double)=DailyBar(day.atTime(9,0).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),close,close+1,close-1,close,1000000)
        val series=BtSeries("2330","台積電",Market.TWSE,listOf(bar(first,100.0),bar(last,108.0)))
        val s=BtSettings(first,last);val run=BacktestEngine.run(BtPrepared(listOf(series),mapOf(first to listOf(BtSignal("2330","A"))),listOf(first,last)),s)
        val store=BacktestStore(app);store.begin("quotes",s);assertTrue(store.save("quotes",BtResult(s,run,1,1,emptyList(),"测试")))
        val report=store.result()!!;val rows=report.getJSONObject("run").getJSONArray("trades")
        assertTrue(rows.getJSONObject(0).isNull("dayChange"));assertEquals(8.0,rows.getJSONObject(1).getDouble("dayChange"),.001);assertEquals(8.0,rows.getJSONObject(1).getDouble("dayChangePct"),.001)
        assertEquals(run.closed.single().pnl,rows.getJSONObject(1).getDouble("netProfit"),.001)
        val original=report.toString();val exported=BacktestDailyLedger.enrich(report)
        assertEquals(original,report.toString());assertEquals(run.profit,exported.getJSONObject("run").getDouble("profit"),.001)
        assertTrue(BacktestTradeLedger.csvFields.containsAll(listOf("buyPrice","costPerShare","sellPrice","netProfit","dayChange","dayChangePct")))
        assertEquals(BacktestTradeLedger.csvFields.size,BacktestTradeLedger.csvHeader.split(',').size)
    }
    private fun find(v:View,starts:String):View?{if(v is TextView&&v.text.toString().startsWith(starts))return v;if(v is ViewGroup)for(i in 0 until v.childCount){find(v.getChildAt(i),starts)?.let{return it}};return null}
    private fun countCards(v:View):Int=(if(v is BacktestTradeCard)1 else 0)+(if(v is ViewGroup)(0 until v.childCount).sumOf{countCards(v.getChildAt(it))}else 0)
    @Test fun dailyBuySellButtonsShowAllFillsAndRemainReadableOnSmallPhone(){
        val ctl=Robolectric.buildActivity(BacktestActivity::class.java).setup();val a=ctl.get()
        val raw=JSONArray().put(fill("BUY","2025-08-01","original",100.0)).put(fill("SELL","2025-08-08","original",104.0,100.0))
        repeat(11){raw.put(fill("BUY","2025-08-08","new-$it",105.0).put("code","${3000+it}").put("name","測試公司$it"))}
        val trades=BacktestTradeLedger.byDate(BacktestTradeLedger.rows(raw)).getValue("2025-08-08")
        val row=JSONObject().put("date","2025-08-08").put("selected",11).put("buys",11).put("sells",1).put("skipped",0).put("realized",raw.getJSONObject(1).getDouble("pnl")).put("holdingCost",1155000.0).put("holdingValue",1150000.0).put("holdingLots",11).put("dayProfit",-1234.0)
        val card=BacktestDailyCard(a,row,trades);val root=NeonUi.vertical(a).apply{setPadding(24,20,24,20);setBackgroundColor(android.graphics.Color.rgb(4,17,30));addView(card)}
        assertEquals(0,countCards(card));find(card,"賣出 1 筆")!!.performClick();assertEquals(1,countCards(card))
        assertNotNull(find(card,"賣出  2330  台積電"));assertNotNull(find(card,"日漲跌 +3.00 元"));capture(root,"backtest-daily-sell-details-360")
        find(card,"買入 11 筆")!!.performClick();assertEquals(10,countCards(card));find(card,"載入更多明細")!!.performClick();assertEquals(11,countCards(card))
        find(card,"買入 11 筆")!!.performClick();assertEquals(0,countCards(card))
        val buy=trades.first{it.getString("side")=="BUY"};val single=NeonUi.vertical(a).apply{setPadding(24,20,24,20);setBackgroundColor(android.graphics.Color.rgb(4,17,30));addView(BacktestTradeCard(a,buy))}
        assertNotNull(find(single,"本筆淨利  尚未實現"));capture(single,"backtest-buy-details-360")
        ctl.pause().stop().destroy()
    }
    private fun capture(v:View,name:String){
        v.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1800,View.MeasureSpec.EXACTLY));v.layout(0,0,720,1800)
        fun check(w:View){if(w is TextView&&w.layout!=null&&w.text.isNotEmpty())assertTrue("clipped ${w.text}",w.layout.height<=w.height-w.compoundPaddingTop-w.compoundPaddingBottom+2);if(w is ViewGroup)for(i in 0 until w.childCount)check(w.getChildAt(i))}
        check(v);val bitmap=Bitmap.createBitmap(720,1800,Bitmap.Config.ARGB_8888);v.draw(Canvas(bitmap));val f=File("build/ui-previews/$name.png");f.parentFile.mkdirs();f.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
}
