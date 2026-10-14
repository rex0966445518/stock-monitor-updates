package com.rex.twboardingscanner.backtest

import android.graphics.Bitmap
import android.graphics.Canvas
import org.robolectric.annotation.GraphicsMode
import java.io.File
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
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
        val id=BtRobotJournalSync.createBatch(store,settings,"7e67cf98")
        val saved=store.session(id)!!
        assertEquals("PAUSED",saved.getString("state"))
        assertEquals(BtRobotSpace.key(settings.rules),saved.getString("required"))
        assertEquals("0",saved.getString("forbidden"))
        assertEquals("7e67cf98",saved.getString("sourceJournalId"))
        assertEquals(settings,BtSettingsCodec.decode(saved.getJSONObject("settings")))
        assertTrue(BtRobotSingleOption.active(saved))
        val first=store.next(id)!!
        assertEquals(BtRobotSingleOption.keys(saved.getString("required"),"0",settings.enabledRadars,true).first(),first.key)
        assertTrue(BtRobotSingleOption.baseline(saved,first.key))
        assertEquals(0L,saved.getLong("tested"))
    }
    @Test fun archivedAbcdChecksAppearInMenusAndStayRequiredDuringExploration(){
        val counts=listOf(12,9,5,9,2,1)
        val archived=archive().copy(rules=RadarType.entries.associateWith{type->
            ScanConditions.forRadar(type).take(counts[type.ordinal]).map{it.id}.toSet()
        },enabledRadars=RadarType.entries.toSet())
        val original=row(archived);val before=original.toString()
        val store=BtRobotStore(app)
        // A prior robot's excluded checks must not contaminate the new journal batch.
        val old=store.create(archive().copy(stockScope=StockScope.ROBOT),forbidden=BtRobotSpace.key(archived.rules))
        val desired=BtRobotJournalSync.fromJournal(original)
        com.rex.twboardingscanner.data.StockPolicyStore(app).change{desired.stockPolicy}
        val id=BtRobotJournalSync.createBatch(store,desired,"7e67cf98")
        val session=store.session(id)!!
        assertEquals("0",session.getString("forbidden"))
        assertEquals(archived.rules,BtRobotSpace.rules(session.getString("required")))
        assertEquals(before,original.toString())
        assertEquals(BtRobotSpace.key(archived.rules),store.session(old)!!.getString("forbidden"))
        val ctl=Robolectric.buildActivity(BtRobotActivity::class.java).setup()
        val a=ctl.get()
        RadarType.entries.forEach{type->
            val button=find(a.window.decorView,"robot-rules-${type.name}") as TextView
            assertTrue(button.text.toString().contains("必選 ${counts[type.ordinal]}／排除 0"))
            val toggle=find(a.window.decorView,"robot-enabled-${type.name}") as TextView
            assertTrue(toggle.text.toString().contains("已啟用"))
        }
        val retained=a.policySession(RobotTradingParams.of(desired),null,RobotBacktestDates.of(desired))
        assertEquals(id,retained)
        assertEquals(archived.rules,BtRobotSpace.rules(store.session(retained)!!.getString("required")))
        val seen=mutableSetOf<String>();var cursor=java.math.BigInteger.ZERO
        repeat(20){
            val candidate=BtRobotSpace.next(session.getString("required"),null,cursor,
                java.math.BigInteger.valueOf(19),session.getString("required"),enabled=desired.enabledRadars){it in seen}!!
            cursor=candidate.cursor;seen+=candidate.key
            val rules=BtRobotSpace.rules(candidate.key)
            RadarType.entries.forEach{assertTrue(rules.getValue(it).containsAll(archived.rules.getValue(it)))}
        }
        ctl.pause().stop().destroy()
        val reopened=Robolectric.buildActivity(BtRobotActivity::class.java).setup()
        assertTrue((find(reopened.get().window.decorView,"robot-rules-${RadarType.D_MAIN_FORCE.name}") as TextView)
            .text.toString().contains("必選 9／排除 0"))
        reopened.pause().stop().destroy()
    }
    @Test fun journalSyncKeepsInactiveRadarsAndDThresholdsInsteadOfOldDraft(){
        val archived=archive().copy(enabledRadars=setOf(RadarType.D_MAIN_FORCE),
            dConfig=DPatternConfig(lookback=35,bullishBars=5,volumeMultiple=2.5),
            rules=RadarType.entries.associateWith{ScanConditions.forRadar(it).take(3).map{r->r.id}.toSet()})
        val store=BtRobotStore(app);val old=store.create(archive().copy(stockScope=StockScope.ROBOT))
        app.getSharedPreferences("robot-params-draft",0).edit().putString("session",old)
            .putStringSet("activeRadars",setOf(RadarType.A_EARLY_BREAKOUT.name)).commit()
        val desired=BtRobotJournalSync.fromJournal(row(archived))
        val id=BtRobotJournalSync.createBatch(store,desired,"7e67cf98")
        val ctl=Robolectric.buildActivity(BtRobotActivity::class.java).setup()
        RadarType.entries.forEach{type->
            val button=find(ctl.get().window.decorView,"robot-enabled-${type.name}") as TextView
            assertEquals(type==RadarType.D_MAIN_FORCE,button.text.toString().contains("已啟用"))
        }
        assertEquals(desired,BtSettingsCodec.decode(store.session(id)!!.getJSONObject("settings")))
        assertEquals(desired.dConfig,BtSettingsCodec.decode(store.session(id)!!.getJSONObject("settings")).dConfig)
        ctl.pause().stop().destroy()
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
        listOf("scanner","backtest").forEach{target->
            val ready=find(journal.get().window.decorView,"journal-sync-$target-7e67cf98") as TextView
            val disabled=find(journal.get().window.decorView,"journal-sync-$target-unfinished") as TextView
            assertTrue(ready.isEnabled);assertFalse(disabled.isEnabled)
            ready.performClick()
            val prompt=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            assertTrue(prompt.isShowing);prompt.dismiss()
        }
        val strip=done.parent as View
        strip.measure(View.MeasureSpec.makeMeasureSpec(624,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        strip.layout(0,0,624,strip.measuredHeight)
        val bitmap=Bitmap.createBitmap(624,strip.measuredHeight,Bitmap.Config.ARGB_8888)
        strip.draw(Canvas(bitmap))
        val preview=File("build/ui-previews/journal-sync-three-buttons.png")
        preview.parentFile!!.mkdirs()
        preview.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
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
