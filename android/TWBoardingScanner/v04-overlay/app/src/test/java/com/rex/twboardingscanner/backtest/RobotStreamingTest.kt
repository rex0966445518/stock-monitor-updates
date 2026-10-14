package com.rex.twboardingscanner.backtest

import com.rex.twboardingscanner.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

class RobotStreamingTest {
    @get:Rule val folder=TemporaryFolder()
    private val begin=LocalDate.of(2025,10,1)
    private fun bars(n:Int)=List(n){j->
        val close=70.0+j*.06+kotlin.math.sin(j*.3)*4
        DailyBar(begin.plusDays(j.toLong()).atTime(9,0).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),close-.3,close+2,close-2,close,1200000L+j*1000)
    }
    private fun series(n:Int,length:Int)=bars(length).let { shared->List(n){i->BtSeries("${2000+i}","fixture$i",Market.TWSE,shared)} }
    @Test fun eightMonthFRunTestsEveryBlankAgainstFullCalculation() {
        val stocks=series(2,340)
        val base=BtSettings(begin.plusDays(80),begin.plusDays(339),enabledRadars=setOf(RadarType.F_VOLUME_CONTRACTION),targetNetPct=9.0,maxHoldingStocks=24)
        val disk=BtRobotIndex.open(stocks,base,folder.newFolder(),"eight-month-F",chunkSize=1)
        val full=BtConditionIndex.build(stocks,base)
        val keys=BtRobotSingleOption.keys("0","0",base.enabledRadars)
        assertEquals(35,keys.size)
        val compact=BacktestEngine.executionIndex(stocks,base.start,base.end)
        val original=BacktestEngine.executionIndex(stocks)
        assertEquals(260,compact.bars.getValue("2000").size)
        assertEquals(stocks[0].bars[79].close,compact.previous.getValue("2000").getValue(base.start),0.0)
        for(key in keys){
            val settings=base.copy(rules=BtRobotSpace.rules(key),holdProfitUntilMa5Break=true)
            val prepared=disk.prepare(settings)
            val expected=full.prepare(settings)
            assertEquals(expected,prepared)
            val run=BacktestEngine.run(prepared,settings,execution=compact)
            val reference=BacktestEngine.run(expected,settings,execution=original)
            assertEquals(reference,run)
        }
        assertEquals(stocks.associateBy{it.code},compact.meta) // full warmup retained for exit/RSI calculations
    }
    @Test fun interruptedDiskPreparationReusesChunksAndRepairsCorruption() {
        val stocks=series(3,85);val settings=BtSettings(begin.plusDays(65),begin.plusDays(84),enabledRadars=setOf(RadarType.F_VOLUME_CONTRACTION))
        val parts=folder.newFolder();var stop=false
        assertThrows(IllegalStateException::class.java){BtRobotIndex.open(stocks,settings,parts,"fixed",progress={if(it.contains("已保存"))stop=true},cancel={stop},chunkSize=1)}
        val visited=mutableSetOf<String>()
        val resumed=BtRobotIndex.open(stocks,settings,parts,"fixed",flowFor={code,_->visited+=code;null},chunkSize=1)
        assertEquals(setOf("2001","2002"),visited)
        val expected=BtConditionIndex.build(stocks,settings).prepare(settings)
        assertEquals(expected,resumed.prepare(settings))
        java.io.File(parts,"0.bin.gz").writeText("broken")
        visited.clear()
        assertEquals(expected,BtRobotIndex.open(stocks,settings,parts,"fixed",flowFor={code,_->visited+=code;null},chunkSize=1).prepare(settings))
        assertEquals(setOf("2000"),visited)
        assertThrows(IllegalStateException::class.java){resumed.prepare(settings,cancel={true})}
    }
    @Test fun marketSizedSnapshotReportsProgressAndCanStopDuringRead() {
        val source=BacktestData.Loaded(series(1346,580),1624,List(278){"excluded$it"})
        val file=folder.newFile("market.bin.gz");BtRobotDataset.write(file,source)
        var updates=0
        val loaded=BtRobotDataset.read(file,progress={updates++})
        assertEquals(source,loaded);assertTrue(updates>100)
        var stop=false
        assertThrows(IllegalStateException::class.java){BtRobotDataset.read(file,progress={stop=true},cancel={stop})}
    }
    @Test fun candleChecksAvoidRepeatedDatabaseReadsButForceValidationBeforeCommit() {
        var now=0L;var slow=0;var stopped=false;var invalid=false
        val check=RobotCancellation({stopped},{slow++;invalid},{now})
        repeat(100000){assertFalse(check.check())};assertEquals(1,slow)
        now=250;assertFalse(check.check());assertEquals(2,slow)
        invalid=true;assertTrue(check.check(force=true));assertEquals(3,slow)
        invalid=false;assertTrue(check.check())
        val immediate=RobotCancellation({stopped},{false},{now})
        assertFalse(immediate.check());stopped=true;assertTrue(immediate.check())
    }
}
