package com.rex.twboardingscanner.backtest
import com.rex.twboardingscanner.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
class AVideoBacktestParityTest {
    private val start=LocalDate.of(2025,1,1)
    private fun b(i:Int,o:Double=99.0,h:Double=100.0,l:Double=98.0,c:Double=99.0,v:Long=1000000)=DailyBar(start.plusDays(i.toLong()).atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),o,h,l,c,v)
    private fun series()=listOf(BtSeries("2330","fixture",Market.TWSE,List(100){i->b(i,v=if(i>=95)400000 else 1000000)}+
        b(100,99.5,102.0,99.0,101.5,2000000)+b(101,101.2,101.4,99.7,100.4,800000)+
        b(102,100.5,103.0,100.3,102.5,1600000)+b(103,102.5,104.0,102.0,103.5,1500000)))
    @Test fun indexedAndDirectBacktestsUseIdenticalPointInTimeRulesAndEvidence(){
        for(preset in listOf(APattern.PREPARE,APattern.CONFIRMED,APattern.PULLBACK,setOf("price","volume"))) {
            val settings=BtSettings(start,start.plusDays(103),rules=RadarType.entries.associateWith{if(it==RadarType.A_EARLY_BREAKOUT)preset else emptySet()},enabledRadars=setOf(RadarType.A_EARLY_BREAKOUT))
            val direct=BacktestEngine.prepare(series(),settings)
            val index=BtConditionIndex.build(series(),settings)
            val indexed=index.prepare(settings)
            assertEquals(direct.signals,indexed.signals)
            if(preset==APattern.CONFIRMED) {
                assertTrue(direct.signals.isNotEmpty())
                val first=direct.signals.entries.first()
                assertEquals(start.plusDays(101),first.key) // A never consumes the execution day's close
                assertEquals(start.plusDays(100),first.value.first().dataDate)
                assertTrue(first.value.first().dEvidence.contains("固定平台 100.0"))
            }
            val file=File.createTempFile("a-video-index",".gz")
            try {
                val fingerprint=BtConditionIndex.fingerprint("fixture",settings)
                index.save(file,fingerprint)
                assertEquals(indexed.signals,BtConditionIndex.read(file,series(),fingerprint).prepare(settings).signals)
            } finally { file.delete() }
        }
    }
}
