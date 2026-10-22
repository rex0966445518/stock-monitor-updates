package com.rex.twboardingscanner.domain

import com.rex.twboardingscanner.backtest.BtRobotSpace
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.math.BigInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class DChipRulesTest {
    private val finalDay=LocalDate.of(2026,10,22)
    private val before=finalDay.minusDays(1)
    private val tradeEnd=LocalTime.of(13,30)
    private fun date(day:LocalDate)=day.atTime(9,0).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli()
    private val bars=List(21){i->
        val close=if(i==20)110.0 else 100.0
        DailyBar(date(finalDay.minusDays((20-i).toLong())),100.0,
            close+1.0,99.0,close,1000000L)
    }
    private fun chip(date:LocalDate,holder:Double?,main:Double?,f:Long?,t:Long?,
        time:LocalTime=tradeEnd)=DChipDay(date,holder,main,f,t,time,"licensed-daily-export")

    private fun snap(current:DChipDay?=null,previous:DChipDay?=null):StockSnapshot{
        val stock=MarketStock("2330","台積電",Market.TWSE,StockSector.SEMICONDUCTOR,
            100.0,111.0,99.0,110.0,10.0,1000,null,null)
        return TechnicalCalculator().build(stock,bars).copy(
            timestamp=finalDay.atTime(13,30).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),
            dChipToday=current,dChipPrevious=previous)
    }

    @Test fun allFiveDOptInConditionsPassWithTwoSourcedTradingDays(){
        val today=chip(finalDay,45.13,8.51,240000L,-35000L)
        val prior=chip(before,45.12,8.50,60000L,10000L)
        val m=RuleMetrics(snap(today,prior))
        assertEquals(true,m.dBigHolderIncreased)
        assertEquals(true,m.dMainForce5Increased)
        assertEquals(true,m.dInstitutionBoughtToday)
        assertEquals(true,m.dMa5Crossed10)
        assertEquals(true,m.dMa5Crossed20)
        val result=ScoringEngine().evaluateD(snap(today,prior),DPattern.CHIP_IDS)
        assertEquals(5,result.checks.count{it.selected&&it.state==CheckState.PASS})
        assertEquals(SignalLight.ORANGE,result.light)
        assertTrue(result.dEvidence!!.contains("45.1200%"))
        assertTrue(result.dEvidence!!.contains("licensed-daily-export"))
    }

    @Test fun missingAndFutureDataNeverTurnIntoFalseInstitutionalBuySignals(){
        val m=RuleMetrics(snap())
        assertNull(m.dBigHolderIncreased)
        assertNull(m.dMainForce5Increased)
        assertNull(m.dInstitutionBoughtToday)
        val gated=ScoringEngine().evaluateD(snap(),DPattern.CHIP_IDS)
        assertEquals(SignalLight.NONE,gated.light)
        assertEquals(3,gated.checks.count{it.selected&&it.state==CheckState.PENDING})
        assertEquals(2,gated.checks.count{it.selected&&it.state==CheckState.PASS})
        val day=chip(finalDay,45.13,8.5,-100L,null)
        val previous=chip(before,45.12,8.4,null,null)
        val negative=RuleMetrics(snap(day,previous))
        assertNull("unknown trust flow must remain pending if foreign is negative",negative.dInstitutionBoughtToday)
        val stale=RuleMetrics(snap(day.copy(date=before),previous))
        assertNull(stale.dBigHolderIncreased)
        val early=snap(day,previous).copy(timestamp=finalDay.atTime(9,15).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli())
        assertNull(RuleMetrics(early).dBigHolderIncreased)
        assertNull(RuleMetrics(snap(day,previous.copy(asOf=LocalTime.of(10,30)))).dMainForce5Increased)
    }

    @Test fun crossingIsAnEventNotSimplyAnAboveMaRelationship(){
        val flat=bars.map{it.copy(close=100.0)}
        val old=snap().copy(bars=flat)
        val m=RuleMetrics(old)
        assertEquals(false,m.dMa5Crossed10)
        assertEquals(false,m.dMa5Crossed20)
        val short=RuleMetrics(snap().copy(bars=bars.take(8)))
        assertNull(short.dMa5Crossed10)
        assertNull(short.dMa5Crossed20)
    }

    @Test fun historicalRobotSlotsRetainAllPositionsAndFiveNewBitsAppend(){
        val rules=ScanConditions.forRadar(RadarType.D_MAIN_FORCE)
        assertEquals(40,rules.size)
        assertEquals(DPattern.CHIP_ORDER,rules.takeLast(5).map{it.id})
        assertTrue(rules.takeLast(5).all{it.extra&&!it.defaultEnabled})
        assertEquals(DPattern.CHIP_ORDER.map{RadarType.D_MAIN_FORCE to it},BtRobotSpace.allSlots.takeLast(5))
        assertEquals(BtRobotSpace.previousSlots,BtRobotSpace.allSlots.dropLast(5))
        val previous=BtRobotSpace.key(RadarType.entries.associateWith{emptySet<String>()})
        assertEquals("0",previous)
        val r=RadarType.entries.associateWith{if(it==RadarType.D_MAIN_FORCE)DPattern.CHIP_IDS else emptySet()}
        assertEquals(r,BtRobotSpace.rules(BtRobotSpace.key(r)))
        assertEquals(BigInteger.ONE.shiftLeft(BtRobotSpace.allSlots.size),BtRobotSpace.total)
    }
}
