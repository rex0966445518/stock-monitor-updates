package com.rex.twboardingscanner.ui

import com.rex.twboardingscanner.backtest.*
import com.rex.twboardingscanner.data.TwseCsvBackup
import com.rex.twboardingscanner.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
class AllSignalsAndDOptionsTest {
    private fun result(code:String,type:RadarType):SignalResult {
        val day=LocalDate.of(2026,10,9)
        val candles=(0 until 65).map{i->
            val d=day.minusDays((64-i).toLong())
            DailyBar(d.atTime(9,0).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),
                100.0,102.0,98.0,101.0,1000000L)
        }
        val stock=MarketStock(code,"測試$code",Market.TWSE,StockSector.SEMICONDUCTOR,
            100.0,102.0,98.0,101.0,1.0,1000,null,null)
        return SignalResult(code,stock.name,stock.sector,type,100,SignalLight.ORANGE,
            emptyList(),emptyList(),TechnicalCalculator().build(stock,candles))
    }

    @Test fun allHitsStillVisibleWhenAZeroButBAndCHaveStocks(){
        val b=result("2330",RadarType.B_DEEP_REVERSAL)
        val c=result("2317",RadarType.C_LONG_RED_VOLUME)
        val data=listOf(b,c)
        assertTrue(ResultFilters.select(data,0).isEmpty())
        assertEquals(1,ResultFilters.select(data,1).size)
        assertEquals(1,ResultFilters.select(data,2).size)
        assertEquals(2,ResultFilters.select(data,6).size)
        val adapter=SignalAdapter()
        adapter.submit(ResultFilters.select(data,6));assertEquals(2,adapter.itemCount)
        adapter.submit(listOf(c));assertEquals(1,adapter.itemCount)
        adapter.submit(data);assertEquals(2,adapter.itemCount)
    }

    @Test fun combinedFilterIsAvailableWithoutAddingTallExtraRow(){
        // MaterialButton requires the actual AppCompat activity theme.
        val ctl=org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
        val view=ResultFilterBar(ctl.get())
        assertEquals(5,view.childCount) // three rows with readable complete category names
        view.update(listOf(0,3,13,2,50,200,18),6)
        var selected=-1;view.onSelected={selected=it}
        val lower=view.getChildAt(2) as android.view.ViewGroup
        val all=lower.getChildAt(0) as android.widget.TextView
        assertTrue(all.text.contains("全部入選"))
        assertTrue(all.text.contains("18"))
        all.performClick()
        assertEquals(6,selected)
        ctl.pause().stop().destroy()
    }

    @Test fun v047DConditionKeysRetainTheirOriginalPrefix(){
        val old=listOf(DPattern.LIMIT,DPattern.GREEN,DPattern.GAP,DPattern.UNFILLED,
            DPattern.VOLUME,DPattern.RELATIVE)
        val d=ScanConditions.forRadar(RadarType.D_MAIN_FORCE)
        val a=ScanConditions.forRadar(RadarType.A_EARLY_BREAKOUT).filterNot{it.id in APattern.IDS}
        assertEquals(old,d.take(6).map{it.id})
        assertEquals(a.map{it.id},d.drop(6).take(a.size).map{it.id})
        assertEquals(6+a.size+5,d.size)
        assertEquals(DPattern.CHIP_ORDER,d.takeLast(5).map{it.id})
        val oldSlots=BtRobotSpace.allSlots.take(77)
        assertEquals(BtRobotSpace.slots+old.map{RadarType.D_MAIN_FORCE to it},oldSlots)
        assertEquals(102,BtRobotSpace.v4Slots.size)
        assertEquals(106,BtRobotSpace.v5Slots.size)
    }

    @Test fun olderSnapshotReadsWithoutImplicitDFilterMutation(){
        val original=BtSettings(LocalDate.of(2026,8,1),LocalDate.of(2026,9,1))
        val oldD=listOf(DPattern.LIMIT,DPattern.GREEN,DPattern.GAP,DPattern.VOLUME)
        val snapshot=JSONObject(BtSettingsCodec.encode(original).toString())
        snapshot.put("rulesVersion",ScanConditions.PREVIOUS_VERSION)
        snapshot.getJSONObject("rules").put(RadarType.D_MAIN_FORCE.name,JSONArray(oldD))
        val restored=BtSettingsCodec.decode(snapshot)
        assertEquals(oldD.toSet(),restored.rules.getValue(RadarType.D_MAIN_FORCE))
        snapshot.put("rulesVersion",ScanConditions.PRE_D_VERSION)
        assertEquals(oldD.toSet(),BtSettingsCodec.decode(snapshot).rules.getValue(RadarType.D_MAIN_FORCE))
    }

    @Test fun officialCsvBackupParsesThousandsSeparatorsWithoutLosingListedStocks(){
        val csv="""證券代號,證券名稱,成交股數,成交金額,開盤價,最高價,最低價,收盤價,漲跌價差,成交筆數
"2330","台積電","34,000,000","30,000,000,000","1050.00","1060.00","1040.00","1055.00","+5.00","22,000"
"2317","鴻海","1,500,000","400,000,000","210","220","209","215","-2","3,000"
"""
        val parsed=TwseCsvBackup.parse(csv,mapOf("2330" to ("台積電" to StockSector.SEMICONDUCTOR)))
        assertEquals(2,parsed.size)
        assertEquals("2330",parsed[0].code)
        assertEquals(34000,parsed[0].volumeLots)
        assertEquals(StockSector.SEMICONDUCTOR,parsed[0].sector)
        assertEquals(StockSector.UNKNOWN,parsed[1].sector)
        assertEquals("鴻海",parsed[1].name)
        assertEquals(215.0,parsed[1].close,.00001)
        assertTrue(TwseCsvBackup.parse(csv.replace("證券代號","錯誤欄名")).isEmpty())
        assertEquals(10,TwseCsvBackup.fields("\"2330\",\"台積電\",\"34,000,000\",\"12,000,000\",\"100\",\"110\",\"99\",\"105\",\"5\",\"10\"").size)
    }
}
