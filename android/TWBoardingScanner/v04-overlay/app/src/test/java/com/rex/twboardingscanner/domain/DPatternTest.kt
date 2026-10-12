package com.rex.twboardingscanner.domain

import com.rex.twboardingscanner.backtest.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.math.BigInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class DPatternTest{
    private val end=LocalDate.of(2026,10,9)
    private fun candles(full:Boolean=true):List<DailyBar>{
        val bars=(0 until 80).map{i->
            val date=end.minusDays((79-i).toLong()).atTime(9,0)
                .atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli()
            DailyBar(date,100.0,100.0,98.0,99.5,1000000)
        }.toMutableList()
        // A historical limit-up HIGH, and then return to a quiet baseline.
        bars[65]=bars[65].copy(high=110.0,close=110.0)
        // Recent four clean rising red candlesticks and a real upward gap.
        (76..79).forEach{i->
            val min=101.0+(i-76)*2
            bars[i]=bars[i].copy(open=min+0.2,high=min+2.0,low=min,close=min+1.2,
                volumeShares=if(i>=77)3000000 else 1200000)
        }
        return if(full)bars else bars.take(9)
    }
    @Test fun DDefaultsAndHistoricalBitsNeverChange(){
        val dOptions=ScanConditions.forRadar(RadarType.D_MAIN_FORCE)
        val aOptions=ScanConditions.forRadar(RadarType.A_EARLY_BREAKOUT)
        assertEquals(32,dOptions.size)
        assertEquals(26,aOptions.size)
        assertEquals(aOptions.map{it.id},dOptions.drop(6).map{it.id})
        assertEquals((setOf(DPattern.LIMIT,DPattern.GREEN,DPattern.GAP,DPattern.VOLUME)+ScanConditions.D_A_DEFAULT_IDS),
            dOptions.filter{it.defaultEnabled}.map{it.id}.toSet())
        assertEquals(listOf(DPattern.LIMIT,DPattern.GREEN,DPattern.GAP,DPattern.UNFILLED,DPattern.VOLUME,DPattern.RELATIVE),
            dOptions.take(6).map{it.id})
        assertEquals(dOptions.map{it.id}.size,dOptions.map{it.id}.distinct().size)
        assertEquals(defaultActiveRadars(),BtSettings(LocalDate.of(2026,8,1),end).enabledRadars)
        assertFalse(RadarType.D_MAIN_FORCE in defaultActiveRadars())
        assertEquals(65,BtRobotSpace.legacySlots.size)
        assertEquals(71,BtRobotSpace.slots.size)
        assertEquals(106,BtRobotSpace.allSlots.size)
        assertEquals(102,BtRobotSpace.v4Slots.size)
        assertEquals(RadarType.entries.map{it to ScanConditions.MAIN_FORCE_COST_20},BtRobotSpace.allSlots.takeLast(4))
        assertEquals(BtRobotSpace.slots,BtRobotSpace.allSlots.take(71))
        assertEquals(BtRobotSpace.dSlots.take(6),BtRobotSpace.allSlots.drop(71).take(6))
        val fromOld=BtRobotSpace.key(defaultBtRules())
        val key=BtRobotSpace.key(BtRobotSpace.rules(fromOld))
        assertEquals(fromOld,key)
        assertEquals(BigInteger.ONE.shiftLeft(74),BtRobotSpace.total("0","0",
            enabled=defaultActiveRadars(),includeD=false))
    }
    @Test fun limitGreenGapAndVolumeProvideTraceableDatesAndPreciseRatios(){
        val bars=candles()
        val benchmark=bars.associate{RuleMetrics.tradingDate(it.time) to 22000.0}
        val e=DPattern.inspect(bars,DPatternConfig.DEFAULT,benchmark)
        val checks=DPattern.checks(e)
        assertEquals(true,checks[DPattern.LIMIT])
        assertEquals(true,checks[DPattern.GREEN])
        assertEquals(true,checks[DPattern.GAP])
        assertEquals(true,checks[DPattern.UNFILLED])
        assertEquals(true,checks[DPattern.VOLUME])
        assertEquals(true,checks[DPattern.RELATIVE])
        assertEquals(end.minusDays(14),e.limitDates.last())
        assertEquals(end.minusDays(3),e.bullish!!.from)
        assertEquals(end,e.bullish.to)
        assertEquals(end.minusDays(3),e.latestGap!!.date)
        assertTrue(e.latestUnfilledGap!!.lower<e.latestUnfilledGap.upper)
        assertEquals(3,e.volumes.size)
        assertTrue(e.volumes.all{it.ratio>2.0})
        assertTrue(e.format().contains("連陽起訖"))
        assertTrue(e.format().contains("缺口"))
        assertTrue(e.format().contains("量比"))
        assertTrue(e.format().contains("漲停"))
    }
    @Test fun thresholdControlsAndIncompleteSourceNeverPass(){
        val bars=candles()
        val strict=DPatternConfig(20,2,5,3,20,4.0)
        val check=DPattern.checks(DPattern.inspect(bars,strict))
        assertEquals(false,check[DPattern.LIMIT])
        assertEquals(false,check[DPattern.GREEN])
        assertEquals(false,check[DPattern.VOLUME])
        assertNull(check[DPattern.RELATIVE])
        val short=DPattern.checks(DPattern.inspect(candles(false)))
        assertNull(short[DPattern.LIMIT])
        assertNull(short[DPattern.VOLUME])
        assertThrows(Exception::class.java){DPatternConfig(3,1,4,3,20,2.0).validated()}
        assertThrows(Exception::class.java){DPatternConfig(20,1,4,3,20,0.0).validated()}
        assertEquals(DPatternConfig.DEFAULT,DPatternConfig.read(DPatternConfig.DEFAULT.json()))
        assertFalse(DPattern.inspect(bars).format().contains("保證"))
    }
    @Test fun fillAfterGapRemovesUnfilledSignalAndDoesNotLookAhead(){
        val candles=candles().toMutableList()
        // Gap at last four days, then later full gap-fill on the next trading day.
        val trailing=candles.last()
        val later=trailing.copy(time=end.plusDays(1).atTime(9,0)
            .atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),
            low=97.0,open=99.0,high=108.0,close=100.0)
        val before=DPattern.inspect(candles)
        val after=DPattern.inspect(candles+later)
        assertNotNull(before.latestUnfilledGap)
        assertTrue(after.latestGap!!.date<=end)
        assertTrue(after.latestUnfilledGap==null || after.latestUnfilledGap!!.date!=end)
    }
    @Test fun DCategoryIsOptInAndEvidenceAttachesOnlyToD(){
        val bars=candles()
        val stock=MarketStock("2330","台積電",Market.TWSE,StockSector.SEMICONDUCTOR,
            105.0,108.0,101.0,106.0,5.0,2000,null,null)
        val snapshot=TechnicalCalculator().build(stock,bars)
            .copy(dConfig=DPatternConfig.DEFAULT,marketIndexCloses=bars.associate{RuleMetrics.tradingDate(it.time) to 22000.0})
        val legacyD=setOf(DPattern.LIMIT,DPattern.GREEN,DPattern.GAP,DPattern.VOLUME)
        val d=ScoringEngine().evaluateD(snapshot,legacyD)
        assertEquals(SignalLight.ORANGE,d.light)
        assertEquals(4,d.checks.count{it.selected})
        val gated=ScoringEngine().evaluateD(snapshot.copy(bars=bars.map{it.copy(close=20.0)}))
        assertEquals(CheckState.FAIL,gated.checks.single{it.id=="price"}.state)
        assertEquals(SignalLight.NONE,gated.light)
        assertNotNull(d.dEvidence)
        assertTrue(d.dEvidence!!.contains("漲停"))
        val a=ScoringEngine().evaluateA(snapshot,setOf("price"))
        assertNull(a.dEvidence)
    }
}
