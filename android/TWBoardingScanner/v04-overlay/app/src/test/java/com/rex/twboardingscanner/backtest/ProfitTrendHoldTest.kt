package com.rex.twboardingscanner.backtest

import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.paper.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ProfitTrendHoldTest {
    private fun candle(d:LocalDate,close:Double,high:Double=close,open:Double=close)=DailyBar(
        d.atTime(13,30).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),
        open,maxOf(open,high,close),minOf(open,close),close,1_000_000L)
    private fun upDays(start:LocalDate)=(0 until 20).map{i->candle(start.plusDays(i.toLong()),100.0+i)}

    @Test fun requiresKnownRsiAndStrictCompletedMa5Break(){
        val start=LocalDate.of(2026,7,1);val history=upDays(start)
        val momentum=ProfitTrendHold.fromCompleted(history)!!
        assertTrue(momentum.canHoldOnTarget);assertFalse(momentum.closedBelowMa5)
        val weakness=ProfitTrendHold.fromCompleted(history+candle(start.plusDays(20),80.0))!!
        assertTrue(weakness.closedBelowMa5);assertFalse(weakness.canHoldOnTarget)
        assertNull(ProfitTrendHold.fromCompleted(history.take(14)))
        assertNull(ProfitTrendHold.fromCompleted(history.dropLast(1)+candle(start.plusDays(19),Double.NaN)))
    }

    @Test fun deferAtTargetThenSellOnlyAfterConfirmedCloseBelowMa5(){
        val start=LocalDate.of(2026,7,1);val entry=start.plusDays(20)
        val target=entry.plusDays(1);val breakdown=target.plusDays(1);val sale=breakdown.plusDays(1)
        val data=upDays(start)+listOf(
            candle(entry,120.0),candle(target,121.0,128.0,120.0),
            candle(breakdown,100.0,121.0,120.0),candle(sale,96.0,99.0,96.0))
        val p=BtPrepared(listOf(BtSeries("2330","台積電",Market.TWSE,data)),
            mapOf(entry to listOf(BtSignal("2330","A"))),listOf(entry,target,breakdown,sale))
        val settings=BtSettings(entry,sale,capital=500000.0,holdProfitUntilMa5Break=true)
        val early=BacktestEngine.run(p,settings.copy(end=target))
        assertEquals(1,early.holdings.size)
        assertTrue(early.holdings.single().trendHoldActivated)
        assertTrue(early.closed.isEmpty())
        val heldOnBreak=BacktestEngine.run(p,settings.copy(end=breakdown))
        assertEquals(1,heldOnBreak.holdings.size) // today's full close is not known until after this session
        val result=BacktestEngine.run(p,settings)
        assertEquals(2,result.trades.size);assertEquals(sale,result.closed.single().date)
        assertTrue(result.closed.single().reason.contains("跌破5日線"))
        assertEquals(0,result.holdings.size)
        val old=BacktestEngine.run(p,settings.copy(holdProfitUntilMa5Break=false))
        assertEquals(target,old.closed.single().date)
    }

    @Test fun armedLotWithMissingCompletedKDoesNotSellBlindly(){
        val day=LocalDate.of(2026,7,2)
        val p=BtHolding("2330","台積電","A",100.0,100143.0,day.minusDays(1),day.minusDays(1),
            110.0,day.minusDays(1),"lot",105.0,day.minusDays(1),true)
        val ledger=BtRun(500000.0,cash=399857.0,holdings=mutableListOf(p),strategyVersion=4)
        BacktestEngine.advance(ledger,day,emptyList(),mapOf("2330" to mapOf(day to candle(day,110.0,120.0))),
            emptyMap(),emptyMap(),BtSettings(day.minusDays(1),day,capital=500000.0,holdProfitUntilMa5Break=true))
        assertEquals(1,ledger.holdings.size);assertTrue(ledger.holdings.single().trendHoldActivated)
    }

    @Test fun paperArmedStateSurvivesSerializationAndExitsOnNextTradingDay(){
        val day=LocalDate.of(2026,10,8)
        val history=upDays(day.minusDays(20))
        val buyAt=day.minusDays(7).atTime(9,0).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli()
        val now=day.atTime(9,5).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli()
        val cost=120000+PaperEngine.fee(120000.0)
        val strategy=BtSettings(day.minusDays(7),day.plusDays(5),capital=500000.0,
            stockScope=StockScope.SCANNER,targetNetPct=3.0,holdProfitUntilMa5Break=true,
            marketGuardVersion=MarketCrashGuard.VERSION)
        val book=PaperBook(capital=500000.0,cash=500000.0-cost,enabled=true,strategy=strategy).apply{
            positions+=PaperPosition("2330","台積電","tse","A",120.0,cost,buyAt,120.0,120.0,buyAt)
        }
        fun quote(instant:Long,price:Double)=PaperQuote("2330",instant,price,price,price+1,10.0,10.0,10_000,200.0,1.0)
        PaperEngine.step(book,mapOf("2330" to quote(now,128.0)),now,histories=mapOf("2330" to history))
        assertTrue(book.positions.single().trendHoldActivated)
        assertTrue(book.trades.isEmpty()) // aged > 5 days still stays after target
        val repository=PaperRepository(RuntimeEnvironment.getApplication())
        val restored=repository.decode(repository.encode(book))
        assertTrue(restored.positions.single().trendHoldActivated)
        val next=day.plusDays(1).atTime(9,5).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli()
        PaperEngine.step(restored,mapOf("2330" to quote(next,90.0)),next,
            histories=mapOf("2330" to history+candle(day,90.0)))
        assertEquals(1,restored.trades.size)
        assertEquals("SELL",restored.trades.single().side)
        assertTrue(restored.trades.single().reason.contains("跌破5MA"))
    }

    @Test fun settingsSnapshotRoundTripAndOldSnapshotDefaultsOff(){
        val settings=BtSettings(LocalDate.of(2026,7,1),LocalDate.of(2026,7,2),holdProfitUntilMa5Break=true)
        val encoded=BtSettingsCodec.encode(settings)
        assertTrue(BtSettingsCodec.decode(encoded).holdProfitUntilMa5Break)
        val legacy=JSONObject(encoded.toString()).apply{remove("holdProfitUntilMa5Break")}
        assertFalse(BtSettingsCodec.decode(legacy).holdProfitUntilMa5Break)
    }
}
