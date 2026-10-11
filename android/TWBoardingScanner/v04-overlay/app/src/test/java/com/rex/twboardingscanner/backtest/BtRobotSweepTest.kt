package com.rex.twboardingscanner.backtest

import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.ui.BtRobotActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
class BtRobotSweepTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun resetPrefs(){
        app.getSharedPreferences("backtest_robot",0).edit().clear().commit()
        app.getSharedPreferences("robot-params-draft",0).edit().clear().commit()
        app.getSharedPreferences("stock-entry-policy",0).edit().clear().commit()
    }
    private fun find(v:View,tag:String):View?{
        if(v.tag==tag)return v
        if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),tag)?.let{return it}
        return null
    }
    private fun result(settings:BtSettings,profit:Double):BtResult{
        val r=BtRun(settings.capital,cash=settings.capital+profit,strategyVersion=4)
        r.curve.add(BtDay(settings.end,r.equity))
        return BtResult(settings,r,1,1,emptyList(),"參數範圍回測測試")
    }
    private fun base()=BtRobotSpace.settings().copy(
        start=LocalDate.of(2026,8,1),end=LocalDate.of(2026,10,8),
        marketGuardVersion=MarketCrashGuard.VERSION
    )
    @Test fun fullRangeIsExactly1380InDeterministicOrder(){
        val sweep=BtRobotSweep()
        assertEquals(1380,sweep.total)
        assertEquals(BtRobotSweep.Point(5,1),sweep.at(0))
        assertEquals(BtRobotSweep.Point(5,30),sweep.at(29))
        assertEquals(BtRobotSweep.Point(6,1),sweep.at(30))
        assertEquals(BtRobotSweep.Point(50,30),sweep.at(1379))
        assertEquals("grid_5_1",sweep.next(0)!!.key)
        assertEquals(1379L,sweep.next(1378)!!.cursor.toLong())
        assertNull(sweep.next(1380))
        val all=(0 until sweep.total).map{sweep.at(it).key}
        assertEquals(1380,all.distinct().size)
        assertEquals(all.indices.toList(),all.map{sweep.index(it)})
        assertEquals(sweep,BtRobotSweep.read(sweep.json()))
        assertThrows(Exception::class.java){sweep.next(1381)}
        assertThrows(Exception::class.java){sweep.parseKey("grid_51_1")}
        assertThrows(Exception::class.java){sweep.parseKey("grid_5_0")}
        assertThrows(Exception::class.java){sweep.parseKey("grid_05_1")}
        assertThrows(Exception::class.java){BtRobotSweep(4,50,1,30)}
        assertThrows(Exception::class.java){BtRobotSweep(5,51,1,30)}
        assertThrows(Exception::class.java){BtRobotSweep(5,50,0,30)}
        assertThrows(Exception::class.java){BtRobotSweep(5,50,1,31)}
        assertEquals(12,BtRobotSweep.parse("6","8","2","5").total)
    }
    @Test fun distinctHoldingsProfitUseUniqueKeysSaveReportsAndResumePrecisely(){
        val store=BtRobotStore(app)
        val sweep=BtRobotSweep(5,6,1,2)
        val base=base()
        val id=store.create(base,sweep=sweep)
        val a=store.session(id)!!
        assertEquals(0L,a.getLong("tested"))
        assertEquals("PAUSED",a.getString("state"))
        assertEquals(sweep,BtRobotSweep.read(a.optJSONObject("sweep")))
        assertEquals(java.math.BigInteger.valueOf(4),store.remaining(id))
        val token=store.resume(id)
        val first=store.next(id)!!
        assertEquals("grid_5_1",first.key)
        assertEquals(base.copy(maxHoldingStocks=5,targetNetPct=1.0),sweep.settings(base,first.key))
        assertTrue(store.commit(id,token,first,result(sweep.settings(base,first.key),100.0),1))
        assertFalse(store.commit(id,token,first,result(sweep.settings(base,first.key),99999.0),1))
        assertEquals(1L,store.session(id)!!.getLong("tested"))
        assertEquals(java.math.BigInteger.valueOf(3),store.remaining(id))
        val second=store.next(id)!!
        assertEquals("grid_5_2",second.key)
        store.pause(id)
        assertFalse(store.commit(id,token,second,result(sweep.settings(base,second.key),999.0),2))
        val restart=store.resume(id)
        assertNotEquals(token,restart)
        assertEquals(second.key,store.next(id)!!.key)
        assertTrue(store.commit(id,restart,second,result(sweep.settings(base,second.key),200.0),3))
        assertEquals("grid_5_2",store.session(id)!!.getString("bestKey"))
        val summary=store.topTrials(id)
        assertEquals(2,summary.size)
        assertEquals(200.0,summary[0].getDouble("profit"),0.001)
        assertEquals(5,summary[0].getInt("maxHoldingStocks"))
        assertEquals(2.0,summary[0].getDouble("targetNetPct"),.001)
        assertEquals(1.0,BtSettingsCodec.decode(store.report(id,first.key)!!.getJSONObject("settings")).targetNetPct,0.0)
        assertEquals(2.0,BtSettingsCodec.decode(store.report(id,second.key)!!.getJSONObject("settings")).targetNetPct,0.0)
        assertEquals("grid_6_1",sweep.next(2)!!.key)
        assertEquals("grid_6_2",sweep.next(3)!!.key)
    }
    @Test fun backupRestoresSweepDefinitionAndRejectsMismatchedGridKeys(){
        val store=BtRobotStore(app)
        val id=store.create(base(),sweep=BtRobotSweep(5,7,2,4))
        val exported=File(app.cacheDir,"test-sweep-backup.zip")
        try{
            BtRobotCheckpoint.export(app,id,exported)
            val entryNames=mutableSetOf<String>()
            ZipInputStream(exported.inputStream()).use{zip->while(true){
                val entry=zip.nextEntry?:break;entryNames+=entry.name
                if(entry.name=="manifest.json"){
                    val json=JSONObject(zip.bufferedReader().readText())
                    assertEquals(8,json.getInt("version"))
                }
                zip.closeEntry()
            }}
            assertTrue(entryNames.containsAll(setOf("session.json","results.csv","manifest.json")))
            val restored=exported.inputStream().use{BtRobotCheckpoint.restore(app,it)}
            assertNotEquals(id,restored)
            assertEquals(BtRobotSweep(5,7,2,4),BtRobotSweep.read(store.session(restored)!!.optJSONObject("sweep")))
            assertEquals(0L,store.session(restored)!!.getLong("tested"))
            assertEquals("grid_5_2",store.next(restored)!!.key)
        }finally{exported.delete()}
    }
    @Test fun robotShowsFourEditableRangeFieldsAndProgress(){
        val store=BtRobotStore(app)
        val id=store.create(base(),sweep=BtRobotSweep(5,7,1,4))
        val activity=Robolectric.buildActivity(BtRobotActivity::class.java).setup()
        val root=activity.get().window.decorView
        val box=find(root,"robot-grid-mode") as CheckBox
        assertTrue(box.isChecked)
        val from=find(root,"robot-holding-from") as EditText
        val to=find(root,"robot-holding-to") as EditText
        val profFrom=find(root,"robot-profit-from") as EditText
        val profTo=find(root,"robot-profit-to") as EditText
        assertEquals("5",from.text.toString());assertEquals("7",to.text.toString())
        assertEquals("1",profFrom.text.toString());assertEquals("4",profTo.text.toString())
        assertFalse((find(root,"robot-holdings-input") as EditText).isEnabled)
        assertTrue((find(root,"robot-grid-total") as TextView).text.toString().contains("12"))
        assertTrue((find(root,"robot-grid-ranking") as TextView).text.toString().contains("最佳前 10"))
        assertEquals(12, BtRobotSweep.parse(from.text.toString(),to.text.toString(),profFrom.text.toString(),profTo.text.toString()).total)
        box.isChecked=false
        assertTrue((find(root,"robot-holdings-input") as EditText).isEnabled)
        box.isChecked=true
        activity.get().render()
        assertEquals(id,store.active())
        activity.pause().stop().destroy()
    }
}
