package com.rex.twboardingscanner.domain

import com.rex.twboardingscanner.backtest.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class RsiGoldenCrossTest{
    @Test fun strict35AndFreshRisingCrossAreRequired(){
        fun test(now:Double,long:Double,previous:Double,previousLong:Double)=RsiGoldenCross.Evidence(now,long,previous,previousLong).passes
        assertTrue(test(34.99,30.0,20.0,25.0))
        assertTrue(test(30.0,29.0,25.0,25.0))
        assertFalse(test(35.0,30.0,20.0,25.0))
        assertFalse(test(30.0,30.0,20.0,25.0))
        assertFalse(test(30.0,25.0,29.0,28.0)) // already above yesterday
        assertFalse(test(29.0,25.0,30.0,32.0)) // crossing caused by RSI10 falling
        assertFalse(test(25.0,30.0,20.0,25.0)) // near but not yet crossed
        assertNull(RsiGoldenCross.inspect(List(11){100.0}))
        assertNull(RsiGoldenCross.inspect(List(12){if(it==5)Double.NaN else 100.0}))
        assertFalse(RsiGoldenCross.inspect(List(20){100.0})!!.passes)
    }
    private val day=LocalDate.of(2026,10,8)
    private fun prices()=List(80){100.0-it*.4}+68.6 // prior close68.4; first small rebound
    private fun bars()=prices().mapIndexed{i,p->DailyBar(day.minusDays((80-i).toLong()).atTime(9,0).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),p,p+1,p-1,p,1000000)}
    @Test fun WilderReboundCrossIsAvailableAsIndependentOptInInEveryRadar(){
        val evidence=RsiGoldenCross.inspect(prices())!!
        assertEquals(11.111111,evidence.short,1e-5)
        assertEquals(5.263157,evidence.long,1e-5)
        assertEquals(0.0,evidence.previousShort,1e-9)
        assertTrue(evidence.passes)
        val stock=MarketStock("2330","測試",Market.TWSE,StockSector.SEMICONDUCTOR,68.6,69.6,67.6,68.6,0.0,1000,null,null)
        val snapshot=TechnicalCalculator().build(stock,bars())
        for(type in RadarType.entries){
            val rule=ScanConditions.forRadar(type).single{it.id==RsiGoldenCross.ID}
            assertFalse(rule.defaultEnabled)
            assertEquals(true,rule.test(RuleMetrics(snapshot)))
            assertEquals(false,rule.test(RuleMetrics(snapshot.copy(bars=bars().dropLast(1)))))
        }
        val result=ScoringEngine().evaluateA(snapshot,setOf(RsiGoldenCross.ID))
        assertNotEquals(SignalLight.NONE,result.light)
        assertTrue(result.dEvidence!!.contains("RSI(5)"))
        val rules=RadarType.entries.associateWith{setOf(RsiGoldenCross.ID)}
        assertEquals(rules,BtRobotSpace.rules(BtRobotSpace.key(rules)))
        assertEquals(RadarType.entries.map{it to RsiGoldenCross.ID},BtRobotSpace.allSlots.takeLast(5))
        assertEquals(106,BtRobotSpace.v5Slots.size)
    }
    @Test fun RobotCacheAndReferenceKeepAllFiveCrossResultsAndJournalChecks(){
        val input=bars()+bars().last().copy(time=day.plusDays(1).atTime(9,0).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),close=68.8)
        val series=listOf(BtSeries("2330","測試",Market.TWSE,input))
        val rules=RadarType.entries.associateWith{setOf(RsiGoldenCross.ID)}
        val settings=BtRobotSpace.settings(rules).copy(start=day,end=day.plusDays(1),enabledRadars=RadarType.entries.toSet(),stockScope=StockScope.ROBOT)
        val ref=BacktestEngine.prepare(series,settings)
        assertEquals("C/D",ref.signals.getValue(day).single().radar)
        assertTrue(ref.signals.getValue(day.plusDays(1)).single().radar.split('/').containsAll(listOf("A","B","E")))
        val index=BtConditionIndex.build(series,settings)
        assertEquals(ref.signals.mapValues{it.value.map{v->v.radar}},index.prepare(settings).signals.mapValues{it.value.map{v->v.radar}})
        assertEquals(settings,BtSettingsCodec.decode(BtSettingsCodec.encode(settings)))
        val store=BtRobotStore(RuntimeEnvironment.getApplication())
        val id=BtRobotJournalSync.createBatch(store,settings,"rsi-journal")
        assertEquals(rules,BtRobotSpace.rules(store.session(id)!!.getString("required")))
    }
}
