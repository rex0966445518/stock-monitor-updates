package com.rex.twboardingscanner.data

import com.rex.twboardingscanner.domain.RuleMetrics
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ScanSpeedOptimizationTest {
    private fun timestamp(day:LocalDate,time:LocalTime)=day.atTime(time).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli()
    private val monday=LocalDate.of(2026,10,12)

    @Test fun intradayCacheExpiresAfterFiveMinutesAndOfflineCanReuse(){
        val trading=timestamp(monday,LocalTime.of(10,0))
        val later=timestamp(monday,LocalTime.of(18,0))
        assertEquals(300_000L,MarketDataProvider.historyTtlMs(trading))
        assertEquals(8L*60L*60_000L,MarketDataProvider.historyTtlMs(later))
        assertEquals(300_000L,MarketDataProvider.referenceTtlMs(
            "https://openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL",trading))
        assertEquals(6L*60L*60_000L,MarketDataProvider.referenceTtlMs(
            "https://openapi.twse.com.tw/v1/opendata/t187ap06_L_ci",trading))
    }

    @Test fun afterOpenDoesNotReusePremarketCacheButRecentIntradayIsOkay(){
        val before=timestamp(monday,LocalTime.of(8,59))
        val open=timestamp(monday,LocalTime.of(9,1))
        val tick=timestamp(monday,LocalTime.of(9,2))
        assertFalse(MarketDataProvider.cacheFresh(before,open,300_000L))
        assertTrue(MarketDataProvider.cacheFresh(open,tick,300_000L))
        assertFalse(MarketDataProvider.cacheFresh(open,tick+301_000L,300_000L))
        assertFalse(MarketDataProvider.cacheFresh(tick,open,300_000L))
    }

    @Test fun financialStatementsAreOnlyRequestedForSelectedFinancialRules(){
        assertEquals(emptySet<String>(),FinancialDataProvider.topicsForRuleIds(listOf(
            "price","volume","macd","extra_high","active_buy_sell_15","d_limit","d_gap")))
        assertEquals(setOf("每股盈餘"),FinancialDataProvider.topicsForRuleIds(listOf("extra_eps","fin_ttm","fin_growth")))
        assertEquals(setOf("現金流量表"),FinancialDataProvider.topicsForRuleIds(listOf("fin_cash")))
        assertEquals(setOf("利潤比率"),FinancialDataProvider.topicsForRuleIds(listOf("fin_margin")))
        assertEquals(FinancialDataProvider.ALL_TOPICS,
            FinancialDataProvider.topicsForRuleIds(listOf("fin_positive","fin_margin","fin_cash")))
    }

    @Test fun partialMarketCoverageMustNotBeLabeledFullMarket(){
        assertFalse(MarketDataProvider.UniverseCoverage(1083,0).complete)
        assertFalse(MarketDataProvider.UniverseCoverage(0,800).complete)
        assertTrue(MarketDataProvider.UniverseCoverage(1083,800).complete)
        assertTrue(MarketDataProvider.UniverseCoverage(1083,0).description.contains("上櫃 0"))
    }
}
