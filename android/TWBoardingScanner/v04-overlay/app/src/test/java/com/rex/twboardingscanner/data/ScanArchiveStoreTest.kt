package com.rex.twboardingscanner.data

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.ui.ScanArchiveActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
class ScanArchiveStoreTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun clear(){app.deleteDatabase("scanner-folders-v1.db")}
    private fun at(day:String):Long=LocalDate.parse(day).atTime(13,30).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli()
    private fun sample(code:String,type:RadarType,up:Boolean=true,price:Double=100.0):SignalResult {
        val date=LocalDate.of(2026,10,9)
        val bars=(0..85).map{i->
            val value=if(up)price-8+(i*8.0/85) else price+8-(i*8.0/85)
            DailyBar(date.minusDays((85-i).toLong()).atTime(9,0).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),
                value-.3,value+1,value-1,value,1_000_000)
        }
        val stock=MarketStock(code,if(code=="2330")"台積電" else "聯發科",Market.TWSE,
            StockSector.SEMICONDUCTOR,price,price+1,price-1,price,0.0,1000,null,null)
        val snap=TechnicalCalculator().build(stock,bars).copy(timestamp=at("2026-10-09"))
        val selected=setOf("price","volume")
        return when(type){
            RadarType.A_EARLY_BREAKOUT->ScoringEngine().evaluateA(snap,selected)
            RadarType.B_DEEP_REVERSAL->ScoringEngine().evaluateB(snap,selected)
            RadarType.C_LONG_RED_VOLUME->ScoringEngine().evaluateC(snap,selected)
            RadarType.D_MAIN_FORCE->ScoringEngine().evaluateD(snap,DPattern.DEFAULT_ENABLED)
        }
    }
    private fun rules()=RadarType.entries.associateWith{setOf("price","volume")}
    private fun settings(created:Long,changes:Boolean=false)=ScanArchiveStore.snapshot(
        "2026-10-09",created,
        if(changes)rules().toMutableMap().apply{
            this[RadarType.A_EARLY_BREAKOUT]=setOf("price","volume",ScanConditions.MA5_TOUCH_MA10)
            this[RadarType.B_DEEP_REVERSAL]=setOf("price")
        } else rules(),
        setOf(StockSector.SEMICONDUCTOR),StockPolicy(),
        "flow-v1",1948,1940,8)
    private fun find(root:View,tagPrefix:String):View?{
        if(root.tag?.toString()?.startsWith(tagPrefix)==true)return root
        if(root is ViewGroup)for(i in 0 until root.childCount)find(root.getChildAt(i),tagPrefix)?.let{return it}
        return null
    }
    @Test fun everyScanHasItsOwnFolderWithFrozenConditionsDiffCountsAndEstimatedFunding(){
        val db=ScanArchiveStore(app)
        val a=sample("2330",RadarType.A_EARLY_BREAKOUT)
        val b=sample("2454",RadarType.A_EARLY_BREAKOUT,false,200.0)
        val c=sample("2330",RadarType.B_DEEP_REVERSAL)
        val d=sample("2454",RadarType.C_LONG_RED_VOLUME,false,200.0)
        val first=db.addCompleteScan("2026-10-09",at("2026-10-09"),settings(at("2026-10-09")),
            listOf(a,b,c,d))
        assertEquals(2,first.a);assertEquals(1,first.b);assertEquals(1,first.c)
        assertEquals(2,first.uniqueStocks)
        assertTrue(first.estimatedCash>300000.0)
        val original=db.rows(first.id)
        assertEquals(4,original.size)
        assertTrue(original.zipWithNext().all{(x,y)->x.score>=y.score})
        assertTrue(original.first().ruleReport.contains("AI 技術訊號"))
        assertTrue(original.all{it.chartBars.size==60})
        val second=db.addCompleteScan("2026-10-09",at("2026-10-09")+65000,
            settings(at("2026-10-09")+65000,true),listOf(a,c))
        assertNotEquals(first.id,second.id)
        assertEquals(1,second.a);assertEquals(1,second.b)
        assertTrue(second.changes.contains("A ＋"))
        assertTrue(second.changes.contains("B −"))
        assertEquals(2,db.list("2026-10-09").size)
        assertEquals(second.id,db.list().first().id)
        assertEquals(4,db.rows(first.id).size)
        assertEquals(2,db.rows(second.id).size)
        assertEquals("首次條件快照 · ABC／產業與名單已保存",first.changes)
        db.close()
        val after=ScanArchiveStore(app)
        assertEquals(2,after.list().size)
        assertEquals(first.signature,after.list().last().signature)
        assertEquals(original,after.rows(first.id))
        assertTrue(after.settings(first.id)!!.getJSONObject("rules")
            .getJSONArray(RadarType.A_EARLY_BREAKOUT.name).length()==2)
        after.close()
    }
    @Test fun emptyScanStillProducesFolderAndUnchangedCriteriaAreLabeledRescan(){
        val store=ScanArchiveStore(app)
        val t=at("2026-10-09")
        val a=store.addCompleteScan("2026-10-09",t,settings(t),emptyList())
        val b=store.addCompleteScan("2026-10-09",t+2,settings(t+2),emptyList())
        assertEquals(0,a.uniqueStocks);assertEquals(0.0,b.estimatedCash,0.0)
        assertTrue(b.changes.contains("相同"))
        assertTrue(store.rows(a.id).isEmpty())
        store.close()
    }
    @Test fun archiveActivityCanOpenSelectedFolderAndLegacyCalendarWithoutErasingEither(){
        val store=ScanArchiveStore(app)
        store.addCompleteScan("2026-10-09",at("2026-10-09"),settings(at("2026-10-09")),
            listOf(sample("2330",RadarType.A_EARLY_BREAKOUT)))
        store.close()
        val controller=Robolectric.buildActivity(ScanArchiveActivity::class.java).setup()
        val root=controller.get().window.decorView
        assertNotNull(find(root,"folder-all"))
        assertNotNull(find(root,"folder-legacy-calendar"))
        val folder=find(root,"scan-folder-")
        assertNotNull(folder)
        val buttons=mutableListOf<TextView>()
        fun collect(v:View){
            if(v is TextView&&v.text.toString().contains("開啟 · AI 排名"))buttons+=v
            if(v is ViewGroup)for(i in 0 until v.childCount)collect(v.getChildAt(i))
        }
        collect(folder!!)
        assertTrue(buttons.first().performClick())
        assertEquals(View.VISIBLE,find(root,"folder-ranked-list")!!.visibility)
        assertEquals(View.VISIBLE,find(root,"folder-detail-head")!!.visibility)
        assertTrue(find(root,"folder-back")!!.performClick())
        assertEquals(View.GONE,find(root,"folder-ranked-list")!!.visibility)
        controller.pause().stop().destroy()
    }
}
