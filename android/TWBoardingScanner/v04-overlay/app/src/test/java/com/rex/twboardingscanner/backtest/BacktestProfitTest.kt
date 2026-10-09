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
class BacktestProfitTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun clean(){
        File(app.filesDir,"backtests").deleteRecursively();File(app.filesDir,"backtest-journal").deleteRecursively()
        listOf("backtest_status","scanner_filters").forEach{app.getSharedPreferences(it,0).edit().clear().commit()}
    }
    private fun fills(count:Int):JSONArray {
        val raw=JSONArray()
        repeat(count){i->
            val buy=100.0+i;val sell=buy+if(i%2==0)4.0 else -4.0
            val cost=buy*1000+PaperEngine.fee(buy*1000)
            fun fill(side:String,price:Double)=JSONObject().put("code","2330").put("name","台積電").put("radar","A").put("lotId","lot-$i").put("shares",1000)
                .put("side",side).put("date",if(side=="BUY")"2025-08-01" else "2025-08-08").put("time",if(side=="BUY")"13:30" else "09:00–13:30")
                .put("timeKind","盤中觸價，確切時間未知").put("price",price).put("fee",PaperEngine.fee(price*1000)).put("tax",if(side=="BUY")0 else PaperEngine.tax(price*1000))
                .put("pnl",if(side=="BUY")0.0 else PaperEngine.netSell(sell)-cost).put("previousClose",buy+2).put("dayClose",buy+10)
            raw.put(fill("BUY",buy));raw.put(fill("SELL",sell))
        }
        return raw
    }
    private fun holdings(count:Int)=JSONArray((0 until count).map{i->
        val entry=100.0+i;val mark=entry+if(i%2==0)-5.0 else 5.0
        val cost=entry*1000+PaperEngine.fee(entry*1000)
        JSONObject().put("lotId","held-$i").put("code","3661").put("name","世芯-KY").put("radar","B").put("shares",1000)
            .put("entryDate","2025-08-04").put("entryTime","13:30（收盤模型）").put("entry",entry).put("cost",cost)
            .put("mark",mark).put("markDate","2025-08-07").put("unrealized",PaperEngine.netSell(mark)-cost)
    })
    private fun find(v:View,p:(View)->Boolean):View?{if(p(v))return v;if(v is ViewGroup)for(i in 0 until v.childCount){find(v.getChildAt(i),p)?.let{return it}};return null}
    private fun tagged(v:View,tag:String)=find(v){it.tag==tag}
    private fun text(v:View,part:String)=find(v){it is TextView&&it.text.contains(part)}
    private fun cards(v:View):Int=(if(v is BacktestProfitCard)1 else 0)+(if(v is ViewGroup)(0 until v.childCount).sumOf{cards(v.getChildAt(it))}else 0)
    @Test fun saleChangeUsesSalePriceAndHoldingsUseCutoffVersusEntryWithoutMutation(){
        val raw=fills(2);val before=raw.toString();val trades=BacktestTradeLedger.rows(raw)
        val sell=trades.getJSONObject(1)
        assertEquals(2.0,sell.getDouble("sellChange"),.001);assertEquals(2.0/102*100,sell.getDouble("sellChangePct"),.000001)
        assertEquals(8.0,sell.getDouble("dayChange"),.001);assertEquals(4.0,sell.getDouble("tradePriceChange"),.001)
        assertEquals(100.0,sell.getDouble("buyPrice"),.001);assertEquals(101.0,trades.getJSONObject(3).getDouble("buyPrice"),.001)
        assertEquals(before,raw.toString())
        val h=holdings(2);val original=h.toString();val held=BacktestHoldingLedger.rows(h)
        assertEquals(-5.0,held.getJSONObject(0).getDouble("holdingChange"),.001);assertEquals(-5.0,held.getJSONObject(0).getDouble("holdingChangePct"),.001)
        assertEquals(5.0/101*100,held.getJSONObject(1).getDouble("holdingChangePct"),.000001)
        assertEquals(h.getJSONObject(0).getDouble("unrealized"),held.getJSONObject(0).getDouble("unrealized"),0.0)
        assertEquals(original,h.toString());assertEquals(held.toString(),BacktestHoldingLedger.rows(held).toString())
        assertEquals(BacktestHoldingLedger.csvFields.size,BacktestHoldingLedger.csvHeader.split(',').size)
        assertEquals(BacktestTradeLedger.csvFields.size,BacktestTradeLedger.csvHeader.split(',').size)
        raw.getJSONObject(1).remove("previousClose");assertTrue(BacktestTradeLedger.rows(raw).getJSONObject(1).isNull("sellChangePct"))
        h.getJSONObject(0).remove("entry");h.getJSONObject(0).remove("unrealized")
        val missing=BacktestHoldingLedger.rows(h).getJSONObject(0);assertTrue(missing.isNull("holdingChangePct"));assertTrue(missing.isNull("unrealizedPct"))
    }
    @Test fun all94SalesAnd35HoldingsAreReachableAndSwitchingDoesNotDuplicateRows(){
        val ctl=Robolectric.buildActivity(BacktestActivity::class.java).setup();val a=ctl.get()
        val trades=BacktestTradeLedger.rows(fills(94));val held=holdings(35)
        val realized=(0 until trades.length()).sumOf{trades.getJSONObject(it).optDouble("netProfit",0.0).takeIf{v->v.isFinite()}?:0.0}
        val unrealized=(0 until held.length()).sumOf{held.getJSONObject(it).getDouble("unrealized")}
        val panel=BacktestProfitPanel(a,trades,held,realized,unrealized,94,"2025-08-08")
        assertEquals(0,cards(panel));tagged(panel,"realized-profit-toggle")!!.performClick();assertEquals(20,cards(panel))
        repeat(4){tagged(panel,"profit-load-more")!!.performClick()}
        assertEquals(94,cards(panel));assertNull(tagged(panel,"profit-load-more"));assertNotNull(text(panel,"已顯示全部 94 筆"))
        assertNotNull(text(panel,"−6.00 元")) // A losing sale is included, not filtered out.
        tagged(panel,"unrealized-profit-toggle")!!.performClick();assertEquals(20,cards(panel))
        tagged(panel,"profit-load-more")!!.performClick();assertEquals(35,cards(panel));assertNotNull(text(panel,"已顯示全部 35 筆"))
        assertNotNull(text(panel,"沿用上列日期價格"));assertNotNull(text(panel,"+5.00 元")) // Profitable holdings also remain in the list.
        tagged(panel,"profit-collapse")!!.performClick();assertEquals(0,cards(panel))
        tagged(panel,"realized-profit-toggle")!!.performClick();assertEquals(20,cards(panel))
        tagged(panel,"realized-profit-toggle")!!.performClick();assertEquals(0,cards(panel))
        ctl.pause().stop().destroy()
    }
    @Test fun phonePanelsShowFullFieldsAndEmptyAndMissingRecordsClearly(){
        val ctl=Robolectric.buildActivity(BacktestActivity::class.java).setup();val a=ctl.get()
        val trades=BacktestTradeLedger.rows(fills(1));val held=holdings(1)
        val pnl=trades.getJSONObject(1).getDouble("netProfit");val loss=held.getJSONObject(0).getDouble("unrealized")
        val panel=BacktestProfitPanel(a,trades,held,pnl,loss,1,"2025-08-08")
        tagged(panel,"realized-profit-toggle")!!.performClick()
        assertNotNull(text(panel,"買入成交價"));assertNotNull(text(panel,"賣出成交價"));assertNotNull(text(panel,"+1.96%"));assertNotNull(text(panel,"淨報酬率"))
        capture(panel,"backtest-realized-list-360")
        tagged(panel,"unrealized-profit-toggle")!!.performClick()
        assertNotNull(text(panel,"截止日現價"));assertNotNull(text(panel,"−5.00%"));assertNotNull(text(panel,"世芯-KY"))
        capture(panel,"backtest-unrealized-list-360")
        val empty=BacktestProfitPanel(a,JSONArray(),JSONArray(),0.0,0.0,0,"2025-08-08")
        tagged(empty,"realized-profit-toggle")!!.performClick();assertNotNull(text(empty,"尚無已賣出交易"))
        tagged(empty,"unrealized-profit-toggle")!!.performClick();assertNotNull(text(empty,"沒有留倉"))
        val incomplete=BacktestProfitCard(a,JSONObject().put("code","2330").put("name","台積電"),false,"2025-08-08")
        assertNotNull(text(incomplete,"缺少損益資料"));assertNotNull(text(incomplete,"—%"))
        ctl.pause().stop().destroy()
    }
    @Test fun existingJournalResultUsesExpandableTilesAndExportLeavesArchiveUntouched(){
        val start=LocalDate.of(2025,8,1);val end=start.plusDays(3)
        fun series(code:String,sells:Boolean)=BtSeries(code,"測試$code",Market.TWSE,listOf(start,end).map{d->val price=if(sells&&d==end)108.0 else 100.0;DailyBar(d.atTime(9,0).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),price,price,price,price,1000000)})
        val p=BtPrepared(listOf(series("2330",true),series("3661",false)),mapOf(start to listOf(BtSignal("2330","A"),BtSignal("3661","B"))),listOf(start,end))
        val s=BtSettings(start,end);val run=BacktestEngine.run(p,s);val store=BacktestStore(app)
        store.begin("archived",s);assertTrue(store.save("archived",BtResult(s,run,2,2,emptyList(),"保存的回測")))
        val file=File(app.filesDir,"backtests/archived.json");val original=file.readBytes();val report=store.log("archived")!!
        val ctl=Robolectric.buildActivity(BacktestActivity::class.java).setup();ctl.get().showResult(report)
        val decor=ctl.get().window.decorView
        tagged(decor,"realized-profit-toggle")!!.performClick();assertEquals(1,cards(decor));assertNotNull(text(decor,"測試2330"))
        tagged(decor,"unrealized-profit-toggle")!!.performClick();assertEquals(1,cards(decor));assertNotNull(text(decor,"截止日現價"))
        val enriched=BacktestDailyLedger.enrich(report)
        assertEquals(run.realized,enriched.getJSONObject("run").getDouble("realized"),0.0)
        assertEquals(run.unrealized,enriched.getJSONObject("run").getDouble("unrealized"),0.0)
        assertTrue(enriched.getJSONObject("run").getJSONArray("holdings").getJSONObject(0).has("holdingChangePct"))
        assertArrayEquals(original,file.readBytes());ctl.pause().stop().destroy()
    }
    private fun capture(v:View,name:String){
        v.setPadding(24,20,24,20);v.setBackgroundColor(android.graphics.Color.rgb(4,17,30))
        v.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1800,View.MeasureSpec.AT_MOST));v.layout(0,0,720,v.measuredHeight)
        fun check(w:View){if(w is TextView&&w.layout!=null&&w.text.isNotEmpty())assertTrue("clipped: ${w.text}",w.layout.height<=w.height-w.compoundPaddingTop-w.compoundPaddingBottom+2);w.jumpDrawablesToCurrentState();if(w is ViewGroup)for(i in 0 until w.childCount)check(w.getChildAt(i))}
        check(v);val bitmap=Bitmap.createBitmap(720,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(bitmap))
        val file=File("build/ui-previews/$name.png");file.parentFile.mkdirs();file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
}
