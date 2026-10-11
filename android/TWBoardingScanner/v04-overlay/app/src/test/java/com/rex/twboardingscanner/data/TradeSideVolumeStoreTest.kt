package com.rex.twboardingscanner.data

import com.rex.twboardingscanner.domain.*
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
class TradeSideVolumeStoreTest {
    @Test fun validatedSourcedImportsRejectFalseBidAskAndPreserveLatestSnapshot(){
        val day="2026-10-09";val header="date,code,tradeVolumeAtAsk,tradeVolumeAtBid,asOf,source"
        val sample="$header\n$day,2330,150000,100000,13:30:00,authorized-broker"
        val today=LocalDate.of(2026,10,12)
        val parsed=TradeSideVolumeStore.parseCsv(sample,today,LocalTime.of(14,0))
        val flow=parsed[today.minusDays(3) to "2330"]!!
        assertEquals(150000L,flow.buyAtAsk)
        assertEquals(100000L,flow.sellAtBid)
        assertEquals("authorized-broker",flow.source)
        listOf(
            "date,code,bidLots,askLots,asOf,source\n$day,2330,100,50,13:30:00,site",
            "$header\n$day,2330,-10,5,13:30:00,site",
            "$header\n$day,2330,10,5,14:30:00,site",
            "$header\n$day,2330,10,5,13:30:00,",
            "$header\n$day,notacode,10,5,13:30:00,site"
        ).forEach{bad->
            assertThrows(Exception::class.java){TradeSideVolumeStore.parseCsv(bad,today,LocalTime.of(14,0))}
        }
        val app=RuntimeEnvironment.getApplication()
        val store=TradeSideVolumeStore(app)
        assertEquals(1,store.importCsv(sample.byteInputStream()))
        val result=store.read("2330",today.minusDays(3),completed=true)
        assertNotNull(result)
        assertEquals(150000L,result!!.buyAtAsk)
        val previous=store.read("2330",today.minusDays(3))
        store.importCsv("$header\n$day,2330,99999,99000,13:00:00,authorized-broker".byteInputStream())
        assertEquals(previous,store.read("2330",today.minusDays(3)))
        assertNull(store.read("0000",today.minusDays(3)))
    }
    @Test fun inProgressCsvIsAllowedForLiveAndRejectedForArchivedDailyBacktests(){
        val store=TradeSideVolumeStore(RuntimeEnvironment.getApplication())
        val day=LocalDate.of(2026,10,9)
        val row="date,code,tradeVolumeAtAsk,tradeVolumeAtBid,asOf,source\n2026-10-09,2454,5000,2000,10:05:00,licensed-export"
        val count=store.importCsv(row.byteInputStream())
        assertEquals(1,count)
        assertNotNull(store.read("2454",day))
        assertNull(store.read("2454",day,completed=true))
    }
}
