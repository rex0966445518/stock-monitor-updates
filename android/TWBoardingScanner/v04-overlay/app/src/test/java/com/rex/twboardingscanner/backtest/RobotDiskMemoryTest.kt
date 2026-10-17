package com.rex.twboardingscanner.backtest

import com.rex.twboardingscanner.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.time.LocalDate

class RobotDiskMemoryTest {
    @get:Rule val folder=TemporaryFolder()
    private val begin=LocalDate.of(2023,1,1)
    private fun bars(n:Int)=List(n){i->DailyBar(begin.plusDays(i.toLong()).atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),99.0,102.0,98.0,100.0+i%3,1000000L+i)}
    @Test fun diskRowsAndDateLookupsPreserveExactPricesAndWarmup(){
        val original=bars(1100);val disk=DiskDailyBars.write(folder.newFile(),original)
        assertEquals(original,disk)
        listOf(0,255,256,511,512,1099).forEach{assertEquals(original[it],disk[it])}
        assertThrows(IndexOutOfBoundsException::class.java){disk[-1]}
        assertThrows(IndexOutOfBoundsException::class.java){disk[1100]}
        val source=listOf(BtSeries("2330","fixture",Market.TWSE,original))
        val backed=listOf(source[0].copy(bars=disk))
        val start=begin.plusDays(600);val end=begin.plusDays(1099)
        val expected=BacktestEngine.executionIndex(source,start,end)
        val actual=BacktestEngine.executionIndex(backed,start,end)
        assertEquals(expected.bars,actual.bars);assertEquals(expected.previous,actual.previous)
        assertEquals(original[599].close,actual.previous.getValue("2330").getValue(start),0.0)
        assertNull(actual.bars.getValue("2330")[start.minusDays(1)])
        assertTrue(actual.bars.getValue("2330") is DiskBarDateMap<*>)
        assertTrue(DiskDailyBars.cachedBytes()<=98304)
    }
    @Test fun resumedFullMarketDoesNotMaterializeOnePointSevenMillionCandles(){
        val shared=bars(1100)
        val source=BacktestData.Loaded(List(1614){i->BtSeries("${2000+i}","fixture$i",Market.TWSE,shared)},1614,emptyList())
        val file=folder.newFile();BtRobotDataset.write(file,source)
        val resumed=BtRobotDataset.read(file,diskBacked=true)
        assertEquals(1614,resumed.series.size)
        assertTrue(resumed.series.all{it.bars is DiskDailyBars&&it.bars.size==1100})
        assertEquals(shared[1000],resumed.series[1260].bars[1000])
        val execution=BacktestEngine.executionIndex(resumed.series,begin.plusDays(600),begin.plusDays(1099))
        assertTrue(execution.bars.values.all{it is DiskBarDateMap<*>})
        assertTrue(DiskDailyBars.cachedBytes()<=98304)
        var stop=false
        assertThrows(IllegalStateException::class.java){BtRobotDataset.read(file,progress={stop=true},cancel={stop},diskBacked=true)}
        assertEquals(shared[1099],BtRobotDataset.read(file,diskBacked=true).series.last().bars.last())
    }
    @Test fun compressedDaySignalsKeepEveryChunkAndLongEvidence(){
        val builder=DiskSignals.Builder(folder.newFolder());val day=begin.plusDays(20)
        val evidence="突破回踩".repeat(12000)
        val first=BtSignal("2330","D/F",day.minusDays(1),evidence)
        val second=BtSignal("2317","F",day.minusDays(1),"第二段")
        builder.append(mapOf(day to listOf(first)));builder.append(mapOf(day to listOf(second)))
        val disk=builder.finish()
        assertEquals(listOf(first,second),disk[day]);assertNull(disk[day.plusDays(1)])
        assertEquals(mapOf(day to listOf(first,second)),disk)
        disk.dispose()
    }
    @Test fun cappedNonTradeDetailsNeverChangeAccountingTradesOrDailySkipCounts(){
        val series=List(8){i->BtSeries("${2000+i}","fixture$i",Market.TWSE,bars(25))}
        val days=List(25){begin.plusDays(it.toLong())}
        val signals=days.associateWith{day->series.map{BtSignal(it.code,"A",day)}}
        val prepared=BtPrepared(series,signals,days)
        val settings=BtSettings(begin,days.last(),capital=5000000.0,maxHoldingStocks=1)
        val full=BacktestEngine.run(prepared,settings)
        val bounded=BacktestEngine.run(prepared,settings,skippedDetailLimit=5)
        assertEquals(full.profit,bounded.profit,0.0);assertEquals(full.trades,bounded.trades)
        assertEquals(full.curve,bounded.curve);assertEquals(full.holdings,bounded.holdings)
        assertEquals(full.skipped.size,bounded.skippedCount)
        assertEquals(5,bounded.skipped.size);assertTrue(bounded.omittedSkipped>0)
    }
}

/** Also runnable in a separate JVM with -Xmx64m to check the full-market storage path. */
object RobotLowMemoryProbe {
    @JvmStatic fun main(args:Array<String>) {
        val directory=File(args.single()).apply{mkdirs()};val begin=LocalDate.of(2023,1,1)
        val shared=List(1100){i->DailyBar(begin.plusDays(i.toLong()).atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),99.0,102.0,98.0,100.0,1000000L+i)}
        val source=BacktestData.Loaded(List(1614){i->BtSeries("${2000+i}","fixture$i",Market.TWSE,shared)},1614,emptyList())
        val file=File(directory,"market.gz");BtRobotDataset.write(file,source)
        val data=BtRobotDataset.read(file,diskBacked=true)
        val execution=BacktestEngine.executionIndex(data.series,begin.plusDays(600),begin.plusDays(1099))
        check(data.series.size==1614&&execution.bars.size==1614)
        check(data.series[1260].bars.last()==shared.last())
        check(DiskDailyBars.cachedBytes()<=98304)
        println("PASS 1614 stocks / 1775400 candles / disk bars and lazy execution / max heap ${Runtime.getRuntime().maxMemory()}")
    }
}
