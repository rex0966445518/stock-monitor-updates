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
            RadarType.E_DIF_TOUCH->ScoringEngine().evaluateE(snap,setOf("price","volume"))
            RadarType.F_VOLUME_CONTRACTION->ScoringEngine().evaluateF(snap,setOf("price","volume"))
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
    @Test fun fullResultsSurviveDatabaseReopenWithPendingChecksEvidenceAndEmptyLatestScan(){
        val t=at("2026-10-09")
        val a=sample("2330",RadarType.A_EARLY_BREAKOUT).copy(
            light=SignalLight.NONE,
            checks=listOf(ConditionCheck("eps","EPS",CheckState.PENDING,true,false)),
            snapshot=sample("2330",RadarType.A_EARLY_BREAKOUT).snapshot.copy(
                marketIndexCloses=mapOf(LocalDate.of(2026,10,8) to 22000.0)))
        val db=ScanArchiveStore(app)
        val folder=db.addCompleteScan("2026-10-09",t,settings(t),listOf(a),emptyList())
        assertTrue(db.rows(folder.id).isEmpty())
        db.close()
        ScanArchiveStore(app).use{reopened->
            val restored=reopened.latestSaved()!!
            assertEquals(folder.id,restored.id)
            assertEquals(listOf(a),restored.main)
            assertTrue(restored.paper.isEmpty())
            assertEquals(CheckState.PENDING,restored.main.single().checks.single().state)
            val second=reopened.addCompleteScan("2026-10-09",t+1,settings(t+1),emptyList())
            assertEquals(second.id,reopened.latestSaved()!!.id)
            assertTrue(reopened.latestSaved()!!.main.isEmpty())
            assertEquals(listOf(a),reopened.saved(folder.id)!!.main)
        }
    }
    @Test fun recreatingHomeRestoresCompletedScanAndFolderEntryIsVisible(){
        val t=at("2026-10-09")
        val signals=listOf(sample("2330",RadarType.A_EARLY_BREAKOUT))
        ScanArchiveStore(app).use{it.addCompleteScan("2026-10-09",t,settings(t),signals)}
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        repeat(2){
            val controller=Robolectric.buildActivity(com.rex.twboardingscanner.ui.MainActivity::class.java).setup()
            val activity=controller.get()
            val executor=com.rex.twboardingscanner.ui.MainActivity::class.java.getDeclaredField("coordinator").apply{isAccessible=true}.get(activity) as java.util.concurrent.ExecutorService
            executor.submit{}.get(10,java.util.concurrent.TimeUnit.SECONDS)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val latest=com.rex.twboardingscanner.ui.MainActivity::class.java.getDeclaredField("latest").apply{isAccessible=true}.get(activity)
            assertEquals(signals,latest)
            com.rex.twboardingscanner.ui.MainActivity::class.java.getDeclaredMethod("requestConfiguredScan").apply{isAccessible=true}.invoke(activity)
            assertEquals(signals,com.rex.twboardingscanner.ui.MainActivity::class.java.getDeclaredField("latest").apply{isAccessible=true}.get(activity))
            assertTrue(activity.findViewById<TextView>(com.rex.twboardingscanner.R.id.progressTitle).text.contains("已還原"))
            val button=activity.findViewById<View>(com.rex.twboardingscanner.R.id.logButton)
            assertEquals(View.VISIBLE,(button.parent as View).visibility)
            controller.pause().stop().destroy()
        }
    }
    @Test fun payloadsLargerThanOneChunkAreStoredWithoutCursorWindowOverflow(){
        val t=at("2026-10-09")
        val huge=sample("2330",RadarType.A_EARLY_BREAKOUT).copy(light=SignalLight.NONE,
            reasons=(0 until 24000).map{java.util.UUID.randomUUID().toString()})
        ScanArchiveStore(app).use{db->
            val folder=db.addCompleteScan("2026-10-09",t,settings(t),listOf(huge),emptyList())
            val count=db.readableDatabase.rawQuery("SELECT COUNT(*) FROM saved_results WHERE folder_id=?",arrayOf(folder.id)).use{c->c.moveToFirst();c.getInt(0)}
            assertTrue(count>1)
            assertEquals(huge,db.saved(folder.id)!!.main.single())
        }
    }
    @Test fun versionFourUpgradePreservesLegacyFoldersAndDoesNotInventTradingProfit(){
        val legacy=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath("scanner-folders-v1.db"),null)
        legacy.execSQL("CREATE TABLE folders(id TEXT PRIMARY KEY,date TEXT,created INTEGER,name TEXT,signature TEXT,settings TEXT,details BLOB,a INTEGER,b INTEGER,c INTEGER,d INTEGER,e INTEGER,f INTEGER,unique_count INTEGER,cash REAL,changes TEXT)")
        val oldSettings=settings(at("2026-10-09")).put("schema",1).apply{remove("profitTracking")}
        val rows=java.io.ByteArrayOutputStream().let{buffer->
            java.util.zip.GZIPOutputStream(buffer).use{it.write("[]".toByteArray())};buffer.toByteArray()
        }
        legacy.execSQL("INSERT INTO folders VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf("legacy","2026-10-09",1L,"舊掃描","sig",oldSettings.toString(),rows,0,0,0,0,0,0,0,0.0,"保留"))
        legacy.version=4;legacy.close()
        ScanArchiveStore(app).use{db->
            assertEquals("legacy",db.list().single().id)
            assertNull(db.latestSaved())
            assertFalse(db.settings("legacy")!!.optBoolean("profitTracking",false))
            val t=at("2026-10-09")
            val new=db.addCompleteScan("2026-10-09",t,settings(t),emptyList())
            assertEquals(2,db.list().size)
            assertEquals(new.id,db.latestSaved()!!.id)
        }
    }
    @Test fun sixRadarAndPaperSnapshotsShareCandlesAndRestoreTheOldUnpooledFormat(){
        val t=at("2026-10-09")
        val original=sample("2330",RadarType.A_EARLY_BREAKOUT).copy(light=SignalLight.NONE)
        val all=RadarType.entries.map{original.copy(radarType=it)}
        val value=ScanSavedResults("shared","2026-10-09",t,all,all,RadarType.entries.toSet(),setOf(StockSector.SEMICONDUCTOR),StockPolicy(),1,1,0)
        val bytes=ScanSavedCodec.encode(value)
        val restored=ScanSavedCodec.decode(bytes)
        assertEquals(value,restored)
        assertSame(restored.main[0],restored.paper[0])
        assertTrue(restored.main.all{it.snapshot.bars===restored.main[0].snapshot.bars})
        val newJson=java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(bytes)).bufferedReader().use{it.readText()}
        val wire=org.json.JSONObject(newJson).getJSONObject("payload")
        val legacyObject=org.json.JSONObject(wire.getJSONObject("scan").toString())
        fun expand(key:String):org.json.JSONArray {
            val result=org.json.JSONArray()
            val ids=wire.getJSONArray(key)
            for(i in 0 until ids.length()){
                val signal=wire.getJSONArray("signals").getJSONObject(ids.getInt(i))
                val row=org.json.JSONObject(signal.getJSONObject("result").toString())
                row.getJSONObject("snapshot").put("bars",wire.getJSONArray("bars").getJSONArray(signal.getInt("series")))
                result.put(row)
            }
            return result
        }
        val legacyJson=legacyObject.put("main",expand("main")).put("paper",expand("paper")).toString()
        val legacy=java.io.ByteArrayOutputStream().let{buf->java.util.zip.GZIPOutputStream(buf).use{it.write(legacyJson.toByteArray())};buf.toByteArray()}
        val older=ScanSavedCodec.decode(legacy)
        assertEquals(value,older)
        assertTrue(older.main.all{it.snapshot.bars[0]===older.main[0].snapshot.bars[0]})
        assertSame(older.main[0].snapshot.bars[0],older.paper[5].snapshot.bars[0])
        assertTrue("Candles should not be stored twelve times",newJson.length*3<legacyJson.length)
    }
    @Test fun fullMarketSixRadarSaveAndRestoreKeepsOnlyOneCandleSeriesPerStock(){
        val t=at("2026-10-09")
        val original=sample("2330",RadarType.A_EARLY_BREAKOUT).copy(light=SignalLight.NONE)
        val all=(1000 until 3000).flatMap{code->
            val snapshot=original.snapshot.copy(code=code.toString(),bars=(0 until 320).map{i->
                DailyBar(t-(320-i)*86400000L,code+i*.01,code+1.0+i*.01,code-1.0+i*.01,code+i*.01,1000000L+i)
            })
            RadarType.entries.map{original.copy(code=code.toString(),radarType=it,snapshot=snapshot)}
        }
        ScanArchiveStore(app).use{db->
            val folder=db.addCompleteScan("2026-10-09",t,settings(t),all,all)
            val restored=db.saved(folder.id)!!
            assertEquals(all,restored.main)
            assertEquals(12000,restored.main.size)
            assertSame(restored.main[0],restored.paper[0])
            assertEquals(2000,restored.main.map{it.snapshot.bars}.distinct().size)
            assertTrue(restored.main.chunked(6).all{group->group.all{it.snapshot.bars===group[0].snapshot.bars}})
        }
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
