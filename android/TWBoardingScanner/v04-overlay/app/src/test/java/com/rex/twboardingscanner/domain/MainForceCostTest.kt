package com.rex.twboardingscanner.domain

import com.rex.twboardingscanner.backtest.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigInteger
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class MainForceCostTest {
    private val start=LocalDate.of(2026,8,1)
    private fun candle(i:Int,price:Double,volume:Long=1_000_000L)=DailyBar(
        start.plusDays(i.toLong()).atTime(13,30).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),
        price,price+2.0,price-2.0,price,volume)
    private val stock=MarketStock("2467","測試",Market.TWSE,StockSector.ELECTRONICS,
        100.0,102.0,98.0,100.0,0.0,1000,null,null)

    @Test fun weightedDailyTypicalPriceUsesSharesAndOnlyMostRecent20Dates(){
        val bars=(0 until 20).map{i->candle(i,if(i==19)110.0 else 100.0,
            if(i==19)3_000_000 else 1_000_000)}
        val estimate=MainForceCost.estimate(bars)!!
        assertEquals((19*100.0+3*110.0)/22.0,estimate.price,1e-9)
        assertEquals(start,estimate.from)
        assertEquals(start.plusDays(19),estimate.to)
        assertEquals(22_000_000.0,estimate.totalShares,.01)
        assertTrue(MainForceCost.below(estimate.price-.01,estimate)!!)
        assertFalse(MainForceCost.below(estimate.price,estimate)!!)
        assertFalse(MainForceCost.below(estimate.price+.01,estimate)!!)
        assertEquals(-2.0,estimate.distancePct(estimate.price*.98)!!,1e-6)
        assertEquals(estimate,MainForceCost.estimate(bars+candle(19,110.0,3_000_000)))
    }

    @Test fun incompleteZeroVolumeInvalidDailyBarsCannotPretendToBeInstitutionalCost(){
        val data=(0 until 20).map{candle(it,100.0)}
        assertNull(MainForceCost.estimate(data.take(19)))
        assertNull(MainForceCost.estimate(data.dropLast(1)+candle(19,100.0,0)))
        assertNull(MainForceCost.estimate(data.dropLast(1)+candle(19,Double.NaN)))
        assertNull(MainForceCost.estimate(data.dropLast(1)+candle(19,100.0).copy(high=90.0)))
        assertNull(MainForceCost.below(100.0,null))
        assertNull(MainForceCost.below(Double.NaN,MainForceCost.estimate(data)))
    }

    @Test fun independentOptionalConditionIsPresentInAllFourAndUnknownBlocksOnlyWhenSelected(){
        val id=ScanConditions.MAIN_FORCE_COST_20
        RadarType.entries.forEach{type->
            val options=ScanConditions.forRadar(type)
            assertEquals(1,options.count{it.id==id})
            assertTrue(options.first{it.id==id}.extra)
            assertFalse(options.first{it.id==id}.defaultEnabled)
        }
        val unknown=TechnicalCalculator().build(stock,(0 until 19).map{candle(it,100.0)})
        RadarType.entries.forEach{type->
            val result=when(type){
                RadarType.A_EARLY_BREAKOUT->ScoringEngine().evaluateA(unknown,setOf(id))
                RadarType.B_DEEP_REVERSAL->ScoringEngine().evaluateB(unknown,setOf(id))
                RadarType.C_LONG_RED_VOLUME->ScoringEngine().evaluateC(unknown,setOf(id))
                RadarType.D_MAIN_FORCE->ScoringEngine().evaluateD(unknown,setOf(id))
                RadarType.E_DIF_TOUCH->ScoringEngine().evaluateE(unknown,setOf(id))
                RadarType.F_VOLUME_CONTRACTION->ScoringEngine().evaluateF(unknown,setOf(id))
            }
            assertEquals(CheckState.PENDING,result.checks.single{it.id==id}.state)
            assertEquals(SignalLight.NONE,result.light)
        }
        val candles=(0 until 20).map{candle(it,if(it==19)98.0 else 100.0)}
        val snap=TechnicalCalculator().build(stock,candles)
        assertTrue(RuleMetrics(snap).belowMainForceCost20!!)
        assertEquals(CheckState.PASS,ScoringEngine().evaluateA(snap,setOf(id)).checks.single{it.id==id}.state)
        val above=TechnicalCalculator().build(stock,candles.dropLast(1)+candle(19,110.0))
        assertFalse(RuleMetrics(above).belowMainForceCost20!!)
        assertEquals(CheckState.FAIL,ScoringEngine().evaluateB(above,setOf(id)).checks.single{it.id==id}.state)
    }

    @Test fun robotKeysAppendNewCostBitsWithoutReinterpretingAnyPriorBits(){
        val oldLength=BtRobotSpace.v4Slots.size
        assertEquals(102,oldLength)
        assertEquals(106,BtRobotSpace.v5Slots.size)
        assertEquals(RadarType.entries.take(4).map{it to ScanConditions.MAIN_FORCE_COST_20},
            BtRobotSpace.v5Slots.drop(oldLength))
        val unchanged=BtRobotSpace.rules(BigInteger.ONE.shiftLeft(101).toString(16))
        assertFalse(unchanged.values.any{ScanConditions.MAIN_FORCE_COST_20 in it})
        val newOnly=RadarType.entries.associateWith{setOf(ScanConditions.MAIN_FORCE_COST_20)}
        val encoded=BtRobotSpace.key(newOnly)
        assertEquals(newOnly,BtRobotSpace.rules(encoded))
        assertEquals(BigInteger.ONE.shiftLeft(102),BtRobotSpace.total("0","0",includeCost=false))
        assertEquals(BigInteger.ONE.shiftLeft(106),BtRobotSpace.total("0","0",includeCost=true,includeE=false))
        assertEquals(BigInteger.ONE.shiftLeft(83),
            BtRobotSpace.total("0","0",enabled=defaultActiveRadars(),includeD=false))
    }

    @Test fun historicalSignalUsesOnlyCandlesAvailableAsOfThatDay(){
        val input=(0..29).map{i->candle(i,if(i<20)100.0 else 90.0)}
        val day20=MainForceCost.estimate(input.take(20))
        val day30=MainForceCost.estimate(input)
        assertNotNull(day20);assertNotNull(day30)
        assertEquals(100.0,day20!!.price,1e-9)
        assertTrue(day30!!.price<100.0)
        assertEquals(start.plusDays(29),day30.to)
    }
}
