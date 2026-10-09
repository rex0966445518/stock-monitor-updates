package com.rex.twboardingscanner.backtest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
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
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BacktestJournalTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val day=LocalDate.of(2025,1,2)
    private fun settings()=BtSettings(day,day.plusDays(10),codes="2330,3661",rules=RadarType.entries.associateWith{if(it==RadarType.A_EARLY_BREAKOUT)setOf("price","volume") else emptySet()},sectors=setOf(StockSector.SEMICONDUCTOR))
    private fun result(s:BtSettings)=BtResult(s,BtRun(s.capital).apply{curve.add(BtDay(s.start,s.capital))},2,2,emptyList(),"日誌保存測試")
    @Before fun clean(){
        File(app.filesDir,"backtests").deleteRecursively();File(app.filesDir,"backtest-journal").deleteRecursively()
        app.getSharedPreferences("backtest_status",0).edit().clear().commit();app.getSharedPreferences("scanner_filters",0).edit().clear().commit()
    }
    @Test fun completedRunsRemainSeparateAndResetKeepsReportsAndSettings(){
        val store=BacktestStore(app);val a=settings();store.begin("first",a);assertTrue(store.save("first",result(a)))
        val b=a.copy(capital=5000000.0,rules=a.rules+mapOf(RadarType.A_EARLY_BREAKOUT to setOf("macd")))
        store.begin("second",b);assertTrue(store.save("second",result(b)))
        store.resetCurrent();val reopened=BacktestStore(app)
        assertEquals("",reopened.active());assertEquals("",reopened.resultId());assertNull(reopened.result());assertEquals("IDLE",reopened.state())
        assertEquals(2,reopened.history().size);assertEquals(5000000.0,reopened.configuration()!!.getDouble("capital"),.01)
        val first=reopened.log("first")!!;val second=reopened.log("second")!!
        assertEquals(a.rules,BtSettingsCodec.decode(first.getJSONObject("settings")).rules)
        assertEquals(b.rules,BtSettingsCodec.decode(second.getJSONObject("settings")).rules)
        assertTrue(first.getLong("finishedAt")>=first.getLong("startedAt"));assertTrue(first.getLong("startedAt")>0)
        assertTrue(first.getJSONObject("settings").getJSONObject("ruleLabels").toString().contains("股價"))
        assertTrue(BacktestJournalUi.describe(first.getJSONObject("settings")).contains("半導體"))
        assertFalse(store.save("first",result(b)))
        assertThrows(IllegalArgumentException::class.java){store.begin("first",b)}
    }
    @Test fun resettingRunningJobPreventsLateWorkerFromRestoringResults(){
        val store=BacktestStore(app);val workerStore=BacktestStore(app);val s=settings();store.begin("reset",s)
        store.resetCurrent();workerStore.update("reset","late progress");workerStore.update("reset","late failure","ERROR")
        assertFalse(workerStore.save("reset",result(s)));assertNull(store.result())
        val log=store.log("reset")!!;assertEquals("CANCELED",log.getString("state"));assertFalse(log.has("run"));assertTrue(log.getString("message").contains("歸零"))
        assertEquals("IDLE",store.state());assertEquals(1,store.history().size)
    }
    @Test fun replacementAndErrorsAreLoggedWithoutFabricatedPerformance(){
        val store=BacktestStore(app);val s=settings();store.begin("replaced",s);store.begin("new",s)
        assertEquals("CANCELED",store.log("replaced")!!.getString("state"));assertFalse(store.save("replaced",result(s)))
        store.update("new","來源暫時失敗","ERROR");store.resetCurrent()
        assertEquals(2,store.history().size);assertEquals("ERROR",store.log("new")!!.getString("state"));assertFalse(store.log("new")!!.has("run"))
    }
    @Test fun legacyReportsAreImportedWithoutAlteringTheirBytes(){
        val store=BacktestStore(app);val s=settings();store.begin("legacy",s);store.save("legacy",result(s))
        val file=File(app.filesDir,"backtests/legacy.json");val old=JSONObject(file.readText())
        listOf("startedAt","finishedAt","state","appVersion","journalVersion").forEach{old.remove(it)}
        old.getJSONObject("settings").remove("ruleLabels");file.writeText(old.toString());val before=file.readBytes()
        File(app.filesDir,"backtest-journal/legacy.json").delete()
        val history=store.history();assertEquals(1,history.size);assertTrue(history.single().getBoolean("legacyImported"))
        assertArrayEquals(before,file.readBytes());assertEquals(1,store.history().size)
        assertTrue(BacktestJournalUi.describe(store.log("legacy")!!.getJSONObject("settings")).contains("舊版僅保存條件代碼"))
    }
    @Test fun conditionsAreIndependentAndExistingRunSnapshotNeverChanges(){
        val live=app.getSharedPreferences("scanner_filters",0)
        val key="rules_${ScanConditions.VERSION}_${RadarType.A_EARLY_BREAKOUT.name}";live.edit().putStringSet(key,setOf("macd")).commit()
        val s=settings();val store=BacktestStore(app);store.begin("frozen",s)
        val ctl=Robolectric.buildActivity(BacktestActivity::class.java).setup();val activity=ctl.get()
        activity.applyRules(RadarType.A_EARLY_BREAKOUT,setOf("price","extra_eps"))
        activity.applyRules(RadarType.B_DEEP_REVERSAL,emptySet())
        assertEquals(setOf("macd"),live.getStringSet(key,null))
        assertEquals(setOf("price","extra_eps"),BtSettingsCodec.decode(store.configuration()!!).rules[RadarType.A_EARLY_BREAKOUT])
        assertEquals(s.rules,BtSettingsCodec.decode(store.log("frozen")!!.getJSONObject("settings")).rules)
        assertTrue(store.save("frozen",result(s)))
        assertEquals(s.rules,BtSettingsCodec.decode(store.log("frozen")!!.getJSONObject("settings")).rules)
        activity.clearCurrent();assertNull(store.result());assertEquals(1,store.history().size)
        capture(activity.window.decorView,"backtest-reset-360")
        ctl.pause().stop().destroy()
        val reopened=Robolectric.buildActivity(BacktestActivity::class.java).setup()
        assertEquals(setOf("price","extra_eps"),BtSettingsCodec.decode(store.configuration()!!).rules[RadarType.A_EARLY_BREAKOUT])
        reopened.pause().stop().destroy()
    }
    @Test fun journalCardsShowAllStatesWithoutClipping(){
        val store=BacktestStore(app);val s=settings();store.begin("complete-one",s);store.save("complete-one",result(s))
        store.begin("stopped-two",s);store.resetCurrent();store.begin("error-three",s);store.update("error-three","測試資料來源中斷","ERROR")
        val ctl=Robolectric.buildActivity(BacktestJournalActivity::class.java).create().start()
        ctl.get().renderEntries(store.history());capture(ctl.get().window.decorView,"backtest-journal-360")
        ctl.stop().destroy()
    }
    @Test fun actualCheckboxDialogAppliesTheChosenConditions(){
        val ctl=Robolectric.buildActivity(BacktestActivity::class.java).setup()
        fun find(v:View,match:(View)->Boolean):View?{if(match(v))return v;if(v is ViewGroup)for(i in 0 until v.childCount){val found=find(v.getChildAt(i),match);if(found!=null)return found};return null}
        val activity=ctl.get()
        find(activity.window.decorView){it is TextView&&it.text.toString().startsWith("A 條件")}!!.performClick()
        val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val box=find(dialog.window!!.decorView){it is android.widget.CheckBox&&it.text.toString().startsWith("MACD 起轉")} as android.widget.CheckBox
        assertTrue(box.isChecked);box.performClick();assertFalse(box.isChecked)
        capture(dialog.window!!.decorView,"backtest-rule-picker-360")
        val apply=find(dialog.window!!.decorView){it is TextView&&it.text.toString()=="套用"}!!
        val visible=android.graphics.Rect();assertTrue("套用必須出現在可視區",apply.getGlobalVisibleRect(visible));assertTrue(visible.height()>=80)
        apply.performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val selected=BtSettingsCodec.decode(BacktestStore(app).configuration()!!).rules[RadarType.A_EARLY_BREAKOUT]!!
        assertFalse("macd" in selected);assertTrue("price" in selected)
        assertFalse(app.getSharedPreferences("scanner_filters",0).contains("rules_${ScanConditions.VERSION}_${RadarType.A_EARLY_BREAKOUT.name}"))
        ctl.pause().stop().destroy()
    }
    private fun capture(view:View,name:String){
        view.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1600,View.MeasureSpec.EXACTLY));view.layout(0,0,720,1600)
        fun check(v:View){if(v is TextView&&v.layout!=null&&v.text.isNotEmpty())assertTrue("clipped: ${v.text}",v.layout.height<=v.height-v.compoundPaddingTop-v.compoundPaddingBottom+2);if(v is ViewGroup)for(i in 0 until v.childCount)check(v.getChildAt(i))}
        check(view)
        fun settle(v:View){v.jumpDrawablesToCurrentState();if(v is ViewGroup)for(i in 0 until v.childCount)settle(v.getChildAt(i))};settle(view)
        val image=Bitmap.createBitmap(720,1600,Bitmap.Config.ARGB_8888);view.draw(Canvas(image))
        val f=File("build/ui-previews/$name.png");f.parentFile.mkdirs();f.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
}
