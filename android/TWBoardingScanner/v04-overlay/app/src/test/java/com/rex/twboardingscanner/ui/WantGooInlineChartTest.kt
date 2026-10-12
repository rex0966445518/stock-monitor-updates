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
        val context=androidx.appcompat.view.ContextThemeWrapper(RuntimeEnvironment.getApplication(),com.rex.twboardingscanner.R.style.Theme_TWBoardingScanner)
        val view=WantGooInlineChartView(context,"3264")
        view.setActive(false)
        assertEquals(0,(view.findViewWithTag<android.widget.FrameLayout>("holding-chart-frame")).let{frame->(0 until frame.childCount).count{frame.getChildAt(it) is android.webkit.WebView}})
        view.dispose()
    }
    @Test fun temporaryVisibilityChangeKeepsLoadAndTimeoutShowsRealCandleFallback(){
        val context=androidx.appcompat.view.ContextThemeWrapper(RuntimeEnvironment.getApplication(),com.rex.twboardingscanner.R.style.Theme_TWBoardingScanner)
        val view=WantGooInlineChartView(context,"3264"){emptyList()}
        val frame=view.findViewWithTag<android.widget.FrameLayout>("holding-chart-frame")
        view.setActive(true)
        val first=(0 until frame.childCount).map{frame.getChildAt(it)}.filterIsInstance<android.webkit.WebView>().single()
        view.setActive(false)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        view.setActive(true)
        assertSame(first,(0 until frame.childCount).map{frame.getChildAt(it)}.filterIsInstance<android.webkit.WebView>().single())
        val bars=(0 until 80).map{i->com.rex.twboardingscanner.domain.DailyBar(
            java.time.LocalDate.of(2026,7,1).plusDays(i.toLong()).atStartOfDay(com.rex.twboardingscanner.domain.RuleMetrics.TAIPEI).toInstant().toEpochMilli(),
            100.0+i*.3,103.0+i*.3,98.0+i*.3,101.0+i*.3,1200000L+i*1000)
        }
        view.showNativeHistory(bars)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(19))
        assertEquals(0,(0 until frame.childCount).count{frame.getChildAt(it) is android.webkit.WebView})
        assertEquals(android.view.View.VISIBLE,view.findViewWithTag<MiniStockChartView>("holding-native-3264").visibility)
        assertTrue((view.findViewWithTag<android.widget.TextView>("holding-chart-status")).text.toString().contains("日線備援"))
        assertEquals(420,(frame.layoutParams.height/context.resources.displayMetrics.density).toInt())
        // Keep the real candles on later empty/error responses; never blank a previously drawn chart.
        view.showNativeHistory(emptyList())
        assertNotNull(view.findViewWithTag<MiniStockChartView>("holding-native-3264"))
        view.dispose()
        assertEquals(0,(0 until frame.childCount).count{frame.getChildAt(it) is android.webkit.WebView})
    }
    @Test(expected=IllegalArgumentException::class) fun invalidCodeCannotChangeUrl() {
        WantGooInlineChartView.url("2327/../member")
    }
}
