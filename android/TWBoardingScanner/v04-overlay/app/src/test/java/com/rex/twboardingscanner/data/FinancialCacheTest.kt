package com.rex.twboardingscanner.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FinancialCacheTest {
    @Test fun cachedReadIgnoresProviderNetworkMonitorAndRejectsExpiredPages(){
        val app=RuntimeEnvironment.getApplication();val provider=FinancialDataProvider(app)
        val dir=File(app.cacheDir,"histock_financial_v1").apply{mkdirs()}
        val cash=File(dir,"2889_現金流量表.html");cash.writeText(javaClass.getResource("/financial/cash-2889.html")!!.readText())
        val worker=Executors.newSingleThreadExecutor()
        try{
            synchronized(provider){
                val report=worker.submit<com.rex.twboardingscanner.domain.FinancialReport?>{provider.cached("2889")}.get(2,TimeUnit.SECONDS)
                assertNotNull(report);assertTrue(report!!.operatingCash.isNotEmpty())
            }
            cash.setLastModified(System.currentTimeMillis()-86_400_001)
            assertNull(provider.cached("2889"))
        }finally{cash.delete();worker.shutdownNow()}
    }
}
