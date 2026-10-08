package com.rex.twboardingscanner.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import kotlin.math.sin

class ChartSeriesTest {
    private fun bars(n: Int)= (0 until n).map { i ->
        val p=100.0+i*.13+sin(i/7.0)*5
        DailyBar(LocalDate.of(2025,1,1).plusDays(i.toLong()).atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),p-1,p+2,p-2,p,1000000L+i)
    }
    @Test fun chartMacdMatchesScreeningWithFullWarmup() {
        val bars=bars(320)
        val plotted=ChartSeries.prepare(bars).last()
        val scanner=TechnicalCalculator().macd(bars.map{it.close})!!
        assertEquals(scanner.dif,plotted.dif!!,1e-10)
        assertEquals(scanner.signal,plotted.dea!!,1e-10)
        assertEquals(scanner.hist,plotted.histogram!!,1e-10)
        // Regression: calculating from the final 32 bars gave a different oscillator.
        assertTrue(kotlin.math.abs(ChartSeries.prepare(bars.takeLast(32)).last().dif!!-plotted.dif)>0.01)
    }
    @Test fun visibleWindowDoesNotChangeIndicatorValues() {
        val series=ChartSeries.prepare(bars(320))
        assertEquals(series.takeLast(30).last(),series.takeLast(250).last())
        assertEquals(series[290].ma20,series.takeLast(30).first().ma20)
    }
    @Test fun shortHistoryDoesNotPretendToHaveWarmedMacd() {
        val series=ChartSeries.prepare(bars(30))
        assertNull(series[28].dif);assertNotNull(series[29].dif)
        assertNull(series[3].ma5);assertNotNull(series[4].ma5)
        assertNull(series[18].ma20);assertNotNull(series[19].ma20)
    }
    @Test fun invalidBarsAndDuplicateDatesAreHandled() {
        val b=bars(40)
        val newer=b.last().copy(time=b.last().time+1000,close=b.last().close+.5)
        val series=ChartSeries.prepare((b+newer+b.first().copy(close=Double.NaN)).reversed())
        assertEquals(40,series.size);assertEquals(newer,series.last().bar)
        assertTrue(ChartSeries.prepare(listOf(b.first().copy(high=1.0))).isEmpty())
        assertTrue(ChartSeries.prepare(emptyList()).isEmpty())
    }
    @Test fun goodinfoLinkUsesClickedCode() {
        assertEquals("https://goodinfo.tw/tw/ShowK_Chart.asp?STOCK_ID=3661",ChartSeries.goodinfoUrl("3661"))
        assertTrue(ChartSeries.goodinfoUrl("2330").endsWith("=2330"))
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsNonStockCodeInLink() {
        ChartSeries.goodinfoUrl("3661&redirect=bad")
    }
}
