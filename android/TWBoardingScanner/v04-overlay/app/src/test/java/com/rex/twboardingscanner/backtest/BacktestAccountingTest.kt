package com.rex.twboardingscanner.backtest

import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.paper.PaperEngine
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class BacktestAccountingTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val d=LocalDate.of(2025,1,2)
    private fun bar(day:LocalDate,close:Double=100.0,high:Double=close,open:Double=close)=DailyBar(day.atTime(9,0).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),open,high,minOf(open,close)-1,close,10000000)
    @Before fun clean(){File(app.filesDir,"backtests").deleteRecursively();File(app.filesDir,"backtest-journal").deleteRecursively();app.getSharedPreferences("backtest_status",0).edit().clear().commit()}
    private fun fixture(dividend:Double=0.0,version:Int=3):Pair<BtSettings,BtRun>{
        val dates=listOf(d,d.plusDays(1),d.plusDays(4),d.plusDays(6))
        val a=BtSeries("1234","賣出股",Market.TWSE,dates.mapIndexed{i,day->if(i==1)bar(day,108.0)else bar(day)})
        val b=BtSeries("5678","留倉股",Market.TWSE,listOf(bar(d),bar(dates[1],95.0),bar(dates[3],90.0)),mapOf(dates[1] to dividend))
        val settings=BtSettings(d,dates.last(),strategyVersion=version)
        return settings to BacktestEngine.run(BtPrepared(listOf(a,b),mapOf(d to listOf(BtSignal("1234","A"),BtSignal("5678","B"))),dates),settings)
    }
    private fun report(s:BtSettings,r:BtRun,id:String="accounting"):JSONObject{
        val store=BacktestStore(app);store.begin(id,s);assertTrue(store.save(id,BtResult(s,r,2,2,emptyList(),"測試")));return store.result()!!
    }
    @Test fun dailyAmountsReconcileAfterSaleDividendAndMissingMark(){
        val (s,r)=fixture(2.0);val first=r.curve.first();val sale=r.closed.single()
        assertEquals(0.0,first.realized!!,.001);assertEquals(2,first.holdingLots!!)
        assertEquals(r.trades.filter{it.side=="BUY"}.sumOf{it.price*1000+it.fee},first.holdingCost!!,.001)
        assertEquals(sale.pnl,r.curve[1].realized!!,.001)
        assertEquals(r.holdings.single().cost,r.curve[1].holdingCost!!,.001)
        assertEquals(1,r.curve[2].staleLots!!);assertEquals(0,r.curve.last().staleLots!!)
        r.curve.forEach{assertEquals(it.equity,it.cash!!+it.holdingValue!!+it.dividendAccrued!!+it.rebateAccrued!!,.001)}
        assertEquals(r.profit,r.curve.sumOf{it.dayProfit!!},.001)
        assertEquals(r.profit,r.realized+r.unrealized+r.dividendAccrued+r.rebateAccrued,.001)
        val saved=report(s,r);val rows=BacktestDailyLedger.rows(saved)
        assertEquals(r.curve[1].holdingCost!!,rows.getJSONObject(1).getDouble("holdingCost"),.001)
        assertEquals(r.rebateAccrued,saved.getJSONObject("run").getDouble("rebateAccrued"),.001)
        val t=saved.getJSONObject("run").getJSONArray("trades").getJSONObject(0)
        assertEquals(t.getDouble("price")*1000,t.getDouble("turnover"),.001);assertEquals("2025-01",t.getString("rebateMonth"))
    }
    private fun removeDailyFields(o:JSONObject){val c=o.getJSONObject("run").getJSONArray("curve");for(i in 0 until c.length()){
        listOf("dailyAccountingVersion","realized","holdingCost","holdingValue","holdingLots","cash","dividendAccrued","dayProfit","staleLots","rebateAccrued","rebateChange").forEach{c.getJSONObject(i).remove(it)}
    }}
    @Test fun oldDailyReportsBackfillWithoutChangingOriginalAndDoNotInventRebates(){
        val (s,r)=fixture(version=2);val old=report(s,r);removeDailyFields(old);old.getJSONObject("run").remove("rebateAccrued")
        val before=old.toString();val enriched=BacktestDailyLedger.enrich(old);assertEquals(before,old.toString())
        val rows=enriched.getJSONObject("run").getJSONArray("curve")
        r.curve.forEachIndexed{i,daily->val row=rows.getJSONObject(i);assertEquals(daily.realized!!,row.getDouble("realized"),.001);assertEquals(daily.holdingCost!!,row.getDouble("holdingCost"),.001);assertEquals(daily.holdingValue!!,row.getDouble("holdingValue"),.001);assertEquals(0.0,row.getDouble("rebateAccrued"),.001)}
        assertEquals(r.profit,enriched.getJSONObject("run").getDouble("profit"),.001)
    }
    @Test fun missingHistoricalAccrualsOrTradesStayUnknown(){
        val (s,r)=fixture(2.0,2);val old=report(s,r);removeDailyFields(old)
        val rows=BacktestDailyLedger.rows(old);assertFalse(rows.getJSONObject(0).has("holdingValue"));assertTrue(rows.getJSONObject(0).has("holdingCost"))
        assertEquals(r.curve.last().holdingValue!!,rows.getJSONObject(rows.length()-1).getDouble("holdingValue"),.001)
        old.getJSONObject("run").remove("trades")
        val missing=BacktestDailyLedger.rows(old).getJSONObject(0);assertFalse(missing.has("realized"));assertFalse(missing.has("holdingCost"))
    }
    @Test fun monthlyThresholdIsStrictAndRepricesWholeMonthWithMonthReset(){
        val m=BtRebateMonth("2025-01",buyAmount=25000000.0,sellAmount=25000000.0)
        assertEquals(.0005,m.rate,1e-10);assertEquals(25000.0,m.amount,.001)
        m.sellAmount+=.01;assertEquals(.001,m.rate,1e-10);assertEquals(50000.0,m.amount,.001)
        val days=listOf(LocalDate.of(2025,1,30),LocalDate.of(2025,1,31),LocalDate.of(2025,2,3))
        val series=BtSeries("1234","門檻股",Market.TWSE,days.map{bar(it,26000.0)})
        val p=BtPrepared(listOf(series),days.associateWith{listOf(BtSignal("1234","A"))},days)
        val s=BtSettings(days.first(),days.last(),100000000.0);val r=BacktestEngine.run(p,s)
        assertEquals(3,r.buys);assertEquals(2,r.rebateMonths.size)
        assertEquals(.001,r.rebateMonths.getValue("2025-01").rate,1e-10);assertEquals(.0005,r.rebateMonths.getValue("2025-02").rate,1e-10)
        val one=BacktestEngine.run(p,s.copy(end=days.first()))
        assertEquals(one.curve.single().equity,r.curve.first().equity,.001)
        assertTrue(r.curve[1].rebateChange!!>r.trades[1].price*1000*.001)
        assertEquals(r.rebateAccrued,r.curve.sumOf{it.rebateChange!!},.001)
        val cost=r.trades.first().let{it.price*1000+it.fee}
        val limited=BacktestEngine.run(p,s.copy(capital=2*cost-1,end=days[1]));assertEquals(1,limited.buys);assertTrue(limited.skipped.single().reason.contains("資金"))
    }
    private fun aged(high:Double=102.0,open:Double=100.0,version:Int=3):BtRun{
        val dates=listOf(d,d.plusDays(5),d.plusDays(6));val s=BtSeries("1234","保本股",Market.TWSE,listOf(bar(d),bar(dates[1],100.0,102.0),bar(dates[2],100.0,high,open)))
        return BacktestEngine.run(BtPrepared(listOf(s),mapOf(d to listOf(BtSignal("1234","A"))),dates),BtSettings(d,dates.last(),strategyVersion=version))
    }
    @Test fun agedExitStartsStrictlyAfterFiveCalendarDaysAndNeverSellsAtNetLoss(){
        val r=aged();val sale=r.closed.single();val buy=r.trades.first();val cost=buy.price*1000+buy.fee
        assertEquals(d.plusDays(6),sale.date);assertTrue(sale.pnl>=0);assertTrue(sale.pnl<cost*.03)
        assertTrue(sale.reason.contains("保本"));assertEquals("09:00–13:30",sale.time)
        assertEquals(PaperEngine.netSell(sale.price)-cost,sale.pnl,.001)
        assertTrue(aged(high=100.0).closed.isEmpty())
        assertTrue(aged(version=2).closed.isEmpty())
    }
    @Test fun agedExitDoesNotPickFutureDailyHighOverEarlierBreakEvenFill(){
        val regular=aged();val futureHigh=aged(high=150.0)
        assertEquals(regular.closed.single().price,futureHigh.closed.single().price,.001)
        val gap=aged(high=110.0,open=108.0).closed.single()
        assertEquals("09:00",gap.time);assertEquals(PaperEngine.execution(108.0,false),gap.price,.001);assertTrue(gap.reason.contains("3%"))
    }
}
