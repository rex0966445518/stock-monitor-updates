package com.rex.twboardingscanner.ui

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ChipKSevenPageStoreTest {
    private fun store() = ChipKSevenPageStore(RuntimeEnvironment.getApplication())

    @Test fun checkpointRetainsSuccessfulFilesAndSkipsUnreachablePage() {
        val s=store()
        val run=s.start("2330")
        assertTrue(run.active)
        assertEquals(0,run.index)
        assertEquals(7,run.pages.size)
        assertEquals(0,run.count)
        val page=ChipKPage.KLINE
        s.ready(0,"selected_tab")
        val file=s.fileFor(run,page)
        file.writeBytes(byteArrayOf(1,2,3))
        s.complete(0,file,"selected_tab")
        assertEquals(1,s.snapshot()?.count)
        assertEquals(1,s.snapshot()?.index)
        assertTrue(file.isFile)
        s.skip(1,"找不到籌碼日報")
        assertEquals(1,s.snapshot()?.failed)
        assertEquals(2,s.snapshot()?.index)
        assertTrue(File(s.dirFor(run),"manifest.json").readText().contains("找不到籌碼日報"))
        s.stop("test stop")
        assertFalse(s.snapshot()!!.active)
        assertTrue(file.isFile)
    }

    @Test fun completionMustFollowExactReadyIndexAndFolder() {
        val s=store()
        val run=s.start("3264")
        val file=s.fileFor(run,ChipKPage.KLINE)
        file.writeText("image")
        s.complete(0,file,"selected_tab")
        assertEquals(0,s.snapshot()?.count)
        s.ready(0,"other")
        s.complete(0,file,"other")
        assertEquals(0,s.snapshot()?.count)
        s.ready(0,"changed_unverified")
        s.complete(0,file,"changed_unverified")
        assertEquals("changed_unverified",s.snapshot()!!.pages[0].verification)
    }

    @Test(expected=IllegalArgumentException::class)
    fun invalidCodeRejected() {
        store().start("../../sensitive")
    }
}
