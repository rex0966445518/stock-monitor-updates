package com.rex.twboardingscanner.backtest

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.ui.BtRobotActivity
import com.rex.twboardingscanner.ui.BtRobotHistoryActivity
import com.rex.twboardingscanner.ui.BacktestActivity
import com.rex.twboardingscanner.ui.BacktestJournalUi
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import android.os.Looper
import androidx.appcompat.app.AlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class RobotHistoryTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val store get()=BtRobotStore(app)
    private val day=LocalDate.of(2026,8,1)
    private val settings get()=BtSettings(day,day.plusDays(6),rules=BtRobotSpace.rules("0"),enabledRadars=setOf(RadarType.C_LONG_RED_VOLUME),targetNetPct=1.0,stockScope=StockScope.ROBOT,stockPolicy=com.rex.twboardingscanner.data.StockPolicyStore(app).read())
    private fun stocks()=listOf(BtSeries("2330","fixture",Market.TWSE,List(86){i->
        val close=100.0+i*.1
        DailyBar(day.minusDays(79).plusDays(i.toLong()).atTime(13,30).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),close-.1,close+4,close-1,close,1000000L)
    }))
    @Before fun clean(){
        store.locked{it.delete("trials",null,null);it.delete("sessions",null,null)}
        app.getSharedPreferences("backtest_robot",0).edit().clear().commit()
        runCatching{androidx.work.WorkManager.initialize(app,androidx.work.Configuration.Builder().build())}
    }
    private fun batch(n:Int=3,sweep:BtRobotSweep?=null):String {
        val base=settings;val id=store.createSingle(base,sweep=sweep);val token=store.resume(id)
        repeat(n){
            val candidate=store.next(id)!!
            val trial=sweep?.settings(base,candidate.key)?:BtRobotSingleOption.settings(base,candidate.key,BtRobotSingleOption.baseline(store.session(id),candidate.key))
            val run=BacktestEngine.run(BacktestEngine.prepare(stocks(),trial),trial)
            store.commit(id,token,candidate,BtResult(trial,run,1,1,emptyList(),"real trade fixture"),1)
        }
        return id
    }
    private fun find(v:View,tag:String):View? {
        if(v.tag==tag)return v
        if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),tag)?.let{return it}
        return null
    }
    @Test fun allBatchesArePagedWithoutDuplicatesAndDetailsRetainBuySellReasons() {
        val first=batch();val second=batch()
        assertEquals(6L,store.historyCount())
        val page1=store.historyPage(limit=2);val page2=store.historyPage(page1.last().getLong("n"),2);val page3=store.historyPage(page2.last().getLong("n"),2)
        val all=page1+page2+page3
        assertEquals(6,all.map{it.getLong("n")}.toSet().size)
        assertEquals(setOf(first,second),all.map{it.getString("session")}.toSet())
        assertEquals(all.map{it.getLong("n")}.sortedDescending(),all.map{it.getLong("n")})
        val report=store.report(first,"0")!!
        val trades=report.getJSONObject("run").getJSONArray("trades")
        val items=(0 until trades.length()).map{trades.getJSONObject(it)}
        assertTrue(items.any{it.getString("side")=="BUY"});assertTrue(items.any{it.getString("side")=="SELL"})
        assertTrue(items.all{it.getString("reason").isNotBlank()&&it.getInt("shares")==1000})
        assertTrue(store.deleteTrial(second,page1.first().getString("key")))
        assertEquals(page2.map{it.getLong("n")},store.historyPage(page1.last().getLong("n"),2).map{it.getLong("n")})
    }
    @Test fun deletingBestKeepsLedgerOtherReportsAndRunningWorkIntact() {
        val id=batch();val s=store.session(id)!!;val key=s.getString("bestKey")
        val remaining=store.remaining(id);val next=store.next(id);val before=store.historyCount()
        assertTrue(store.deleteTrial(id,key));assertFalse(store.deleteTrial(id,key));assertTrue(store.seen(id,key))
        assertNull(store.report(id,key));assertEquals(remaining,store.remaining(id));assertEquals(next,store.next(id))
        assertEquals(s.getLong("tested"),store.session(id)!!.getLong("tested"))
        assertEquals(s.getString("token"),store.session(id)!!.getString("token"));assertEquals("RUNNING",store.session(id)!!.getString("state"))
        assertEquals(before-1,store.historyCount());assertTrue(store.topTrials(id).none{it.getString("key")==key})
        assertTrue(store.searchPage().none{it.getString("session")==id&&it.getString("key")==key})
        val replacement=store.session(id)!!.getString("bestKey")
        assertNotEquals(key,replacement);assertNotNull(store.report(id,replacement))
        store.trials(id).forEach{assertTrue(store.deleteTrial(id,it.getString("key")))}
        assertEquals("",store.session(id)!!.getString("bestKey"));assertFalse(store.session(id)!!.has("bestProfit"))
        assertEquals(0,store.clearZero(id));assertEquals(remaining,store.remaining(id))
    }
    @Test fun deletionSurvivesExportRestoreIncludingGridPoints() {
        for(sweep in listOf(null,BtRobotSweep(5,5,1,3))){
            val id=batch(2,sweep);val key=store.trials(id).first().getString("key")
            store.deleteTrial(id,key)
            val remaining=store.remaining(id);val candidate=store.next(id)
            BtRobotDataset.write(store.dataset(id),BacktestData.Loaded(stocks(),1,emptyList()))
            val token=store.session(id)!!.getString("token")
            store.coverage(id,token,1,1,BtRobotCheckpoint.digest(store.dataset(id)));store.pause(id)
            val zip=File(app.cacheDir,"history-checkpoint.zip");BtRobotCheckpoint.export(app,id,zip)
            val restored=zip.inputStream().use{BtRobotCheckpoint.restore(app,it)}
            assertNull(store.report(restored,key));assertTrue(store.seen(restored,key))
            assertEquals(1L,store.session(restored)!!.getLong("deleted"));assertEquals(0L,store.session(restored)!!.getLong("cleared"))
            assertEquals(remaining,store.remaining(restored));assertEquals(candidate,store.next(restored))
            assertEquals(1,store.historyPage(limit=100).count{it.getString("session")==restored})
            zip.delete()
        }
    }
    @Test fun historyCardsOpenExactReportAndDeleteOnlyAfterUserConfirms() {
        val id=batch();val rows=store.historyPage();val entry=rows.first();val n=entry.getLong("n");val key=entry.getString("key")
        val controller=Robolectric.buildActivity(BtRobotHistoryActivity::class.java).create();val activity=controller.get()
        activity.renderRows(rows,store.historyCount());val root=activity.window.decorView
        assertNotNull(find(root,"robot-history-card-$n"))
        find(root,"robot-history-details-$n")!!.performClick()
        val intent=shadowOf(activity).nextStartedActivity
        assertEquals(BacktestActivity::class.java.name,intent.component!!.className)
        assertEquals(id,intent.getStringExtra("robotSession"));assertEquals(key,intent.getStringExtra("robotKey"))
        find(root,"robot-history-delete-$n")!!.performClick()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertNotNull(store.report(id,key))
        find(root,"robot-history-delete-$n")!!.performClick()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(System.nanoTime()<end&&store.report(id,key)!=null){Thread.yield();shadowOf(Looper.getMainLooper()).idle()}
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(store.report(id,key));assertNotNull(store.report(id,"0"))
        controller.destroy()
        val robot=Robolectric.buildActivity(BtRobotActivity::class.java).setup()
        find(robot.get().window.decorView,"robot-history-entry")!!.performClick()
        assertEquals(BtRobotHistoryActivity::class.java.name,shadowOf(robot.get()).nextStartedActivity.component!!.className)
        robot.pause().stop().destroy()
        val baseline=BtSettingsCodec.encode(BtRobotSingleOption.settings(settings,"0",true))
        assertTrue(BacktestJournalUi.describe(baseline).contains("空白基準：不套用選股條件"))
    }
}
