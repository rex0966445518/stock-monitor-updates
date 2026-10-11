package com.rex.twboardingscanner.backtest

import android.widget.EditText
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.ui.BacktestJournalActivity
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
import org.robolectric.shadows.ShadowDialog
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
class RobotJournalSyncTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun clean(){
        app.getSharedPreferences("backtest_robot",0).edit().clear().commit()
        app.getSharedPreferences("stock-entry-policy",0).edit().clear().commit()
        app.getSharedPreferences("robot-params-draft",0).edit().clear().commit()
        // SQLite helper is shared by existing robot tests; use unique UUID sessions and clear only active pointer.
    }
    private fun archive():BtSettings{
        val rules=RadarType.entries.associateWith{type->
            when(type){RadarType.A_EARLY_BREAKOUT->setOf("price","volume");else->emptySet()}
        }
        return BtRobotSpace.settings(rules,setOf(StockSector.SEMICONDUCTOR,StockSector.ELECTRONICS)).copy(
            start=LocalDate.of(2026,8,1),end=LocalDate.of(2026,10,8),
            capital=5000000.0,maxHoldingStocks=20,targetNetPct=4.0,codes="2330,2454",
            stockPolicy=StockPolicy(banned=setOf("2414"),ceiling=1800.0),
            exitRules=emptyList(),stockScope=StockScope.BACKTEST,marketGuardVersion=MarketCrashGuard.VERSION
        )
    }
    private fun row(s:BtSettings=archive(),state:String="DONE")=
        JSONObject().put("id","7e67cf98").put("state",state).put("settings",BtSettingsCodec.encode(s))
            .put("startedAt",System.currentTimeMillis()).put("profit",1154712.0)
            .put("buys",294).put("closed",274).put("holdings",20)
    private fun find(v:View,tag:String):View?{
        if(v.tag==tag)return v
        if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),tag)?.let{return it}
        return null
    }
    @Test fun journalSyncPreservesEntireSnapshotButSetsRobotScopeAndCreatesIndependentBatch(){
        val original=row();val before=original.toString()
        val settings=BtRobotJournalSync.fromJournal(original)
        assertEquals(archive().copy(stockScope=StockScope.ROBOT),settings)
        assertEquals(5000000.0,settings.capital,0.0)
        assertEquals(20,settings.maxHoldingStocks)
        assertEquals(4.0,settings.targetNetPct,0.0)
        assertEquals(setOf("2414"),settings.stockPolicy.banned)
        assertEquals("2330,2454",settings.codes)
        assertEquals(before,original.toString())
        val store=BtRobotStore(app)
        val id=store.create(settings,sourceJournalId="7e67cf98")
        val saved=store.session(id)!!
        assertEquals("PAUSED",saved.getString("state"))
        assertEquals("7e67cf98",saved.getString("sourceJournalId"))
        assertEquals(settings,BtSettingsCodec.decode(saved.getJSONObject("settings")))
        assertEquals(BtRobotSpace.key(settings.rules),store.next(id)!!.key)
        assertEquals(0L,saved.getLong("tested"))
    }
    @Test fun legacyOrIncompleteJournalIsRejectedRatherThanSilentlyDroppingRules(){
        assertThrows(Exception::class.java){BtRobotJournalSync.fromJournal(row(state="ERROR"))}
        assertThrows(Exception::class.java){BtRobotJournalSync.fromJournal(row().apply{getJSONObject("settings").remove("stockPolicy")})}
        assertThrows(Exception::class.java){BtRobotJournalSync.fromJournal(row().apply{getJSONObject("settings").remove("exitRules")})}
        assertThrows(Exception::class.java){BtRobotJournalSync.fromJournal(row(archive().copy(marketGuardVersion=0)))}
    }
    @Test fun variableMoneyHoldingsAndNetProfitValidateAndPersistPerBatch(){
        val base=archive().copy(stockPolicy=StockPolicy(),stockScope=StockScope.ROBOT)
        val params=RobotTradingParams.parse("8,000,000","18","2.5")
        assertEquals(8000000.0,params.capital,0.0)
        val target=params.applyTo(base)
        BtRobotSpace.validate(target)
        assertEquals(18,target.maxHoldingStocks);assertEquals(2.5,target.targetNetPct,0.0)
        val store=BtRobotStore(app)
        val first=store.create(base)
        val second=store.create(target)
        assertNotEquals(first,second)
        assertEquals(base,BtSettingsCodec.decode(store.session(first)!!.getJSONObject("settings")))
        assertEquals(target,BtSettingsCodec.decode(store.session(second)!!.getJSONObject("settings")))
        listOf(Triple("","18","2.5"),Triple("1000","0","2.5"),Triple("1000","18","0"),
            Triple("1000","18","2000"),Triple("bad","18","2.5")).forEach{(money,hold,profit)->
            assertThrows(Exception::class.java){RobotTradingParams.parse(money,hold,profit)}
        }
    }
    @Test fun journalCardHasSyncButtonAndRobotFieldsAreEditable(){
        val row=row()
        val journal=Robolectric.buildActivity(BacktestJournalActivity::class.java).setup()
        journal.get().renderEntries(listOf(row,row().put("id","unfinished").put("state","ERROR")))
        val done=find(journal.get().window.decorView,"journal-sync-7e67cf98") as TextView
        val incomplete=find(journal.get().window.decorView,"journal-sync-unfinished") as TextView
        assertNotNull(done);assertTrue(done.isEnabled)
        assertNotNull(incomplete);assertFalse(incomplete.isEnabled)
        done.performClick()
        val dialog=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(dialog.isShowing)
        assertTrue(dialog.window!!.decorView.toString().isNotEmpty())
        dialog.dismiss()
        journal.pause().stop().destroy()
        val store=BtRobotStore(app)
        store.create(archive().copy(stockPolicy=StockPolicy(),stockScope=StockScope.ROBOT))
        val robot=Robolectric.buildActivity(BtRobotActivity::class.java).setup()
        val root=robot.get().window.decorView
        val capital=find(root,"robot-capital-input") as EditText
        val holdings=find(root,"robot-holdings-input") as EditText
        val profit=find(root,"robot-profit-input") as EditText
        assertEquals("5000000",capital.text.toString())
        assertEquals("20",holdings.text.toString())
        assertEquals("4",profit.text.toString())
        capital.setText("7200000");holdings.setText("16");profit.setText("3")
        robot.get().render()
        assertEquals("7200000",capital.text.toString())
        assertEquals("16",holdings.text.toString())
        assertEquals("3",profit.text.toString())
        assertEquals(RobotTradingParams(7200000.0,16,3.0),
            RobotTradingParams.parse(capital.text.toString(),holdings.text.toString(),profit.text.toString()))
        robot.pause().stop().destroy()
    }
}
