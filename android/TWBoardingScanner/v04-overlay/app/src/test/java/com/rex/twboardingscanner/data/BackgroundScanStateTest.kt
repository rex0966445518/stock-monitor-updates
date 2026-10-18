package com.rex.twboardingscanner.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class BackgroundScanStateTest {
    private val app get()=RuntimeEnvironment.getApplication()

    @Before fun clear(){
        app.getSharedPreferences("main-market-scan-state-v1",0).edit().clear().commit()
    }

    @Test fun frozenRulesAndProgressSurviveUiRecreation(){
        val config=JSONObject().put("date","2026-10-18")
            .put("enabledRadars",org.json.JSONArray(listOf("A_EARLY_BREAKOUT")))
            .put("rules",JSONObject().put("A_EARLY_BREAKOUT",org.json.JSONArray(listOf("price","volume"))))
        val id=BackgroundScanState.begin(app,config)
        assertEquals("QUEUED",BackgroundScanState.read(app).state)
        assertEquals(config.toString(),BackgroundScanState.settings(app,id).toString())
        BackgroundScanState.report(app,id,"逐檔掃描",done=85,total=100,analyzed=80,excluded=3,failed=2,pending=4)
        val status=BackgroundScanState.read(app)
        assertTrue(status.active)
        assertEquals(85,status.percent)
        assertEquals(4,status.pending)
        assertEquals("逐檔掃描",status.message)
        BackgroundScanState.complete(app,id,"folder-123","完成")
        assertFalse(BackgroundScanState.read(app).active)
        assertEquals("folder-123",BackgroundScanState.read(app).folderId)
        BackgroundScanState.report(app,id,"過時的工作不應覆蓋資料",state="RETRY")
        assertEquals("DONE",BackgroundScanState.read(app).state)
    }

    @Test fun cancelRejectsLateWorkerAndNextRunStartsClean(){
        val first=BackgroundScanState.begin(app,JSONObject().put("date","2026-10-18"))
        BackgroundScanState.cancel(app,first)
        assertEquals("CANCELED",BackgroundScanState.read(app).state)
        val second=BackgroundScanState.begin(app,JSONObject().put("date","2026-10-19"))
        assertNotEquals(first,second)
        BackgroundScanState.report(app,first,"old work",done=99,total=100)
        assertEquals(0,BackgroundScanState.read(app).done)
        BackgroundScanState.failure(app,first,"old failure")
        assertEquals("QUEUED",BackgroundScanState.read(app).state)
        BackgroundScanState.failure(app,second,"actual error")
        assertEquals("ERROR",BackgroundScanState.read(app).state)
    }
    @Test fun onlyHeartbeatOwnerCanAdvanceAndStaleOwnerCanBeTakenOver(){
        val id=BackgroundScanState.begin(app,JSONObject().put("date","2026-10-18"))
        assertFalse(BackgroundScanState.freshService(app,id,now=1000))
        assertTrue(BackgroundScanState.claim(app,id,"service:first",now=1000))
        assertFalse(BackgroundScanState.claim(app,id,"worker:other",now=2000))
        BackgroundScanState.report(app,id,"正常執行",done=15,total=100,owner="service:first")
        assertTrue(BackgroundScanState.owns(app,id,"service:first"))
        val first=BackgroundScanState.read(app)
        assertTrue(first.lastAdvanceAt>0)
        BackgroundScanState.report(app,id,"舊工作不可覆蓋",done=33,total=100,owner="worker:other")
        assertEquals(15,BackgroundScanState.read(app).done)
        assertTrue(BackgroundScanState.claim(app,id,"worker:recovery",staleMs=45_000,now=first.heartbeatAt+46_000))
        assertFalse(BackgroundScanState.owns(app,id,"service:first"))
        BackgroundScanState.report(app,id,"舊工作不可覆蓋",done=99,total=100,owner="service:first")
        assertEquals(15,BackgroundScanState.read(app).done)
        BackgroundScanState.report(app,id,"備援運作",done=20,total=100,owner="worker:recovery")
        assertEquals(20,BackgroundScanState.read(app).done)
        BackgroundScanState.complete(app,id,"wrong-folder","wrong",owner="service:first")
        assertTrue(BackgroundScanState.read(app).active)
        BackgroundScanState.complete(app,id,"folder-ok","完成",owner="worker:recovery")
        assertEquals("folder-ok",BackgroundScanState.read(app).folderId)
    }

}