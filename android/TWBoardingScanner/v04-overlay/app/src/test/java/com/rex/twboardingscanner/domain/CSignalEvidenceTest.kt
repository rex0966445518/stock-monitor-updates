package com.rex.twboardingscanner.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CSignalEvidenceTest {
    private val stock=MarketStock("2449","京元電子",Market.TWSE,StockSector.SEMICONDUCTOR,292.0,307.0,290.5,305.0,1.67,40029,null,null)
    private fun snapshot(priorVolume:Long=20_000_000L):StockSnapshot {
        val start=LocalDate.of(2026,9,23)
        val bars=(0..20).map{i->
            DailyBar(start.plusDays(i.toLong()).atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),300.0,301.0,299.0,300.0,priorVolume)
        }.toMutableList()
        bars[20]=bars.last().copy(open=292.0,high=307.0,low=290.5,close=305.0,volumeShares=40_029_000L)
        return TechnicalCalculator().build(stock,bars)
    }
    @Test fun screenshotGainCannotPassDefaultEvenWhenBodyAndVolumePass() {
        val result=ScoringEngine().evaluateC(snapshot())
        assertEquals(SignalLight.NONE,result.light)
        assertEquals(CheckState.FAIL,result.checks.single{it.id=="gain"}.state)
        assertEquals(CheckState.PASS,result.checks.single{it.id=="body"}.state)
        assertEquals(CheckState.PASS,result.checks.single{it.id=="burst"}.state)
        val evidence=result.dEvidence!!
        assertTrue(evidence.contains("2026-10-13"))
        assertTrue(evidence.contains("日漲幅 1.67%"))
        assertTrue(evidence.contains("紅K實體 4.45%"))
        assertTrue(evidence.contains("2.00 倍"))
        assertTrue(evidence.contains("上漲 ≥ 3%：未通過"))
    }
    @Test fun customSelectionExplainsOmittedGainInsteadOfClaimingDefaultPass() {
        val defaults=ScanConditions.forRadar(RadarType.C_LONG_RED_VOLUME).filter{it.defaultEnabled}.map{it.id}.toSet()
        val result=ScoringEngine().evaluateC(snapshot(),defaults-"gain")
        assertNotEquals(SignalLight.NONE,result.light)
        assertTrue(result.dEvidence!!.contains("自訂C條件"))
        assertTrue(result.dEvidence!!.contains("上漲 ≥ 3%：未啟用，不作篩選"))
    }
    @Test fun missingPriorVolumeCannotManufactureBurstAndValidOneDayIncreaseIsNotEnough() {
        val snap=snapshot()
        val missing=snap.copy(bars=snap.bars.mapIndexed{i,b->if(i==0)b.copy(volumeShares=0)else b})
        assertNull(RuleMetrics(missing).volumeAtLeast(2.0))
        assertEquals(CheckState.PENDING,ScoringEngine().evaluateC(missing).checks.single{it.id=="burst"}.state)
        val notBurst=snapshot(31_272_656L)
        assertEquals(false,RuleMetrics(notBurst).volumeAtLeast(2.0))
        assertEquals(CheckState.FAIL,ScoringEngine().evaluateC(notBurst).checks.single{it.id=="burst"}.state)
        assertNull(RuleMetrics(snap.copy(bars=snap.bars.drop(1))).volumeAtLeast(2.0))
    }
}
