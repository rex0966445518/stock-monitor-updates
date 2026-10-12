package com.rex.twboardingscanner.ui

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ChipKLauncherTest {
    @Test fun selectedHoldingTargetsChipKAndKeepsItsStockCode() {
        for(code in listOf("3264","3362","6141","6188","8210","3029")) {
            val intent=ChipKLauncher.stockIntent(code)
            assertEquals(Intent.ACTION_VIEW,intent.action)
            assertEquals("com.cmoney.chipk",intent.`package`)
            assertEquals("https://www.cmoney.tw/finance/$code/stockmainkline",intent.data.toString())
        }
    }
    @Test(expected=IllegalArgumentException::class) fun rejectInvalidStockCode() {
        ChipKLauncher.stockIntent("../2330")
    }
}
