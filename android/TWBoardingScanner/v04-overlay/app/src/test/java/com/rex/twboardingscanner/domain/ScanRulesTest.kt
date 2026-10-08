package com.rex.twboardingscanner.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ScanRulesTest {
    private val calc = TechnicalCalculator()
    private val stock = MarketStock("2330", "測試", Market.TWSE, StockSector.SEMICONDUCTOR, 999.0, 999.0, 999.0, 999.0, 99.0, 9999, 99.0, 0.0)
    private fun bars(closes: List<Double>, volumes: List<Long> = List(closes.size) { 1000000L }): List<DailyBar> = closes.mapIndexed { i, close ->
        DailyBar(LocalDate.of(2025, 1, 1).plusDays(i.toLong()).atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli(), close, close + 1, close - 1, close, volumes[i])
    }
    private fun metrics(b: List<DailyBar>, metadata: MarketStock = stock) = RuleMetrics(calc.build(metadata, b))
    private fun rule(type: RadarType, id: String, b: List<DailyBar>): Boolean? = ScanConditions.forRadar(type).single { it.id == id }.test(metrics(b))

    @Test fun exactCataloguesReplaceOldRules() {
        val a = ScanConditions.forRadar(RadarType.A_EARLY_BREAKOUT)
        val b = ScanConditions.forRadar(RadarType.B_DEEP_REVERSAL)
        val c = ScanConditions.forRadar(RadarType.C_LONG_RED_VOLUME)
        assertEquals(11, a.count { !it.extra }); assertEquals(11, b.count { !it.extra }); assertEquals(7, c.count { !it.extra })
        for (list in listOf(a,b,c)) {
            assertEquals(5, list.count { it.extra }); assertTrue(list.all { it.defaultEnabled == !it.extra })
            assertEquals(list.size, list.map { it.id }.distinct().size)
        }
        assertFalse(c.any { it.id == "macd" || it.id == "heat" })
        assertFalse(b.any { it.id == "rsi" || it.id == "ma20" })
    }
    @Test fun taipeiDayCutoffIncludesOnlyPriorDaysForAB() {
        val input = bars(List(42) { 100.0 })
        val today = RuleMetrics.tradingDate(input.last().time)
        assertEquals(41, calc.barsForRadar(input, RadarType.A_EARLY_BREAKOUT, today).size)
        assertEquals(41, calc.barsForRadar(input, RadarType.B_DEEP_REVERSAL, today).size)
        assertEquals(42, calc.barsForRadar(input, RadarType.C_LONG_RED_VOLUME, today).size)
        assertEquals(42, calc.barsForRadar(input, RadarType.A_EARLY_BREAKOUT, today.plusDays(1)).size)
        assertEquals(41, calc.barsForRadar(input, RadarType.C_LONG_RED_VOLUME, today.minusDays(1)).size)
    }
    @Test fun snapshotUsesTriggerNotUnrelatedLiveQuote() {
        val input = bars(List(30) { 60.0 } + 63.0)
        val s = calc.build(stock, input)
        assertEquals(63.0, s.price, 0.0); assertEquals(5.0, s.changePct, 1e-9)
        assertEquals(1000, s.volumeLots)
    }
    @Test fun priceAndVolumeIncludeEquality() {
        val input = bars(List(21) { 50.0 }, List(21) { 500000L })
        assertEquals(true, rule(RadarType.A_EARLY_BREAKOUT,"price",input))
        assertEquals(true, rule(RadarType.A_EARLY_BREAKOUT,"volume",input))
        assertEquals(false, rule(RadarType.A_EARLY_BREAKOUT,"price", input.dropLast(1)+input.last().copy(close=49.99)))
        assertEquals(false, rule(RadarType.A_EARLY_BREAKOUT,"volume", input.dropLast(1)+input.last().copy(volumeShares=499999)))
    }
    @Test fun volumeDenominatorExcludesTriggerAndRequires20PreviousBars() {
        val input = bars(List(21) { 60.0 }, List(20) { 1000000L } + 2000000L)
        assertEquals(true, metrics(input).volumeAtLeast(2.0))
        assertEquals(false, metrics(input.dropLast(1)+input.last().copy(volumeShares=1999999)).volumeAtLeast(2.0))
        assertNull(metrics(input.drop(1)).volumeAtLeast(2.0))
        assertEquals(1000.0, calc.build(stock, input).avg20VolumeLots, 0.0)
    }
    @Test fun consolidationUsesNineAndTenBeforeTrigger() {
        val input = bars(List(20) { 60.0 }, List(10) { 2000000L }+List(9) {1000000L}+999999999L)
        assertEquals(true, metrics(input).consolidationContracting)
        assertEquals(false, metrics(bars(List(20){60.0})).consolidationContracting)
        assertNull(metrics(input.drop(1)).consolidationContracting)
    }
    @Test fun breakoutUses19ClosingPricesAndStrictGreater() {
        val input = bars(List(19) { 100.0 } + 101.0).map { it.copy(high=1000.0) }
        assertEquals(true, metrics(input).breakout)
        assertEquals(false, metrics(input.dropLast(1)+input.last().copy(close=100.0)).breakout)
        assertNull(metrics(input.drop(1)).breakout)
    }
    @Test fun floorAndHigherLowUseClosesNotWicks() {
        val input = bars(List(20){90.0}+List(10){100.0}+List(10){101.0}).mapIndexed { i,b -> b.copy(low=if(i>20) 1.0 else 80.0) }
        assertEquals(true, metrics(input).floorHeld); assertEquals(true, metrics(input).higherLow)
        assertEquals(true, metrics(bars(List(40){100.0})).floorHeld)
        assertEquals(false, metrics(bars(List(40){100.0})).higherLow)
        assertEquals(false, metrics(bars(List(20){100.0}+List(20){99.99})).floorHeld)
        assertNull(metrics(input.drop(1)).floorHeld)
    }
    @Test fun drawdownUsesOnlyLatest252ClosesAndThirtyPercentBoundary() {
        assertEquals(true, metrics(bars(List(251){100.0}+70.0)).drawdown)
        assertEquals(false, metrics(bars(listOf(1000.0)+List(251){100.0}+70.01)).drawdown)
        assertEquals(true, metrics(bars(listOf(100.0,70.0))).drawdown)
    }
    @Test fun declineVolumeRequiresDownDaysInBothWindows() {
        val closes = (0..20).map { if (it%2==0) 100.0 else 99.0 }
        val input = bars(closes, List(11){2000000L}+List(10){1000000L})
        assertEquals(true, metrics(input).declineContracting)
        assertEquals(false, metrics(bars(closes)).declineContracting)
        assertEquals(false, metrics(bars((0..20).map{100.0+it})).declineContracting)
        assertNull(metrics(input.drop(1)).declineContracting)
    }
    @Test fun convergenceAndStrictMovingAverageRules() {
        assertEquals(true, metrics(bars(List(15){100.0}+List(5){104.0})).converging)
        assertEquals(false, metrics(bars(List(15){100.0}+List(5){105.0})).converging)
        val flat = metrics(bars(List(40){100.0}))
        assertEquals(false,flat.aboveThree);assertEquals(false,flat.aboveMa5);assertEquals(false,flat.ma5Up)
        assertEquals(false,flat.macdUp)
    }
    @Test fun macdRequiresBothDifAndHistogramImprovement() {
        val slow = bars(List(40){50.0} + listOf(55.0,60.0,65.0,65.0,65.0,65.0))
        val state = calc.macd(slow.map { it.close })!!
        assertTrue(state.difRising); assertFalse(state.histRising)
        assertEquals(false, metrics(slow).macdUp)
        assertEquals(true,metrics(bars(List(40){50.0}+listOf(51.0,52.0,53.0))).macdUp)
    }
    @Test fun heatDoesNotRejectLargeNegativeDistance() {
        assertEquals(true,metrics(bars(List(39){100.0}+80.0)).notHot)
        assertEquals(false,metrics(bars(List(39){100.0}+116.0)).notHot)
    }
    @Test fun cPassesWithoutMacdOrABHeatAndExtraGatesAreOptional() {
        val input = bars(List(20){50.0}+53.0, List(20){1000000L}+2000000L)
            .mapIndexed { i,b -> if(i==20) b.copy(open=51.0,high=53.0,low=50.0) else b }
        val snapshot = calc.build(stock,input)
        val result = ScoringEngine().evaluateC(snapshot)
        assertNotEquals(SignalLight.NONE,result.light)
        assertTrue(result.checks.filter { it.extra && it.id in setOf("extra_eps","extra_flow") }.all { it.state==CheckState.PENDING && !it.selected })
        val defaults = ScanConditions.forRadar(RadarType.C_LONG_RED_VOLUME).filter {it.defaultEnabled}.map{it.id}.toSet()
        assertEquals(SignalLight.NONE, ScoringEngine().evaluateC(snapshot,defaults+"extra_eps").light)
        assertEquals(SignalLight.NONE, ScoringEngine().evaluateC(snapshot,defaults+"extra_high").light)
        val weak = snapshot.copy(bars=input.dropLast(1)+input.last().copy(open=53.0))
        assertEquals(SignalLight.NONE, ScoringEngine().evaluateC(weak).light)
        assertNotEquals(SignalLight.NONE, ScoringEngine().evaluateC(weak, defaults-setOf("red","body")).light)
    }
    @Test fun cExactThreePercentAndTopQuarter() {
        val input=bars(List(20){100.0}+103.0).let { it.dropLast(1)+it.last().copy(open=100.0,low=100.0,high=104.0) }
        val m=metrics(input)
        assertEquals(true,m.closeHigh)
        assertEquals(true,rule(RadarType.C_LONG_RED_VOLUME,"body",input));assertEquals(true,rule(RadarType.C_LONG_RED_VOLUME,"gain",input))
        assertEquals(false,metrics(input.dropLast(1)+input.last().copy(close=102.99)).closeHigh)
    }
    @Test fun earningsRequireFourConsecutivePositiveQuartersNotPositiveSum() {
        val input=bars(List(40){60.0});val q=listOf(QuarterEps(2025,3,2.0),QuarterEps(2025,4,2.0),QuarterEps(2026,1,2.0),QuarterEps(2026,2,2.0))
        assertEquals(true,metrics(input,stock.copy(quarterlyEps=q)).earnings)
        assertEquals(false,metrics(input,stock.copy(quarterlyEps=q.dropLast(1)+q.last().copy(eps=-1.0))).earnings)
        assertNull(metrics(input,stock.copy(quarterlyEps=q.drop(1))).earnings)
        assertNull(metrics(input,stock.copy(quarterlyEps=q.dropLast(1)+q.last().copy(quarter=3))).earnings)
        assertNull(metrics(input,stock.copy(quarterlyEps=q,revenueYoY=null)).earnings)
    }
    @Test fun institutionNeedsCompleteMatchingFiveDatesAndThreeBuyingDays() {
        val input=bars(List(40){60.0});val dates=input.takeLast(5).map{RuleMetrics.tradingDate(it.time)}
        fun flows(v:List<Long>)=dates.zip(v).map{InstitutionDay(it.first,it.second)}
        val pass=flows(listOf(10,10,10,-5,-5))
        val fail=flows(listOf(100,0,0,-5,-5))
        assertEquals(true,metrics(input,stock.copy(trustDaily=pass)).institutions)
        assertEquals(false,metrics(input,stock.copy(foreignDaily=fail,trustDaily=fail)).institutions)
        assertNull(metrics(input,stock.copy(foreignDaily=fail,trustDaily=pass.drop(1))).institutions)
    }
    @Test fun optionalHighAndEstimatedLimitBoundaries() {
        val input=bars(List(29){100.0}+95.0).map{it.copy(high=100.0)}
        assertEquals(false,metrics(input).belowHigh)
        assertEquals(true,metrics(input.dropLast(1)+input.last().copy(close=94.99)).belowHigh)
        assertEquals(44.65,RuleMetrics.estimatedLimit(40.6),1e-9)
        assertEquals(110.0,RuleMetrics.estimatedLimit(100.0),0.0)
        assertEquals(false,metrics(bars(listOf(100.0,110.0))).belowLimit)
        assertEquals(true,metrics(bars(listOf(100.0,109.5))).belowLimit)
    }
    @Test fun bTechnicalUpgradeIsLabelNotBasicGate() {
        val rising=calc.build(stock,bars(List(39){60.0}+61.0))
        val flat=calc.build(stock,bars(List(40){60.0}))
        val engine=ScoringEngine()
        val a=engine.evaluateB(rising,setOf("price","volume"))
        val b=engine.evaluateB(flat,setOf("price","volume"))
        assertNotEquals(SignalLight.NONE,a.light); assertTrue(a.technicalUpgrade)
        assertNotEquals(SignalLight.NONE,b.light); assertFalse(b.technicalUpgrade)
    }
    @Test fun riskIsPendingWithoutValidOverheadResistance() {
        val input=bars(List(61){100.0}).map{it.copy(low=90.0,high=140.0)}
        assertTrue(metrics(input).rewardRisk!! >= 2)
        assertNull(metrics(input.map{it.copy(high=100.0)}).rewardRisk)
    }
}
