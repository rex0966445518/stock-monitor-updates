package com.rex.twboardingscanner.ui

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ChipKNavigationTest{
    private fun apply(p:ChipKNavigation,nodes:List<ChipKNavigation.Node>):ChipKNavigation.Action{
        val a=p.next(nodes)!!;p.applied(a);return a
    }
    @Test fun navigationSearchesExactStockThenSelectsKlineAndDayOnly(){
        val p=ChipKNavigation("3264","欣銓")
        assertEquals(1,apply(p,listOf(ChipKNavigation.Node(1,"搜尋",clickable=true))).key)
        assertNull(p.next(listOf(ChipKNavigation.Node(1,"搜尋",clickable=true))))
        assertEquals("3264",apply(p,listOf(ChipKNavigation.Node(2,"股號／名稱","search_input",editable=true))).text)
        val candidates=listOf(ChipKNavigation.Node(3,"13264 欣銓",clickable=true),
            ChipKNavigation.Node(4,"3264 欣銓",clickable=true),ChipKNavigation.Node(5,"3264 別的公司",clickable=true))
        assertEquals(4,apply(p,candidates).key)
        assertEquals(6,apply(p,listOf(ChipKNavigation.Node(6,"K線",clickable=true))).key)
        assertEquals(7,apply(p,listOf(ChipKNavigation.Node(7,"日",clickable=true))).key)
        assertNull(p.next(emptyList()));assertTrue(p.complete)
    }
    @Test fun ignoresPasswordsOrderInputsAndTradeButtons(){
        val p=ChipKNavigation("2330","台積電")
        assertNull(p.next(listOf(ChipKNavigation.Node(1,"股號 交易密碼","search",editable=true,password=true),
            ChipKNavigation.Node(2,"買入股號","search",editable=true),
            ChipKNavigation.Node(3,"搜尋 下單",clickable=true))))
        assertEquals("2330",apply(p,listOf(ChipKNavigation.Node(4,"搜尋股號","search_input",editable=true))).text)
        assertNull(p.next(listOf(ChipKNavigation.Node(5,"2330 台積電 賣出",clickable=true))))
        assertEquals(6,apply(p,listOf(ChipKNavigation.Node(6,"2330 台積電",clickable=true))).key)
        val day=ChipKNavigation.Node(7,"日K",clickable=true,selected=true)
        assertNull(p.next(listOf(day)));assertTrue(p.complete)
    }
    @Test fun missingAppNeverOpensBrowserAndRequestsExpire(){
        val app=RuntimeEnvironment.getApplication()
        ChipKLauncher.open(app,"3264","欣銓")
        assertNull(Shadows.shadowOf(app).nextStartedActivity)
        ChipKSearchRequest.put(app,"3264","欣銓")
        assertEquals("3264",ChipKSearchRequest.get(app)!!.code)
        app.getSharedPreferences("chipk-search-request",0).edit().putLong("at",System.currentTimeMillis()-121000).commit()
        assertNull(ChipKSearchRequest.get(app))
    }
    @Test fun mainScanCardAndMiniChartLaunchTheSelectedChipKStock(){
        val app=RuntimeEnvironment.getApplication()
        val ri=android.content.pm.ResolveInfo().apply{isDefault=true;activityInfo=android.content.pm.ActivityInfo().apply{
            packageName=ChipKLauncher.PACKAGE;name="com.cmoney.chipk.TestActivity";exported=true
            applicationInfo=android.content.pm.ApplicationInfo().apply{packageName=ChipKLauncher.PACKAGE}
        }}
        Shadows.shadowOf(app.packageManager).addResolveInfoForIntent(ChipKLauncher.stockIntent("3264"),ri)
        val ctl=org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup();val a=ctl.get()
        val f=MainActivity::class.java.getDeclaredField("adapter").apply{isAccessible=true}
        val adapter=f.get(a) as SignalAdapter
        val stock=com.rex.twboardingscanner.domain.MarketStock("3264","欣銓",com.rex.twboardingscanner.domain.Market.TPEX,
            com.rex.twboardingscanner.domain.StockSector.SEMICONDUCTOR,100.0,103.0,99.0,102.0,2.0,1200,null,null)
        val bar=com.rex.twboardingscanner.domain.DailyBar(System.currentTimeMillis(),100.0,103.0,99.0,102.0,1200000)
        val result=com.rex.twboardingscanner.domain.SignalResult("3264","欣銓",stock.sector,
            com.rex.twboardingscanner.domain.RadarType.C_LONG_RED_VOLUME,100,com.rex.twboardingscanner.domain.SignalLight.ORANGE,
            emptyList(),emptyList(),com.rex.twboardingscanner.domain.TechnicalCalculator().build(stock,List(65){bar.copy(time=bar.time-it*86400000L)}))
        adapter.submit(listOf(result))
        val holder=adapter.onCreateViewHolder(android.widget.FrameLayout(a),0);adapter.onBindViewHolder(holder,0)
        holder.itemView.performClick()
        val first=Shadows.shadowOf(a).nextStartedActivity
        assertEquals(ChipKLauncher.PACKAGE,first.`package`);assertEquals(ChipKLauncher.stockIntent("3264").data,first.data)
        holder.itemView.findViewById<android.view.View>(com.rex.twboardingscanner.R.id.chartView).performClick()
        assertEquals(ChipKLauncher.PACKAGE,Shadows.shadowOf(a).nextStartedActivity.`package`)
        ctl.pause().stop().destroy()
    }
}
