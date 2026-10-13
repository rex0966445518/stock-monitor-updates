package com.rex.twboardingscanner.domain

import com.rex.twboardingscanner.backtest.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class MomentumAndMa5ExitTest {
    private val day=LocalDate.of(2026,10,8)
    private fun bars(prices:List<Double>)=prices.mapIndexed{i,p->DailyBar(day.minusDays((prices.lastIndex-i).toLong()).atTime(9,0).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),p,p+1,p-1,p,1000000)}
    private fun metrics(prices:List<Double>):RuleMetrics {
        val b=bars(prices)
        return RuleMetrics(TechnicalCalculator().build(MarketStock("2330","測試",Market.TWSE,StockSector.SEMICONDUCTOR,b.last().close,b.last().high,b.last().low,b.last().open,0.0,1000,null,null),b))
    }
    @Test fun zeroAxisRequiresPositiveSignalNotJustPositiveHistogram(){
        assertEquals(true,metrics(List(60){100.0+it}).macdAboveZero)
        assertEquals(false,metrics(List(60){200.0-it}).macdAboveZero)
        assertEquals(false,metrics(List(60){100.0}).macdAboveZero)
        val rebound=List(60){200.0-it}+145.0
        val macd=TechnicalCalculator().macd(rebound)!!
        assertTrue(macd.hist>0);assertTrue(macd.signal<0)
        assertEquals(false,metrics(rebound).macdAboveZero)
        assertNull(metrics(List(20){100.0}).macdAboveZero)
    }
    @Test fun rsiJumpMustBeWithinExactlyOneTradingDay(){
        val falling=List(30){100.0-it}
        assertEquals(true,metrics(falling+100.0).rsi30To50)
        assertEquals(false,metrics(falling+72.0).rsi30To50)
        assertEquals(false,metrics(falling+100.0+101.0).rsi30To50)
        assertEquals(false,metrics(List(40){100.0}).rsi30To50)
        assertNull(metrics(List(15){100.0}).rsi30To50)
        for(type in RadarType.entries){
            val rules=ScanConditions.forRadar(type)
            assertEquals(2,rules.count{it.id in ScanConditions.MOMENTUM_IDS})
            assertTrue(rules.filter{it.id in ScanConditions.MOMENTUM_IDS}.all{!it.defaultEnabled})
            assertEquals(true,rules.single{it.id==ScanConditions.RSI_30_TO_50}.test(metrics(falling+100.0)))
        }
    }
    @Test fun standaloneMa5ExitIgnoresProfitAndRsiAndSurvivesSerialization(){
        val rule=ExitRule(ma5Exit=true)
        val history=bars(List(30){100.0+it}+124.0)
        assertTrue(RsiGoldenCross.values(history.map{it.close},14).last()>50)
        for(pnl in listOf(-10.0,0.0,20.0))assertEquals(0,ExitRules.match(listOf(rule),day.minusDays(5),day,pnl,history))
        assertNull(ExitRules.match(emptyList(),day.minusDays(5),day,20.0,history))
        assertNull(ExitRules.match(listOf(rule),day.minusDays(5),day,20.0,bars(List(6){100.0})))
        assertNull(ExitRules.match(listOf(rule),day.minusDays(5),day,20.0,history.takeLast(4)))
        assertNull(ExitRules.match(listOf(rule),day.minusDays(5),day,20.0,history.dropLast(1)))
        val all=List(5){ExitRule()}+ExitRule(bearishExit=true)+rule
        assertEquals(all,ExitRules.read(ExitRules.json(all)))
        assertFalse(ExitRules.read(org.json.JSONArray("[{\"maDays\":5}]"))[0].ma5Exit)
    }
    @Test fun optionalMomentumRulesReachEngineAndRobotCache(){
        val prices=List(60){200.0-it}+145.0
        val s=metrics(prices).s
        val engine=ScoringEngine()
        for(type in RadarType.entries){
            fun evaluate(ids:Set<String>)=when(type){
                RadarType.A_EARLY_BREAKOUT->engine.evaluateA(s,ids)
                RadarType.B_DEEP_REVERSAL->engine.evaluateB(s,ids)
                RadarType.C_LONG_RED_VOLUME->engine.evaluateC(s,ids)
                RadarType.D_MAIN_FORCE->engine.evaluateD(s,ids)
                RadarType.E_DIF_TOUCH->engine.evaluateE(s,ids)
                RadarType.F_VOLUME_CONTRACTION->engine.evaluateF(s,ids)
            }
            assertNotEquals(SignalLight.NONE,evaluate(setOf("price")).light)
            assertEquals(SignalLight.NONE,evaluate(setOf("price",ScanConditions.MACD_ABOVE_ZERO)).light)
        }
        val series=listOf(BtSeries("2330","測試",Market.TWSE,bars(prices)))
        for(ids in listOf(setOf("price"),ScanConditions.MOMENTUM_IDS)){
            val settings=BtRobotSpace.settings(RadarType.entries.associateWith{ids}).copy(start=day.minusDays(2),end=day,enabledRadars=RadarType.entries.toSet())
            assertEquals(BacktestEngine.prepare(series,settings).signals,BtConditionIndex.build(series,settings).prepare(settings).signals)
        }
    }
    @Test fun ma5ExitExecutesNextOpenEvenBeforeProfitTarget(){
        val history=bars(List(30){100.0+it}+124.0)
        val next=history.last().copy(time=day.plusDays(1).atTime(9,0).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),open=123.0,close=123.0)
        val series=BtSeries("2330","測試",Market.TWSE,history+next)
        val entry=day.minusDays(1)
        val prepared=BtPrepared(listOf(series),mapOf(entry to listOf(BtSignal("2330","A"))),listOf(entry,day,day.plusDays(1)))
        val settings=BtSettings(entry,day.plusDays(1),targetNetPct=50.0,holdProfitUntilMa5Break=true,exitRules=listOf(ExitRule(ma5Exit=true)))
        val run=BacktestEngine.run(prepared,settings)
        val sell=run.closed.single()
        assertEquals(day.plusDays(1),sell.date);assertEquals(day,sell.dataDate)
        assertTrue(sell.reason.contains("不參考RSI"))
        assertTrue(BacktestEngine.run(prepared,settings.copy(end=day)).closed.isEmpty())
        assertTrue(BacktestEngine.run(prepared,settings.copy(exitRules=emptyList())).closed.isEmpty())
    }
    @Test fun appendedRobotBitsPreserveEveryHistoricalPosition(){
        val old=BtRobotSpace.v6Slots
        assertEquals(old,BtRobotSpace.allSlots.take(old.size))
        assertEquals(12,BtRobotSpace.v7Slots.size-old.size)
        old.forEachIndexed{i,pair->assertTrue(pair.second in BtRobotSpace.rules(java.math.BigInteger.ONE.shiftLeft(i).toString(16)).getValue(pair.first))}
        assertEquals(java.math.BigInteger.ONE.shiftLeft(old.size),BtRobotSpace.total("0",includeMomentum=false))
        val settings=BtRobotSpace.settings(RadarType.entries.associateWith{ScanConditions.MOMENTUM_IDS})
        assertEquals(settings.rules,BtRobotSpace.rules(BtRobotSpace.key(settings.rules)))
    }
}
