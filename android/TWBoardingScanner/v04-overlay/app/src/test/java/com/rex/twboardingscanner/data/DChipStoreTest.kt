package com.rex.twboardingscanner.data

import com.rex.twboardingscanner.domain.RuleMetrics
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class DChipStoreTest{
    private val header="date,code,bigHolderPct,mainForce5Pct,foreignNetShares,trustNetShares,asOf,source"

    @Test fun csvImportRetainsDatesSourceAndPercentagePointPrecision(){
        val context=RuntimeEnvironment.getApplication()
        val store=DChipStore(context)
        val csv="$header\n2026-10-08,6412,45.12,8.40,-200000,,13:30:00,licensed-export\n"+
            "2026-10-09,6412,45.13,8.55,120000,-8000,13:30:00,licensed-export"
        val records=DChipStore.parseCsv(csv,LocalDate.of(2026,10,12),LocalTime.of(14,0))
        assertEquals(2,records.size)
        assertEquals(2,store.importCsv(csv.byteInputStream()))
        val today=store.read("6412",LocalDate.of(2026,10,9),completed=true)!!
        assertEquals(45.13,today.bigHolderPct!!,0.000000001)
        assertEquals(8.55,today.mainForce5Pct!!,0.000000001)
        assertEquals(120000L,today.foreignNetShares)
        assertEquals(-8000L,today.trustNetShares)
        assertEquals("licensed-export",today.source)
        assertEquals(2,store.forCode("6412",LocalDate.of(2026,10,8),LocalDate.of(2026,10,9)).size)
        val revision=store.revision()
        assertNotEquals("none",revision)
        store.importCsv("$header\n2026-10-09,6412,40.00,1.0,1,1,09:30:00,old-source".byteInputStream())
        assertEquals(today,store.read("6412",LocalDate.of(2026,10,9),completed=true))
    }

    @Test fun partialAndUnreliableImportsCannotTurnIntoValidDailyEvidence(){
        val context=RuntimeEnvironment.getApplication()
        val store=DChipStore(context)
        val row="$header\n2026-10-09,6413,,,25000,,10:00:00,trade-export"
        assertEquals(1,store.importCsv(row.byteInputStream()))
        assertNotNull(store.read("6413",LocalDate.of(2026,10,9)))
        assertNull(store.read("6413",LocalDate.of(2026,10,9),completed=true))
        val bad=listOf(
            "date,code,holder,mainForce5Pct,foreignNetShares,trustNetShares,asOf,source\n2026-10-09,2330,1,2,3,4,13:30:00,x",
            "$header\n2026-10-09,2330,101,2,3,4,13:30:00,x",
            "$header\n2026-10-09,2330,40,NaN,3,4,13:30:00,x",
            "$header\n2026-10-09,2330,40,2,hello,4,13:30:00,x",
            "$header\n2026-10-09,2330,40,2,3,4,14:00:00,x",
            "$header\n2026-10-09,0000,40,2,3,4,13:30:00,x",
            "$header\n2026-10-09,2330,,,,,13:30:00,x",
            "$header\n2026-10-09,2330,40,2,3,4,13:30:00,"
        )
        bad.forEach{csv->assertThrows(Exception::class.java){
            DChipStore.parseCsv(csv,LocalDate.of(2026,10,12),LocalTime.of(14,0))
        }}
        assertThrows(Exception::class.java){
            DChipStore.parseCsv("$header\n2026-10-12,2330,40,2,3,4,13:30:00,x",
                LocalDate.of(2026,10,12),LocalTime.of(9,45))
        }
    }
}
