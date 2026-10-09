package com.rex.twboardingscanner.backtest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.ui.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.*
import java.math.BigInteger
import java.time.LocalDate
import java.util.zip.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BtRobotCheckpointTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun clear(){app.getSharedPreferences("backtest_robot",0).edit().clear().commit()}
    private fun data():BacktestData.Loaded {
        val end=LocalDate.of(2026,8,5)
        val bars=(0..85).map{i->val p=70.0+i*.3;DailyBar(end.minusDays((85-i).toLong()).atTime(9,0).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),p-.2,p+1,p-1,p,1000000)}
        return BacktestData.Loaded(listOf(BtSeries("2330","測試資料",Market.TWSE,bars)),1,emptyList())
    }
    private fun fixture(s:BtSettings,pnl:Double)=BtResult(s,BtRun(s.capital,cash=s.capital+pnl,strategyVersion=4),1,1,emptyList(),"fixture only")
    private fun add(store:BtRobotStore,id:String,token:String,pnl:Double):String {
        val candidate=store.next(id)!!;val s=BtSettingsCodec.decode(store.session(id)!!.getJSONObject("settings")).copy(rules=BtRobotSpace.rules(candidate.key))
        store.commit(id,token,candidate,fixture(s,pnl),System.currentTimeMillis());return candidate.key
    }
    private fun snapshot(store:BtRobotStore,id:String,token:String){
        val loaded=data();BtRobotDataset.write(store.dataset(id),loaded)
        val sha=BtRobotCheckpoint.digest(store.dataset(id));store.coverage(id,token,1,1,sha)
        val s=BtSettingsCodec.decode(store.session(id)!!.getJSONObject("settings"))
        BtConditionIndex.build(loaded.series,s).save(store.conditionFile(id),BtConditionIndex.fingerprint(sha,s))
    }
    @Test fun lockedConditionsExhaustOnlyFreeBitsAndNeverTurnRequiredOff(){
        val all=BtRobotSpace.total-BigInteger.ONE
        val required=all.clearBit(0).clearBit(24).clearBit(64).toString(16)
        assertEquals(BigInteger.valueOf(8),BtRobotSpace.total(required))
        for(seed in listOf(BigInteger.ZERO,BigInteger.ONE,BigInteger("923468"))){
            val seen=mutableSetOf<String>();var cursor=BigInteger.ZERO
            repeat(8){val c=BtRobotSpace.next("0",all.toString(16),cursor,seed,required){it in seen}!!
                assertTrue(BtRobotSpace.includes(c.key,required));assertTrue(seen.add(c.key));cursor=c.cursor
            }
            assertNull(BtRobotSpace.next("0",all.toString(16),cursor,seed,required){it in seen})
            assertEquals(8,seen.size)
        }
        val one=all.toString(16);val c=BtRobotSpace.next("0",null,BigInteger.ZERO,BigInteger.ONE,one){false}!!
        assertEquals(one,c.key);assertNull(BtRobotSpace.next("0",null,c.cursor,BigInteger.ONE,one){true})
    }
    @Test fun cleaningZeroKeepsLedgerProgressAndNonzeroTrades(){
        val store=BtRobotStore(app);val id=store.create(BtRobotSpace.settings());val token=store.resume(id)
        val zero=add(store,id,token,0.0);val negative=add(store,id,token,-500.0);val positive=add(store,id,token,900.0)
        val next=store.next(id)!!;val remaining=store.remaining(id)
        assertEquals(1,store.clearZero(id));assertEquals(0,store.clearZero(id))
        assertNull(store.report(id,zero));assertTrue(store.seen(id,zero));assertEquals(2,store.trials(id).size)
        assertNotNull(store.report(id,negative));assertNotNull(store.report(id,positive))
        assertEquals(3L,store.session(id)!!.getLong("tested"));assertEquals(1L,store.session(id)!!.getLong("cleared"))
        assertEquals(remaining,store.remaining(id));assertEquals(next,store.next(id));assertEquals(900.0,store.session(id)!!.getDouble("bestProfit"),0.0)
        store.pause(id)
    }
    @Test fun cleanupCrossesManyDatabasePagesWithoutSkippingZeroRows(){
        val store=BtRobotStore(app);val id=store.create(BtRobotSpace.settings())
        store.locked{db->db.beginTransaction();try{
            repeat(1205){i->db.insertOrThrow("trials",null,android.content.ContentValues().apply{
                put("session",id);put("key",i.toString(16));put("summary",JSONObject().put("profit",if(i==600)1.0 else 0.0).toString());put("report",ByteArray(8))
            })}
            val s=store.session(id)!!.put("tested",1205);db.update("sessions",android.content.ContentValues().apply{put("json",s.toString())},"id=?",arrayOf(id));db.setTransactionSuccessful()
        }finally{db.endTransaction()}}
        assertEquals(1204,store.clearZero(id));assertEquals(1L,store.retained(id));assertEquals(1205L,store.session(id)!!.getLong("tested"));assertTrue(store.seen(id,1204.toString(16)))
    }
    @Test fun cleanedZeroBestFallsBackToRetainedBestAndNoTrialsAreRepeated(){
        val store=BtRobotStore(app);val id=store.create(BtRobotSpace.settings());val token=store.resume(id)
        val zero=add(store,id,token,0.0);val neg=add(store,id,token,-50.0);store.clearZero(id)
        assertEquals(neg,store.session(id)!!.getString("bestKey"));assertTrue(store.seen(id,zero));assertNotEquals(zero,store.next(id)!!.key);store.pause(id)
    }
    @Test fun diskConditionsAndNewRequiredBatchReuseExactPreparedFacts(){
        val store=BtRobotStore(app);val s=BtRobotSpace.settings();val id=store.create(s);val token=store.resume(id);snapshot(store,id,token)
        val original=BtConditionIndex.build(data().series,s).prepare(s)
        val loaded=BtConditionIndex.read(store.conditionFile(id),data().series,BtConditionIndex.fingerprint(store.session(id)!!.getString("datasetSha256"),s)).prepare(s)
        assertEquals(original.signals,loaded.signals);assertEquals(original.pendingChecks,loaded.pendingChecks)
        assertThrows(Exception::class.java){BtConditionIndex.read(store.conditionFile(id),data().series,"wrong data")}
        store.pause(id);val newer=store.create(s,"1");BtRobotCheckpoint.cloneData(store,id,newer)
        assertArrayEquals(store.conditionFile(id).readBytes(),store.conditionFile(newer).readBytes())
        assertArrayEquals(store.dataset(id).readBytes(),store.dataset(newer).readBytes());assertEquals(BigInteger.ONE.shiftLeft(64),store.remaining(newer))
    }
    @Test fun portableArchiveRestoresEveryCompletedTrialClearedMarkerAndNextCandidate(){
        val store=BtRobotStore(app);val s=BtRobotSpace.settings();val id=store.create(s,"1");val token=store.resume(id);snapshot(store,id,token)
        val zero=add(store,id,token,0.0);val win=add(store,id,token,400.0);add(store,id,token,-90.0);store.clearZero(id);store.pause(id)
        val next=store.next(id)!!;val archive=File(app.cacheDir,"robot-roundtrip.zip");BtRobotCheckpoint.export(app,id,archive)
        ZipFile(archive).use{assertNotNull(it.getEntry("results.csv"));assertNotNull(it.getEntry("conditions.bin.gz"))}
        val restored=archive.inputStream().use{BtRobotCheckpoint.restore(app,it)}
        assertNotEquals(id,restored);assertEquals("PAUSED",store.session(restored)!!.getString("state"));assertEquals("1",store.session(restored)!!.getString("required"))
        assertEquals(3L,store.session(restored)!!.getLong("tested"));assertEquals(1L,store.session(restored)!!.getLong("cleared"))
        assertTrue(store.seen(restored,zero));assertNull(store.report(restored,zero))
        assertEquals(store.report(id,win).toString(),store.report(restored,win).toString())
        assertEquals(next,store.next(restored));assertEquals(store.remaining(id),store.remaining(restored))
        assertArrayEquals(store.dataset(id).readBytes(),store.dataset(restored).readBytes());assertArrayEquals(store.conditionFile(id).readBytes(),store.conditionFile(restored).readBytes())
        val t=store.resume(restored);add(store,restored,t,700.0);assertEquals(4L,store.session(restored)!!.getLong("tested"));assertEquals(3L,store.session(id)!!.getLong("tested"));store.pause(restored)
    }
    @Test fun invalidImportNeverReplacesOrPausesExistingSession(){
        val store=BtRobotStore(app);val id=store.create(BtRobotSpace.settings());val token=store.resume(id)
        val zip=ByteArrayOutputStream();ZipOutputStream(zip).use{it.putNextEntry(ZipEntry("../bad"));it.write(byteArrayOf(1));it.closeEntry()}
        assertThrows(Exception::class.java){BtRobotCheckpoint.restore(app,ByteArrayInputStream(zip.toByteArray()))}
        assertEquals(id,store.active());assertTrue(store.current(id,token));assertFalse(File(app.cacheDir,"bad").exists());store.pause(id)
        val archive=File(app.cacheDir,"robot-corrupt.zip");BtRobotCheckpoint.export(app,id,archive)
        val changed=ByteArrayOutputStream();ZipOutputStream(changed).use{out->ZipFile(archive).use{source->source.entries().asSequence().forEach{entry->out.putNextEntry(ZipEntry(entry.name));if(entry.name=="session.json")out.write("{}".toByteArray())else source.getInputStream(entry).use{it.copyTo(out)};out.closeEntry()}}}
        val count=store.sessions().size;assertThrows(Exception::class.java){BtRobotCheckpoint.restore(app,ByteArrayInputStream(changed.toByteArray()))};assertEquals(count,store.sessions().size);assertEquals(id,store.active())
    }
    @Test fun recoveryWaitsTenSecondsAndNeverDuplicatesQueuedWorkOrOverridesManualPause(){
        assertEquals(1,BtRobotWorker.recoveryAction("RUNNING",false,1000,1001))
        assertEquals(0,BtRobotWorker.recoveryAction("RETRY",false,1000,10999))
        assertEquals(2,BtRobotWorker.recoveryAction("RETRY",false,1000,11000))
        for(state in listOf("PAUSED","DONE","ERROR"))assertEquals(0,BtRobotWorker.recoveryAction(state,false,1000,99999))
        for(state in listOf("RUNNING","RETRY"))assertEquals(0,BtRobotWorker.recoveryAction(state,true,1000,99999))
        val store=BtRobotStore(app);val id=store.create(BtRobotSpace.settings());val t=store.resume(id)
        store.status(id,t,"retry","RETRY");assertTrue(store.current(id,t));store.pause(id);assertFalse(store.current(id,t))
        store.status(id,t,"stale retry","RETRY");assertEquals("PAUSED",store.session(id)!!.getString("state"))
        assertTrue(BtRobotCheckpoint.compatible(JSONObject().put("appVersion","0.4.24")))
        assertFalse(BtRobotCheckpoint.compatible(JSONObject().put("engineVersion","future").put("appVersion","0.4.24")))
    }
    @Test fun requiredMenuSeparatesAbcAndRendersWithClearAndExportActions(){
        val store=BtRobotStore(app);store.create(BtRobotSpace.settings(),"1")
        val ctl=Robolectric.buildActivity(BtRobotActivity::class.java).setup();val a=ctl.get()
        assertNotNull(find(a.window.decorView){it.tag=="robot-clear-zero"});assertNotNull(find(a.window.decorView){it is TextView&&it.text.toString()=="匯出結果／斷點"})
        var selected="";BtRobotRulesDialog.show(a,"1"){selected=it}
        val dialog=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        (find(dialog.window!!.decorView){it.tag=="required-A_EARLY_BREAKOUT-volume"} as CheckBox).performClick()
        find(dialog.window!!.decorView){it is TextView&&it.text.toString()=="C 爆量"}!!.performClick()
        (find(dialog.window!!.decorView){it.tag=="required-C_LONG_RED_VOLUME-red"} as CheckBox).performClick()
        capture(dialog.window!!.decorView,"robot-required-360.png",1450)
        val save=find(dialog.window!!.decorView){it.tag=="save-required-rules"}!!
        val decor=dialog.window!!.decorView as ViewGroup;val rect=android.graphics.Rect(0,0,save.width,save.height);decor.offsetDescendantRectToMyCoords(save,rect)
        assertTrue("required actions clipped $rect",rect.height()>=80&&rect.bottom<=decor.height&&rect.top>=0)
        save.performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val rules=BtRobotSpace.rules(selected);assertEquals(setOf("price","volume"),rules[RadarType.A_EARLY_BREAKOUT]);assertEquals(setOf("red"),rules[RadarType.C_LONG_RED_VOLUME]);assertTrue(rules[RadarType.B_DEEP_REVERSAL]!!.isEmpty())
        capture(a.window.decorView.findViewById(android.R.id.content),"robot-tools-360.png",2200)
        assertEquals("AI-離職神器",app.applicationInfo.loadLabel(app.packageManager).toString())
        ctl.pause().stop().destroy()
    }
    private fun find(v:View,p:(View)->Boolean):View?{if(p(v))return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),p)?.let{return it};return null}
    private fun capture(v:View,name:String,height:Int){v.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));v.layout(0,0,720,height);val image=Bitmap.createBitmap(720,height,Bitmap.Config.ARGB_8888);v.draw(Canvas(image));val file=File("build/ui-previews/$name");file.parentFile!!.mkdirs();file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
}
