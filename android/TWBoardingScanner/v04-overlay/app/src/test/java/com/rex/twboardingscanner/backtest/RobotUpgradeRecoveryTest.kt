package com.rex.twboardingscanner.backtest

import android.content.ContentValues
import android.content.ClipboardManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.ui.BtRobotActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class RobotUpgradeRecoveryTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val a=RadarType.A_EARLY_BREAKOUT
    private fun closeHelper(){
        val field=BtRobotStore::class.java.getDeclaredField("helper").apply{isAccessible=true}
        (field.get(null) as? android.database.sqlite.SQLiteOpenHelper)?.close();field.set(null,null)
    }
    @Before fun reset(){
        closeHelper();app.deleteDatabase("backtest-robot.db")
        listOf("backtest_robot","robot-params-draft").forEach{app.getSharedPreferences(it,0).edit().clear().commit()}
    }
    @After fun close(){closeHelper()}
    private fun settings()=BtSettings(LocalDate.of(2026,8,1),LocalDate.of(2026,8,8),enabledRadars=setOf(a),marketGuardVersion=1)
    private fun fixtureSession(id:String)=JSONObject().put("id",id).put("created",1).put("state","PAUSED").put("token","")
        .put("settings",BtSettingsCodec.encode(settings())).put("tested",2).put("cursor","2").put("seed","1")
        .put("bestKey","").put("bestSeq",0).put("ack",0).put("required","0").put("forbidden","0")
        .put("analysisVersion",2).put("searchVersion",9).put("appVersion","0.4.82").put("engineVersion",BtRobotCheckpoint.ENGINE)
        .put("cleared",0).put("message","legacy fixture")
    private fun legacy(name:String):String {
        val id=UUID.randomUUID().toString();val file=app.getDatabasePath(name);file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file,null).use{db->
            db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,created INTEGER NOT NULL,json TEXT NOT NULL)")
            db.execSQL("CREATE TABLE trials(n INTEGER PRIMARY KEY AUTOINCREMENT,session TEXT NOT NULL,key TEXT NOT NULL,summary TEXT NOT NULL,report BLOB NOT NULL,cleared INTEGER NOT NULL DEFAULT 0,UNIQUE(session,key))")
            db.execSQL("CREATE INDEX trial_session ON trials(session,n)")
            db.insertOrThrow("sessions",null,ContentValues().apply{put("id",id);put("created",1);put("json",fixtureSession(id).toString())})
            val key=BtRobotSpace.key(BtRobotSpace.rules("0")+mapOf(a to setOf("price")))
            val minimal=JSONObject().put("key",key).put("seq",1).put("profit",190000.0)
            listOf(key to minimal.toString(),"0" to "not valid JSON {").forEach{(k,summary)->db.insertOrThrow("trials",null,ContentValues().apply{
                put("session",id);put("key",k);put("summary",summary);put("report",byteArrayOf(1,2,3,4));put("cleared",0)
            })}
            db.version=2
        }
        return id
    }
    private fun find(v:View,tag:String):View? {
        if(v.tag==tag)return v
        if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),tag)?.let{return it}
        return null
    }
    @Test fun realVersionTwoUpgradeDoesNotParseHistoryOrLoseBytes() {
        val name="robot-upgrade-${UUID.randomUUID()}.db";legacy(name)
        BtRobotStore.Db(app,name).use{helper->
            val db=helper.writableDatabase
            assertEquals(4,db.version)
            db.rawQuery("SELECT value FROM robot_meta WHERE name='history_index'",null).use{it.moveToFirst();assertEquals(0,it.getInt(0))}
            db.rawQuery("SELECT report,summary FROM trials ORDER BY n",null).use{rows->
                assertTrue(rows.moveToFirst());assertArrayEquals(byteArrayOf(1,2,3,4),rows.getBlob(0))
                assertTrue(rows.moveToNext());assertEquals("not valid JSON {",rows.getString(1));assertArrayEquals(byteArrayOf(1,2,3,4),rows.getBlob(0))
            }
            RobotHistoryCategory.backfill(db)
            db.rawQuery("SELECT radars,net_profit,summary_valid FROM trials ORDER BY n",null).use{rows->
                rows.moveToFirst();assertEquals(RobotHistoryCategory.bit(a),rows.getInt(0));assertEquals(190000.0,rows.getDouble(1),0.0);assertEquals(1,rows.getInt(2))
                rows.moveToNext();assertEquals(0,rows.getInt(2))
            }
        }
        app.deleteDatabase(name)
    }
    @Test fun robotOpensAfterRealUpgradeWithMinimalAndCorruptLegacySummaries() {
        val id=legacy("backtest-robot.db")
        app.getSharedPreferences("backtest_robot",0).edit().putString("active",id).commit()
        runCatching{androidx.work.WorkManager.initialize(app,androidx.work.Configuration.Builder().build())}
        val controller=Robolectric.buildActivity(BtRobotActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(find(controller.get().window.decorView,"robot-load-error"))
        assertNotNull(find(controller.get().window.decorView,"robot-history-entry"))
        val store=BtRobotStore(app)
        assertEquals(1,store.trials(id).size)
        assertEquals(1L,store.historyCount(a))
        assertEquals(1,store.rankedHistory(a).size)
        assertEquals(2L,store.session(id)!!.getLong("tested"))
        controller.pause().stop().destroy()
    }
    @Test fun refreshFailureShowsRecoveryAndNeverOverwritesDraftOrThrowsOnResume() {
        runCatching{androidx.work.WorkManager.initialize(app,androidx.work.Configuration.Builder().build())}
        val store=BtRobotStore(app);val id=store.createSingle(settings())
        val controller=Robolectric.buildActivity(BtRobotActivity::class.java).setup();val activity=controller.get()
        store.locked{db->val broken=store.session(id)!!.put("settings","invalid snapshot")
            db.update("sessions",ContentValues().apply{put("json",broken.toString())},"id=?",arrayOf(id))
        }
        app.getSharedPreferences("robot-params-draft",0).edit().putString("holdings","24").commit()
        activity.render()
        assertNotNull(find(activity.window.decorView,"robot-load-error"))
        find(activity.window.decorView,"robot-copy-error")!!.performClick()
        val clipboard=app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertTrue(clipboard.primaryClip!!.getItemAt(0).text.toString().contains("機器人頁面錯誤"))
        controller.pause().resume();shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(find(activity.window.decorView,"robot-load-error"))
        assertEquals("24",app.getSharedPreferences("robot-params-draft",0).getString("holdings",null))
        assertNotNull(store.session(id))
        controller.pause().stop().destroy()
    }
}
