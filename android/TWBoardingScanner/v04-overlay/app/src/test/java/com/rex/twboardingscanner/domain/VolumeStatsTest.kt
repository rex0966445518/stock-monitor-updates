package com.rex.twboardingscanner.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class VolumeStatsTest {
    private fun bars(volumes:List<Long>)=volumes.mapIndexed { i,v -> DailyBar(
        LocalDate.of(2026,1,1).plusDays(i.toLong()).atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),
        100.0,101.0,99.0,100.0,v) }
    @Test fun summaryKeepsFractionalLotsAndExcludesTriggerFromAverage() {
        val v=VolumeStats.from(bars(List(20){1000000L}+1500500L))
        assertEquals(1500.5,v.latestLots!!,1e-9)
        assertEquals(50.05,v.changePct!!,1e-9)
        assertEquals(1.5005,v.ratio20!!,1e-9)
        assertEquals(5,v.recent.size)
        assertEquals(1500500L,v.recent.last().volumeShares)
    }
    @Test fun noFabricatedRatiosWhenHistoryOrDenominatorMissing() {
        val short=VolumeStats.from(bars(listOf(0L,500000L)))
        assertNull(short.changePct);assertNull(short.ratio20)
        assertEquals(2,short.recent.size)
        assertNull(VolumeStats.from(bars(List(20){0L}+500000L)).ratio20)
        assertNull(VolumeStats.from(emptyList()).latestLots)
    }
    @Test fun recentSessionsAreChronologicalAndLimitedToFive() {
        val input=bars((1..8).map{it*1000L})
        val result=VolumeStats.from(input.reversed())
        assertEquals(listOf(4000L,5000L,6000L,7000L,8000L),result.recent.map{it.volumeShares})
    }
}
