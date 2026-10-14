package com.rex.twboardingscanner.backtest

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.ui.BtRobotHistoryActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.math.BigInteger
import java.time.LocalDate
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class RobotHistoryRankingTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val store get()=BtRobotStore(app)
    private val a=RadarType.A_EARLY_BREAKOUT
    private val b=RadarType.B_DEEP_REVERSAL
    private val f=RadarType.F_VOLUME_CONTRACTION
    @Before fun clean(){
        store.locked{it.delete("trials",null,null);it.delete("sessions",null,null)}
        app.getSharedPreferences("backtest_robot",0).edit().clear().commit()
    }
    private fun record(types:Set<RadarType>,profit:Double,enabled:Set<RadarType> = types,baseline:Boolean=false):Pair<String,String> {
        val rules=RadarType.entries.associateWith{type->if(type in types&&!baseline)setOf(ScanConditions.forRadar(type).first().id) else emptySet()}
        val day=LocalDate.of(2026,8,1)
        val base=BtSettings(day,day.plusDays(3),rules=rules,enabledRadars=enabled,stockPolicy=com.rex.twboardingscanner.data.StockPolicyStore(app).read())
        val id=if(baseline)store.createSingle(base) else store.create(base)
        val token=store.resume(id)
        val key=BtRobotSpace.key(rules)
        val candidate=if(baseline)store.next(id)!! else BtRobotSpace.Candidate(key,BigInteger.ONE)
        val settings=if(baseline)BtRobotSingleOption.settings(base,key,true) else base
        // Deterministic accounting fixture: ranking must use the stored net P&L, not quote changes.
        val run=BtRun(settings.capital,cash=settings.capital+profit,strategyVersion=4).apply{curve+=BtDay(day,equity)}
        store.commit(id,token,candidate,BtResult(settings,run,1,1,emptyList(),"ranking fixture"),1)
        return id to key
    }
    private fun pnl(rows:List<org.json.JSONObject>)=rows.map{it.getJSONObject("summary").getDouble("profit")}
    @Test fun eachCategoryRanksAcrossBatchesWithStableTiesAndLosses() {
        record(setOf(a),190000.125);record(setOf(a),190000.125);record(setOf(b),999999.0)
        record(setOf(a),0.0);record(setOf(a),-1000.0);record(setOf(a),-190000.0)
        assertEquals(5L,store.historyCount(a));assertEquals(1L,store.historyCount(b))
        val first=store.rankedHistory(a,limit=2)
        assertEquals(listOf(190000.125,190000.125),pnl(first));assertTrue(first[0].getLong("n")>first[1].getLong("n"))
        val next=store.rankedHistory(a,first.last().getJSONObject("summary").getDouble("profit"),first.last().getLong("n"),2)
        assertEquals(listOf(0.0,-1000.0),pnl(next))
        val last=store.rankedHistory(a,next.last().getJSONObject("summary").getDouble("profit"),next.last().getLong("n"),2)
        assertEquals(listOf(-190000.0),pnl(last))
        val all=first+next+last
        assertEquals((1L..5L).toList(),all.map{it.getLong("rank")});assertEquals(5,all.map{it.getLong("n")}.toSet().size)
        assertEquals(999999.0,pnl(store.rankedHistory()).first(),0.0)
    }
    @Test fun classificationUsesTestedRulesAndBaselineEnabledRegionsAndBackfillsOldRows() {
        val mixed=record(setOf(a,b),300.0)
        record(setOf(f),100.0,enabled=setOf(a,f))
        val baseline=record(setOf(f),50.0,baseline=true)
        assertEquals(1L,store.historyCount(a));assertEquals(1L,store.historyCount(b));assertEquals(2L,store.historyCount(f))
        assertEquals(mixed.first,store.rankedHistory(b).single().getString("session"))
        store.locked{db->db.execSQL("UPDATE trials SET radars=0,net_profit=0");RobotHistoryCategory.backfill(db)}
        assertEquals(1L,store.historyCount(a));assertEquals(2L,store.historyCount(f))
        assertEquals(listOf(100.0,50.0),pnl(store.rankedHistory(f)))
        assertTrue(store.rankedHistory(f).any{it.getString("session")==baseline.first&&it.getJSONObject("summary").getBoolean("baseline")})
    }
    @Test fun deletingCategoryWinnerRebuildsItsRanksWithoutAffectingOtherCategories() {
        val winner=record(setOf(a),190000.0);record(setOf(a),50000.0)
        val other=record(setOf(b),999999.0)
        assertTrue(store.deleteTrial(winner.first,winner.second))
        val rows=store.rankedHistory(a)
        assertEquals(listOf(50000.0),pnl(rows));assertEquals(1L,rows.single().getLong("rank"))
        assertEquals(other.first,store.rankedHistory(b).single().getString("session"))
    }
    @Test fun legacyRepairYieldsToWorkerWritesAndCanResumeAfterCancellation() {
        val (id,key)=record(setOf(a),25.0,baseline=true)
        store.locked{db->
            val summary=db.rawQuery("SELECT summary FROM trials WHERE session=? AND key=?",arrayOf(id,key)).use{it.moveToFirst();it.getString(0)}
            repeat(450){n->
                val k=(n+1).toString(16)
                val row=org.json.JSONObject(summary).put("key",k).put("seq",n+2).put("profit",n.toDouble())
                db.execSQL("INSERT INTO trials(session,key,summary,report) VALUES(?,?,?,?)",arrayOf(id,k,row.toString(),byteArrayOf(1)))
            }
            db.execSQL("UPDATE robot_meta SET value=0 WHERE name='history_index'")
        }
        val writes=java.util.concurrent.atomic.AtomicInteger()
        var stop=false
        try{
            store.prepareHistory({
                // A different worker must be able to acquire the store lock between repair pages.
                val writer=Thread{store.locked{db->db.execSQL("UPDATE sessions SET created=created+1 WHERE id=?",arrayOf(id))};writes.incrementAndGet()}
                writer.start();writer.join(2000)
                assertFalse("index repair held the worker lock",writer.isAlive)
                stop=true
            },{stop})
            fail("cancelled repair must not advertise a complete index")
        }catch(_:IllegalStateException){}
        assertEquals(1,writes.get())
        store.locked{db->assertEquals(0,db.rawQuery("SELECT value FROM robot_meta WHERE name='history_index'",null).use{it.moveToFirst();it.getInt(0)})}
        val pages=mutableListOf<Long>()
        store.prepareHistory({pages+=it})
        assertTrue(pages.size>=3)
        assertEquals(451L,store.historyCount(a))
        assertEquals(449.0,pnl(store.rankedHistory(a)).first(),0.0)
    }
    @Test fun zeroCleanupAndLargePagedDeletionRespectStarsAndLateStarChanges() {
        val (id,key)=record(setOf(a),0.0,baseline=true)
        assertTrue(store.setFavorite(id,key,true));assertEquals(0,store.clearZero(id));assertNotNull(store.report(id,key))
        store.locked{db->
            val raw=db.rawQuery("SELECT summary FROM trials WHERE session=? AND key=?",arrayOf(id,key)).use{it.moveToFirst();it.getString(0)}
            repeat(450){i->
                val k=(i+1).toString(16)
                val row=org.json.JSONObject(raw).put("key",k).put("seq",i+2).put("profit",i.toDouble()).put("favorite",false)
                db.execSQL("INSERT INTO trials(session,key,summary,report,net_profit) VALUES(?,?,?,?,?)",arrayOf(id,k,row.toString(),byteArrayOf(1),i.toDouble()))
            }
        }
        store.locked{db->
            val session=store.session(id)!!.put("tested",451).put("bestKey",450.toString(16)).put("bestProfit",449.0).put("bestSeq",451)
            db.execSQL("UPDATE sessions SET json=? WHERE id=?",arrayOf(session.toString(),id))
        }
        val snapshot=store.unstarredSnapshot();assertEquals(450L,snapshot.second)
        val keepKey=450.toString(16)
        var pages=0
        val deleted=store.deleteUnstarred(snapshot.first){
            pages++
            if(pages==1)assertTrue(store.setFavorite(id,keepKey,true))
        }
        assertEquals(449L,deleted);assertTrue(pages>=3)
        assertEquals(2L,store.historyCount());assertFalse(store.deleteTrial(id,keepKey))
        assertEquals(keepKey,store.session(id)!!.getString("bestKey"));assertEquals(449L,store.session(id)!!.getLong("deleted"))
        assertTrue(store.seen(id,"1"));assertNull(store.report(id,"1"))
        assertEquals(0L,store.deleteUnstarred(snapshot.first))
    }
    private fun find(v:View,tag:String):View? {
        if(v.tag==tag)return v
        if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),tag)?.let{return it}
        return null
    }
    @Test fun categoryButtonsShowOnlyChosenCategoryInProfitOrder() {
        val one=record(setOf(a),100.0);val two=record(setOf(a),200.0);record(setOf(b),1000.0)
        val controller=Robolectric.buildActivity(BtRobotHistoryActivity::class.java).create();val activity=controller.get();val root=activity.window.decorView
        find(root,"robot-history-category-A")!!.performClick()
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(System.nanoTime()<end){
            shadowOf(Looper.getMainLooper()).idle()
            if((find(root,"robot-history-status") as TextView).text.contains("共 2 筆"))break
            Thread.yield()
        }
        val status=(find(root,"robot-history-status") as TextView).text.toString()
        assertTrue(status.contains(a.label));assertTrue(status.contains("由高到低"));assertTrue(find(root,"robot-history-category-A")!!.isSelected)
        val host=find(root,"robot-history-cards") as ViewGroup
        val expected=store.rankedHistory(a)
        assertEquals("robot-history-card-${expected.first().getLong("n")}",host.getChildAt(0).tag)
        assertEquals(setOf(one.first,two.first),expected.map{it.getString("session")}.toSet())
        assertEquals(4,host.childCount) // two cards and two spacers, no B card
        controller.destroy()
    }
}
