package com.rex.twboardingscanner.ui

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class WantGooInlineChartTest {
    @Test fun holdingCodesKeepTheirOwnChartAndNavigationStaysOnSource() {
        for(code in listOf("2327","3264","3362","6141")) {
            assertEquals("https://www.wantgoo.com/stock/$code/technical-chart",WantGooInlineChartView.url(code))
            assertTrue(WantGooInlineChartView.isChartUrl(WantGooInlineChartView.url(code),code))
            assertFalse(WantGooInlineChartView.isChartUrl("https://www.wantgoo.com/stock/9999/technical-chart",code))
            assertFalse(WantGooInlineChartView.isChartUrl("https://www.wantgoo.com.evil.test/stock/$code/technical-chart",code))
            assertFalse(WantGooInlineChartView.isChartUrl("http://www.wantgoo.com/stock/$code/technical-chart",code))
        }
    }
    @Test fun offscreenCardsDoNotCreateBrowserOrMakeRequests() {
        val view=WantGooInlineChartView(RuntimeEnvironment.getApplication(),"3264")
        view.setActive(false)
        assertEquals(0,(view.getChildAt(1) as android.widget.FrameLayout).childCount)
        view.dispose()
    }
    @Test(expected=IllegalArgumentException::class) fun invalidCodeCannotChangeUrl() {
        WantGooInlineChartView.url("2327/../member")
    }
}
