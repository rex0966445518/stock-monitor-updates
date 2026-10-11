package com.rex.twboardingscanner.ui

import android.app.Activity
import android.content.Intent
import android.widget.TextView
import com.rex.twboardingscanner.R
import com.rex.twboardingscanner.data.BacktestFiveMinuteRepository
import com.rex.twboardingscanner.domain.ChartSeries
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
class BacktestGoodinfoFallbackTest {
    @Test fun oldTradeOffersClearlyLabeledGoodinfoDailyChartWithoutPretendingToBeFiveMinute(){
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.Theme_TWBoardingScanner)
        val panel=BacktestFiveMinutePanel(activity,"2414","2026-08-11","SELL",53.10,
            "09:00","前一完整日線判斷；下一交易日開盤價模擬")
        val open=panel.findViewWithTag<TextView>("goodinfo-historical-daily")
        assertNotNull(open)
        assertTrue(open.isEnabled)
        assertTrue(open.text.toString().contains("日 K"))
        assertTrue(open.text.toString().contains("非 5 分 K"))
        open.performClick()
        val intent=Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW,intent.action)
        assertEquals(ChartSeries.goodinfoUrl("2414"),intent.data.toString())
        assertTrue(BacktestFiveMinuteRepository.retentionReason(LocalDate.parse("2026-08-11"),
            LocalDate.parse("2026-10-11"))!!.contains("Goodinfo"))
    }
    @Test fun invalidStockCodeCannotOpenUnknownWebsite(){
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.Theme_TWBoardingScanner)
        val panel=BacktestFiveMinutePanel(activity,"","2025-01-01","BUY",null,"13:30","")
        val open=panel.findViewWithTag<TextView>("goodinfo-historical-daily")
        assertNotNull(open);assertFalse(open.isEnabled)
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
    }
}
