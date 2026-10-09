package com.rex.twboardingscanner.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import com.rex.twboardingscanner.data.HistockParser

class FinancialReportTest {
    private val today = LocalDate.of(2026,10,9)
    private val eps = listOf(QuarterEps(2025,3,16.4),QuarterEps(2025,4,18.28),QuarterEps(2026,1,17.55),QuarterEps(2026,2,20.01))
    @Test fun separatesPositiveSumFromPositiveEveryQuarter() {
        val good = FinancialReport(eps=eps)
        assertEquals(72.24, good.ttm(today)!!, 1e-8)
        assertEquals(true,good.fourPositive(today))
        val oneLoss=good.copy(eps=eps.dropLast(1)+eps.last().copy(eps=-1.0))
        assertTrue(oneLoss.ttm(today)!! > 0); assertEquals(false,oneLoss.fourPositive(today))
    }
    @Test fun rejectsMissingDuplicateStaleFutureAndNonfiniteEps() {
        assertNull(FinancialReport(eps=eps.drop(1)).ttm(today))
        assertNull(FinancialReport(eps=eps.dropLast(1)+eps[2]).ttm(today))
        assertNull(FinancialReport(eps=eps).ttm(today.plusYears(1)))
        assertNull(FinancialReport(eps=eps).ttm(LocalDate.of(2026,5,1)))
        assertNull(FinancialReport(eps=eps.dropLast(1)+eps.last().copy(eps=Double.NaN)).ttm(today))
    }
    @Test fun growthRequiresEightConsecutiveQuartersAndPositiveBase() {
        val old=eps.map { it.copy(year=it.year-1,eps=1.0) }
        assertEquals(true,FinancialReport(eps=old+eps).growth(today))
        assertNull(FinancialReport(eps=old.drop(1)+eps).growth(today))
        assertEquals(false,FinancialReport(eps=old.map { it.copy(eps=-1.0) }+eps).growth(today))
    }
    @Test fun annualGrowthUsesOnlyCompleteConsecutiveYears() {
        val report=FinancialReport(annualEps=(2021..2025).associateWith { (it-2020).toDouble() } + (2026 to 0.1))
        assertEquals(true,report.stableYears(3,today)); assertEquals(true,report.stableYears(5,today))
        assertNull(report.copy(annualEps=report.annualEps-2024).stableYears(3,today))
        assertEquals(false,report.copy(annualEps=report.annualEps+(2024 to 9.0)).stableYears(3,today))
        assertNull(report.copy(annualEps=report.annualEps-2025-2026).stableYears(3,today))
    }
    @Test fun positiveEpsDoesNotTurnNegativeCashPositiveAndPeriodsStayIndependent() {
        val report=FinancialReport(eps=eps,operatingCash=listOf(PeriodValue(2026,2,-4112509.0)))
        assertEquals(true,report.fourPositive(today))
        assertTrue(report.latest(report.operatingCash,today)!!.value < 0)
        assertNull(report.latest(listOf(PeriodValue(2024,1,1.0)),today))
    }
    private fun html(topic:String,rows:String) = "<html><title>世芯-KY (3661) $topic</title><body><h1>$topic</h1><table>$rows</table></body></html>"
    private fun row(vararg cells:String) = "<tr>"+cells.joinToString(""){"<td>$it</td>"}+"</tr>"
    @Test fun parsesYearColumnsWithoutCountingCumulativeTotalAsAQuarter() {
        val raw=html("每股盈餘",row("季別/年度","2025","2026")+row("Q1","18.13","17.55")+row("Q2","16.37","20.01")+row("Q3","16.4","-")+row("Q4","18.28","-")+row("總計","69.18","37.56"))
        val (q,y)=HistockParser.eps(raw,"3661")
        assertEquals(6,q.size);assertEquals(setOf(2025),y.keys)
        assertEquals(72.24,FinancialReport(eps=q).ttm(today)!!,1e-8)
        assertTrue(runCatching { HistockParser.eps(raw,"2330") }.isFailure)
        assertTrue(runCatching { HistockParser.eps("<title>403 Forbidden</title>","3661") }.isFailure)
    }
    @Test fun cashParserUsesOperatingColumnAndPreservesSignNotNetCash() {
        val raw=html("現金流量表",row("年度/季別","投資現金流","營業現金流","淨現金流")+row("2026Q2","-3,887,923","-4,112,509","10,416,412")+row("2026Q1","-1","-","20"))
        val values=HistockParser.periodValues(raw,"3661","現金流量表","營業現金流")
        assertEquals(1,values.size);assertEquals(-4112509.0,values.single().value,0.0)
        assertTrue(runCatching { HistockParser.periodValues(raw,"3661","現金流量表","未知欄位") }.isFailure)
    }
    @Test fun financialCheckboxesGateAllThreeRadarsAndMissingEvidenceBlocks() {
        val stock=MarketStock("3661","測試",Market.TWSE,StockSector.SEMICONDUCTOR,60.0,61.0,59.0,60.0,0.0,1000,null,1.0,
            financials=FinancialReport(eps=eps,operatingCash=listOf(PeriodValue(2026,2,-1.0))))
        val bars=(1..40).map { DailyBar(it*86400000L,60.0,61.0,59.0,60.0,1000000) }
        val snap=TechnicalCalculator().build(stock,bars).copy(timestamp=today.atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli())
        val engine=ScoringEngine()
        for (type in RadarType.entries) {
            fun run(ids:Set<String>):SignalResult = when(type) {
                RadarType.A_EARLY_BREAKOUT -> engine.evaluateA(snap,ids)
                RadarType.B_DEEP_REVERSAL -> engine.evaluateB(snap,ids)
                RadarType.C_LONG_RED_VOLUME -> engine.evaluateC(snap,ids)
            }
            assertNotEquals(SignalLight.NONE,run(setOf("price","fin_positive")).light)
            assertEquals(SignalLight.NONE,run(setOf("price","fin_cash")).light)
            assertEquals(SignalLight.NONE,run(setOf("price","fin_margin")).light)
            assertEquals(true,RuleMetrics(snap).earnings)
        }
    }
}
