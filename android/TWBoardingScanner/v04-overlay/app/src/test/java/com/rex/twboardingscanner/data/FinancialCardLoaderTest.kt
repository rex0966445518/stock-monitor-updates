package com.rex.twboardingscanner.data

import com.rex.twboardingscanner.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FinancialCardLoaderTest {
    private fun full():FinancialReport {
        val today=LocalDate.now(RuleMetrics.TAIPEI)
        val current=today.year*4+(today.monthValue-1)/3
        val eps=(current-3..current).map{k->QuarterEps((k-1)/4,(k-1)%4+1,2.0)}
        val latest=eps.last()
        return FinancialReport(eps,operatingCash=listOf(PeriodValue(latest.year,latest.quarter,100.0)),operatingMargin=listOf(PeriodValue(latest.year,latest.quarter,12.0)),fetchedAt=System.currentTimeMillis())
    }
    @Test fun cachedCardDisplaysWhileAnotherStockNetworkIsBlockedAndDoesNotRequestNetwork(){
        val busy=CountDownLatch(1);val release=CountDownLatch(1);val cachedShown=CountDownLatch(1)
        val requests=AtomicInteger()
        val report=full()
        val loader=FinancialCardLoader({if(it=="2330")report else null},{_,_->requests.incrementAndGet();busy.countDown();release.await(5,TimeUnit.SECONDS);FinancialReport()},
            {code,r,loading->if(code=="2330"&&!loading&&r?.ttm(LocalDate.now(RuleMetrics.TAIPEI))==8.0)cachedShown.countDown()})
        try{
            loader.request("2317");assertTrue(busy.await(2,TimeUnit.SECONDS))
            loader.request("2330");assertTrue("cache waited behind network",cachedShown.await(2,TimeUnit.SECONDS));assertEquals(1,requests.get())
        }finally{release.countDown();loader.close()}
    }
    @Test fun duplicateRadarCardsShareOneRequestAndPartialEpsArrivesBeforeCash(){
        val started=CountDownLatch(1);val release=CountDownLatch(1);val epsShown=CountDownLatch(1);val completed=CountDownLatch(1)
        val calls=AtomicInteger();val report=full()
        val loader=FinancialCardLoader({null},{_,partial->calls.incrementAndGet();started.countDown();partial(report.copy(operatingCash=emptyList(),operatingMargin=emptyList()));release.await(5,TimeUnit.SECONDS);report},
            {_,r,loading->if(loading&&r?.eps?.isNotEmpty()==true&&r.operatingCash.isEmpty())epsShown.countDown();if(!loading)completed.countDown()})
        try{
            loader.request("2330");assertTrue(started.await(2,TimeUnit.SECONDS))
            repeat(6){loader.request("2330")}
            assertTrue(epsShown.await(2,TimeUnit.SECONDS));assertEquals(1,calls.get());assertEquals(1L,completed.count)
            release.countDown();assertTrue(completed.await(2,TimeUnit.SECONDS))
            loader.request("2330");assertEquals(1,calls.get())
        }finally{release.countDown();loader.close()}
    }
    @Test fun failureEndsLoadingAndPreservesAvailableMetrics(){
        val completed=CountDownLatch(1);var result:FinancialReport?=null
        val initial=full().copy(operatingCash=emptyList(),operatingMargin=emptyList())
        val loader=FinancialCardLoader({null},{_,_->error("HTTP 429")},{_,r,loading->if(!loading){result=r;completed.countDown()}})
        try{loader.request("2330",initial);assertTrue(completed.await(2,TimeUnit.SECONDS));assertEquals(initial.eps,result!!.eps);assertTrue(result!!.messages.single().contains("HTTP 429"))}finally{loader.close()}
    }
}
