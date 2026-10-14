package com.rex.twboardingscanner.backtest

import com.rex.twboardingscanner.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

class RobotIndexResumeTest {
    @get:Rule val folder=TemporaryFolder()
    private val start=LocalDate.of(2026,1,1)
    private val settings=BtSettings(start.plusDays(65),start.plusDays(79),enabledRadars=setOf(RadarType.E_DIF_TOUCH))
    private val series=(0..2).map { i-> BtSeries("${2000+i}","fixture",Market.TWSE,(0..79).map { j->
        val close=80.0+i+j*.3+kotlin.math.sin(j.toDouble())*2
        DailyBar(start.plusDays(j.toLong()).atTime(9,0).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),close-.2,close+1,close-1,close,1200000L)
    }) }
    @Test fun interruptedChunksResumeWithoutRecomputingSavedStocksAndMatchFullBuild() {
        val parts=folder.newFolder();var stop=false
        assertThrows(IllegalStateException::class.java) {
            BtConditionIndex.buildResumable(series,settings,parts,"fixture",progress={stop=true},cancel={stop},chunkSize=1)
        }
        assertTrue(java.io.File(parts,"0.bin.gz").exists())
        assertFalse(java.io.File(parts,"1.bin.gz").exists())
        val visited=mutableSetOf<String>()
        val resumed=BtConditionIndex.buildResumable(series,settings,parts,"fixture",flowFor={code,_->visited+=code;null},chunkSize=1)
        assertEquals(setOf("2001","2002"),visited)
        val expected=BtConditionIndex.build(series,settings).prepare(settings)
        assertEquals(expected,resumed.prepare(settings))
        java.io.File(parts,"0.bin.gz").writeText("broken cache")
        visited.clear()
        val repaired=BtConditionIndex.buildResumable(series,settings,parts,"fixture",flowFor={code,_->visited+=code;null},chunkSize=1)
        assertEquals(setOf("2000"),visited)
        assertEquals(expected,repaired.prepare(settings))
    }
}
