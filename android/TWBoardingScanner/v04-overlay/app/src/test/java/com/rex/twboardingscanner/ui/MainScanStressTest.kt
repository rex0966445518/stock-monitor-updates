package com.rex.twboardingscanner.ui

import android.os.Looper
import android.widget.TextView
import com.rex.twboardingscanner.R
import com.rex.twboardingscanner.data.*
import com.rex.twboardingscanner.domain.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class MainScanStressTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun clear(){
        app.deleteDatabase("scanner-folders-v1.db")
        app.getSharedPreferences("paper_trading_v1",0).edit().clear().commit()
        app.getSharedPreferences("scanner_filters",0).edit().clear().commit()
    }
    @Test fun completeOfflineMarketRunsThroughActivityPersistenceAndResultCards(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val host=controller.get()
        val today=LocalDate.now(RuleMetrics.TAIPEI)
        val stocks=(1000 until 3000).map{code->MarketStock(code.toString(),"測試$code",Market.TWSE,StockSector.SEMICONDUCTOR,100.0,105.0,99.0,104.0,4.0,1000,null,null)}
        val bars=(0 until 320).map{i->DailyBar(today.minusDays((320-i).toLong()).atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),100.0,105.0,99.0,104.0,1000000)}
        val auctionCalls=java.util.concurrent.atomic.AtomicInteger(0)
        host.scanInput=object:MainScanInput {
            override fun auction(stock:MarketStock):ClosingAuction { auctionCalls.incrementAndGet();return ClosingAuction(note="離線fixture") }
            override fun universe(progress:(String)->Unit)=stocks
            override fun history(stock:MarketStock)=bars
            override fun guard()=MarketCrashGuard.unknown("離線測試")
        }
        fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}
        field("radarEnabled").set(host,RadarType.entries.toSet())
        field("radarSelections").set(host,RadarType.entries.associateWith{setOf("price","volume")}.toMutableMap())
        field("scanExclusions").set(host,ExclusionConditions.empty())
        (field("financialCards").get(host) as FinancialCardLoader).close()
        val executor=field("coordinator").get(host) as ExecutorService
        MainActivity::class.java.getDeclaredMethod("startFullScan").apply{isAccessible=true}.invoke(host)
        executor.submit{}.get(120,TimeUnit.SECONDS)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(field("isScanning").getBoolean(host))
        @Suppress("UNCHECKED_CAST")
        val results=field("latest").get(host) as List<SignalResult>
        assertEquals(2000*RadarType.entries.size,results.size)
        if(java.time.LocalTime.now(RuleMetrics.TAIPEI)>=java.time.LocalTime.of(13,33))assertEquals(2000,auctionCalls.get())
        ScanArchiveStore(app).use{db->assertEquals(results,db.latestSaved()!!.main)}
        val content=host.findViewById<android.view.View>(android.R.id.content)
        content.measure(android.view.View.MeasureSpec.makeMeasureSpec(720,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(1600,android.view.View.MeasureSpec.EXACTLY))
        content.layout(0,0,720,1600)
        assertTrue(host.findViewById<TextView>(R.id.progressTitle).text.toString().contains("掃描"))
        controller.pause().stop().destroy()
    }
    @Test fun unexpectedSourceFailureEndsScanAndLeavesRetryAvailable(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val host=controller.get()
        host.scanInput=object:MainScanInput {
            override fun universe(progress:(String)->Unit):List<MarketStock> = throw IllegalStateException("fixture failure")
            override fun history(stock:MarketStock)=emptyList<DailyBar>()
            override fun guard()=MarketCrashGuard.unknown("離線測試")
        }
        val field=MainActivity::class.java.getDeclaredField("coordinator").apply{isAccessible=true}
        MainActivity::class.java.getDeclaredMethod("startFullScan").apply{isAccessible=true}.invoke(host)
        (field.get(host) as ExecutorService).submit{}.get(10,TimeUnit.SECONDS)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(host.findViewById<android.view.View>(R.id.startScanButton).isEnabled)
        assertTrue(host.findViewById<TextView>(R.id.progressTitle).text.contains("掃描中斷"))
        assertTrue(ScanDiagnostics.read(host).contains("fixture failure"))
        controller.pause().stop().destroy()
    }
}
