package com.rex.twboardingscanner.ui

import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.backtest.BtSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ScannerParticipationTest {
    private val sector=StockSector.SEMICONDUCTOR
    private val paper=BtSettings(LocalDate.of(2026,10,1),LocalDate.of(2026,10,30))
        .copy(enabledRadars=RadarType.entries.toSet(),sectors=setOf(sector))
    @Test fun all64SwitchCombinationsOnlyInvokeSelectedEnginesWithPausedPaperStrategy(){
        for(mask in 0 until (1 shl RadarType.entries.size)){
            val selected=RadarType.entries.filterIndexed{i,_->mask and (1 shl i)!=0}.toSet()
            val calls=mutableListOf<RadarType>()
            ScanParticipation(selected,setOf(sector),false,paper).evaluate(sector){calls.add(it);it}
            assertEquals("mask=$mask",selected,calls.toSet())
            assertEquals(selected.size,calls.size)
        }
    }
    private fun result(type:RadarType):SignalResult {
        val bars=(0..64).map{i->DailyBar(LocalDate.of(2026,8,1).plusDays(i.toLong()).atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),100.0,102.0,98.0,101.0,1000000L)}
        val stock=MarketStock("2330","測試",Market.TWSE,sector,100.0,102.0,98.0,101.0,1.0,1000,null,null)
        return SignalResult(stock.code,stock.name,sector,type,100,SignalLight.ORANGE,emptyList(),emptyList(),TechnicalCalculator().build(stock,bars))
    }
    @Test fun runningPaperResultsNeverLeakIntoEScannerResults(){
        val routing=ScanParticipation(setOf(RadarType.E_DIF_TOUCH),setOf(sector),true,paper)
        val all=routing.evaluate(sector){result(it)}
        assertEquals(7,all.size)
        assertEquals(listOf(RadarType.E_DIF_TOUCH),routing.mainResults(all).map{it.radarType})
        assertEquals(7,routing.paperResults(all).size)
        assertTrue(routing.evaluate(StockSector.UNKNOWN){it}.isEmpty())
    }
    @Test fun mainScreenFiltersStaleResultsAndInvalidatesRunningScan(){
        val ctl=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=ctl.get()
        fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}
        field("radarEnabled").set(activity,setOf(RadarType.E_DIF_TOUCH))
        field("latest").set(activity,RadarType.entries.map{result(it)})
        val admitted=MainActivity::class.java.getDeclaredMethod("admitted").apply{isAccessible=true}
        @Suppress("UNCHECKED_CAST")
        val shown=admitted.invoke(activity) as List<SignalResult>
        assertEquals(listOf(RadarType.E_DIF_TOUCH),shown.map{it.radarType})
        field("isScanning").set(activity,true)
        MainActivity::class.java.getDeclaredMethod("requestConfiguredScan").apply{isAccessible=true}.invoke(activity)
        assertTrue((field("latest").get(activity) as List<*>).isEmpty())
        assertEquals(true,field("restartScan").get(activity))
        assertEquals(1L,(field("scanRevision").get(activity) as java.util.concurrent.atomic.AtomicLong).get())
        ctl.pause().stop().destroy()
    }
}
