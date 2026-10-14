package com.rex.twboardingscanner.data

import com.rex.twboardingscanner.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ScanPipelineTest {
    private fun result():SignalResult {
        val stock=MarketStock("2449","京元電子",Market.TWSE,StockSector.SEMICONDUCTOR,100.0,104.0,99.0,103.0,3.0,2000,null,5.0)
        val bars=(0..20).map{i->DailyBar(LocalDate.of(2026,9,1).plusDays(i.toLong()).atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),100.0,104.0,99.0,if(i==20)103.0 else 100.0,if(i==20)2_000_000 else 1_000_000)}
        return ScoringEngine().evaluateC(TechnicalCalculator().build(stock,bars))
    }
    @Test fun optionalTechnicalFailureOrUnknownSkipsFinancialHttp() {
        val hit=result()
        assertEquals(setOf("每股盈餘"),FinancialDataProvider.neededTopics(hit,setOf("price","fin_ttm")))
        val fail=hit.copy(checks=hit.checks.map{if(it.id=="extra_high")it.copy(state=CheckState.FAIL)else it})
        assertTrue(FinancialDataProvider.neededTopics(fail,setOf("price","extra_high","fin_ttm")).isEmpty())
        val pending=hit.copy(checks=hit.checks.map{if(it.id=="extra_flow")it.copy(state=CheckState.PENDING)else it})
        assertTrue(FinancialDataProvider.neededTopics(pending,setOf("extra_flow","fin_ttm")).isEmpty())
        assertTrue(FinancialDataProvider.neededTopics(hit,setOf("price","volume")).isEmpty())
        assertTrue(FinancialDataProvider.neededTopics(hit,setOf("unknown_rule","fin_ttm")).isEmpty())
    }
    @Test fun paperAndScannerSelectionsAreIndependentAndFinancialPagesUnionIsMinimal() {
        val hit=result()
        val scanner=FinancialDataProvider.neededTopics(hit,setOf("price","volume"))
        val paper=FinancialDataProvider.neededTopics(hit,setOf("price","fin_cash"))
        assertEquals(setOf("現金流量表"),scanner+paper)
        val missingRevenue=hit.copy(snapshot=hit.snapshot.copy(sourceStock=hit.snapshot.sourceStock!!.copy(revenueYoY=null)))
        assertTrue(FinancialDataProvider.neededTopics(missingRevenue,setOf("price","extra_eps")).isEmpty())
        assertEquals(setOf("每股盈餘"),FinancialDataProvider.neededTopics(missingRevenue,setOf("price","fin_ttm")))
    }
    @Test fun slowFinancialWorkDoesNotBlockPriceProducersButCompletionWaits() {
        val executor=Executors.newSingleThreadExecutor()
        val waiter=Executors.newSingleThreadExecutor()
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val second=CountDownLatch(1)
        val queue=ScanEnrichmentQueue(executor)
        try {
            queue.submit{entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            // Another stock can finish its daily data and enqueue a follow-up immediately.
            queue.submit{second.countDown()}
            assertEquals(2,queue.pending)
            val completion=waiter.submit{queue.await()}
            assertFalse(completion.isDone)
            release.countDown();completion.get(3,TimeUnit.SECONDS)
            assertEquals(0L,second.count);assertEquals(0,queue.pending)
        } finally {release.countDown();executor.shutdownNow();waiter.shutdownNow()}
    }
}
