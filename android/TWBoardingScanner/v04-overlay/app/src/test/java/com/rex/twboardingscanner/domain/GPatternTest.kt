package com.rex.twboardingscanner.domain

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class GPatternTest{
    private val start=LocalDate.of(2026,1,1)
    private fun candles(closes:List<Double>,volumes:List<Long> = List(closes.size){1_000_000L}):List<DailyBar> =
        closes.mapIndexed{i,p->
            DailyBar(start.plusDays(i.toLong()).atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),
                p,p+1,p-1,p,volumes[i])
        }

    @Test fun g1BigCandleAndG2PullbackAreDifferentDaysAndRequireCompletedBar(){
        val previous=candles(List(25){100.0})
        val bullish=previous.last().copy(
            time=start.plusDays(25).atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),
            open=100.0,high=107.0,low=100.0,close=106.0,volumeShares=2_000_000L)
        val touch=bullish.copy(time=start.plusDays(26).atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),
            open=104.0,high=105.0,low=101.6,close=102.0,volumeShares=900_000L)
        val before=GPattern.inspect(previous+bullish)
        assertEquals(false,before.eventPass)
        assertEquals(false,before.pullbackPass)
        val after=GPattern.inspect(previous+bullish+touch)
        assertEquals(true,after.eventPass)
        assertEquals(true,after.pullbackPass)
        assertEquals(true,after.eventVolumePass)
        assertEquals(false,after.volumePass)
        assertEquals(6.0,after.eventGain!!,1e-8)
        assertTrue(after.format().contains("5MA"))
    }
    @Test fun requiredEntryConditionsAreSeparateFromG3ToG5(){
        assertEquals(GPattern.REQUIRED_IDS,ScanConditions.forRadar(RadarType.G_MA5_WAVE)
            .filter{it.defaultEnabled}.map{it.id}.toSet())
        assertEquals(8,GPattern.rules().size)
        assertNull(GPattern.inspect(candles(List(5){100.0})).pullbackPass)
        assertFalse(RadarType.G_MA5_WAVE in com.rex.twboardingscanner.backtest.defaultActiveRadars())
    }
    @Test fun sellAndRebuyDecisionsRespectSharesAndPriority(){
        val baseline=List(25){100.0}
        val g3=GExitEngine.decide(candles(baseline+110.0),GPatternConfig.DEFAULT,100.0,1000)
        assertEquals(GAction.G3_REDUCE,g3?.action)
        assertEquals(GAction.G4_REDUCE,GExitEngine.decide(candles(baseline+99.0),GPatternConfig.DEFAULT,100.0,1000)?.action)
        assertEquals(GAction.G5_EXIT,GExitEngine.decide(candles(baseline+listOf(99.0,98.0,97.0)),GPatternConfig.DEFAULT,100.0,500,0,500)?.action)
        assertEquals(GAction.G3_REBUY,GExitEngine.decide(candles(baseline+listOf(106.0,102.0)),GPatternConfig.DEFAULT,100.0,500,500,0)?.action)
        assertEquals(GAction.G4_REBUY,GExitEngine.decide(candles(baseline+listOf(99.0,102.0)),GPatternConfig.DEFAULT,100.0,500,0,500)?.action)
        assertEquals(GAction.STOP,GExitEngine.decide(candles(baseline+93.0),GPatternConfig.DEFAULT.copy(stopEnabled=true),100.0,1000)?.action)
    }
    @Test fun configurationMustRoundTripAndRejectInvalidPercentages(){
        val config=GPatternConfig.DEFAULT.copy(g5Days=4,g3SellPct=35.0,eventGainPct=6.0,g4Enabled=false)
        assertEquals(config,GPatternConfig.read(config.json()))
        assertEquals(GPatternConfig.DEFAULT,GPatternConfig.read(null))
        assertTrue(runCatching{config.copy(g5Days=0).validated()}.isFailure)
        assertTrue(runCatching{config.copy(g3SellPct=101.0).validated()}.isFailure)
    }
}
