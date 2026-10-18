package com.rex.twboardingscanner.backtest

import android.content.ComponentName
import android.content.pm.ServiceInfo
import android.os.Looper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class RobotForegroundCheckpointTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private fun closeStore(){
        val f=BtRobotStore::class.java.getDeclaredField("helper").apply{isAccessible=true}
        (f.get(null) as? android.database.sqlite.SQLiteOpenHelper)?.close()
        f.set(null,null)
    }
    @Before fun reset(){
        closeStore()
        app.deleteDatabase("backtest-robot.db")
        app.getSharedPreferences("backtest_robot",0).edit().clear().commit()
    }
    @After fun cleanup(){closeStore()}

    @Test fun robotForegroundServiceIsDataSyncAndCanOutliveActivity(){
        val info=app.packageManager.getServiceInfo(ComponentName(app,BtRobotService::class.java),0)
        assertFalse(info.exported)
        assertEquals(0,info.flags and ServiceInfo.FLAG_STOP_WITH_TASK)
        assertTrue(info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC!=0)
    }

    @Test fun monotonicSameTrialStageAndWorkerCannotOverwriteService(){
        val store=BtRobotStore(app)
        val s=BtSettings(LocalDate.of(2026,8,1),LocalDate.of(2026,9,30),
            enabledRadars=setOf(com.rex.twboardingscanner.domain.RadarType.D_MAIN_FORCE),
            marketGuardVersion=1)
        val id=store.createSingle(s)
        val token=store.resume(id)
        val owner="service:first"
        assertTrue(store.claim(id,token,owner))
        assertFalse(store.claim(id,token,"worker:other"))
        assertTrue(store.stage(id,token,owner,"分段套用條件 224/1261 檔",224,1261))
        assertTrue(store.stage(id,token,owner,"分段套用條件 104/1261 檔",104,1261))
        assertEquals(224,store.session(id)!!.getInt("phaseDone"))
        assertTrue(store.session(id)!!.getString("message").contains("224/1261"))
        assertFalse(store.stage(id,token,"worker:other","分段套用條件 300/1261 檔",300,1261))
        assertEquals(224,store.session(id)!!.getInt("phaseDone"))
        assertTrue(store.heartbeat(id,token,owner))
        assertTrue(store.freshService(id,token))
        assertTrue(store.release(id,token,owner,"已保存斷點"))
        assertEquals("RETRY",store.session(id)!!.getString("state"))
        assertEquals(224,store.session(id)!!.getInt("phaseDone"))
        assertTrue(store.claim(id,token,"worker:new"))
        assertFalse(store.owns(id,token,owner))
        store.pause(id)
        assertFalse(store.current(id,token))
    }

    @Test fun preparedChunksRoundTripAndCorruptedCheckpointRejected(){
        val day=LocalDate.of(2026,10,15)
        val signal=BtSignal("2330","D",day,"缺口 123.45 | 一段可恢復記錄")
        val dir=File(app.cacheDir,"robot-chunk-test-"+UUID.randomUUID()).apply{mkdirs()}
        val file=File(dir,"0.bin.gz")
        try{
            BtRobotPreparedChunk.save(file,"frozen:strategy:chunk0",mapOf(day to listOf(signal)),12)
            val restored=BtRobotPreparedChunk.read(file,"frozen:strategy:chunk0")
            assertEquals(12,restored.missing)
            assertEquals(listOf(signal),restored.signals[day])
            assertTrue(runCatching{BtRobotPreparedChunk.read(file,"different-strategy")}.isFailure)
            file.writeBytes(byteArrayOf(1,2,3,4))
            assertTrue(runCatching{BtRobotPreparedChunk.read(file,"frozen:strategy:chunk0")}.isFailure)
        }finally{dir.deleteRecursively()}
    }

    @Test fun foregroundNotificationShowsActualTrialAndStage(){
        val store=BtRobotStore(app)
        val s=BtSettings(LocalDate.of(2026,8,1),LocalDate.of(2026,9,30),
            enabledRadars=setOf(com.rex.twboardingscanner.domain.RadarType.D_MAIN_FORCE),marketGuardVersion=1)
        val id=store.createSingle(s)
        val token=store.resume(id)
        assertTrue(store.claim(id,token,"service:one"))
        store.stage(id,token,"service:one","分段套用條件 224/1261 檔",224,1261)
        val note=BtRobotNotification.build(app,store.session(id)!!)
        assertEquals(224,note.extras.getInt(android.app.Notification.EXTRA_PROGRESS))
        assertEquals(1261,note.extras.getInt(android.app.Notification.EXTRA_PROGRESS_MAX))
        assertTrue(note.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().contains("224/1261"))
        assertTrue(note.actions.isNotEmpty())
    }
}
