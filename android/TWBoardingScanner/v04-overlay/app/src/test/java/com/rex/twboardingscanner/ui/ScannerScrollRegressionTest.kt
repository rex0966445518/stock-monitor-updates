package com.rex.twboardingscanner.ui

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.RecyclerView
import com.rex.twboardingscanner.R
import com.rex.twboardingscanner.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.time.LocalDate

/** Regression for an enormous C-report blocking the Android scanner result list. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
class ScannerScrollRegressionTest {
    private fun fixture():SignalResult {
        val date=LocalDate.now(RuleMetrics.TAIPEI)
        val stock=MarketStock("6146","滑動測試",Market.TWSE,StockSector.ELECTRONICS,
            100.0,105.0,98.0,103.0,3.0,1383,null,1.0)
        val bars=(0..79).map { i ->
            val close=90.0+i*.2
            DailyBar(date.minusDays((80-i).toLong()).atStartOfDay(RuleMetrics.TAIPEI)
                .toInstant().toEpochMilli(),close-.2,close+.8,close-1,close,
                180000L+i*100)
        }
        return ScoringEngine().evaluateA(TechnicalCalculator().build(stock,bars),setOf("price","volume"))
            .copy(radarType=RadarType.C_LONG_RED_VOLUME)
    }
    private fun all(v:View):List<View> = listOf(v)+
        if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))} else emptyList()

    @Test fun longEvidenceCollapsesWithoutDroppingTheOriginalText() {
        val context=ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_TWBoardingScanner)
        val content=(1..130).joinToString("\n"){"C 判定條件 $it：MACD、RSI、成交量查核，僅觀察不保證獲利"}
        val a=SignalAdapter()
        a.submit(listOf(fixture().copy(dEvidence=content)))
        val h=a.onCreateViewHolder(LinearLayout(context),0)
        a.onBindViewHolder(h,0)
        val preview=h.b.dEvidence
        assertEquals(View.VISIBLE,preview.visibility)
        assertTrue(preview.text.length<260)
        assertEquals(5,preview.maxLines)
        assertTrue(preview.performClick())
        val dialog=ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        assertTrue(all(dialog.window!!.decorView).filterIsInstance<TextView>()
            .any{it.text.toString()==content})
        dialog.dismiss()
    }

    @Test fun financialCacheRefreshDoesNotRebindCardsDuringSwipe() {
        val context=ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_TWBoardingScanner)
        val a=SignalAdapter()
        a.submit(listOf(fixture()))
        var rebinds=0
        a.registerAdapterDataObserver(object:RecyclerView.AdapterDataObserver(){
            override fun onItemRangeChanged(positionStart:Int,itemCount:Int,payload:Any?){rebinds+=itemCount}
            override fun onItemRangeChanged(positionStart:Int,itemCount:Int){rebinds+=itemCount}
        })
        a.setScrolling(true)
        a.updateFinancial("6146",FinancialReport(fetchedAt=1L),false)
        assertEquals(0,rebinds)
        a.setScrolling(false)
        assertEquals(1,rebinds)
        // Different report after idle still refreshes the existing card.
        a.updateFinancial("6146",FinancialReport(fetchedAt=2L),false)
        assertEquals(2,rebinds)
    }

    @Test fun chartsDoNotScrubOrClaimVerticalGesturesInCompactMode() {
        val context=ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_TWBoardingScanner)
        val chart=MiniStockChartView(context)
        assertFalse(chart.detailed)
        val down=android.view.MotionEvent.obtain(0,0,android.view.MotionEvent.ACTION_DOWN,20f,20f,0)
        assertFalse(chart.onTouchEvent(down))
        down.recycle()
    }
}
