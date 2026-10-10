package com.rex.twboardingscanner.backtest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
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
class BacktestSettingsDiffTest {
    private fun settings()=BtSettings(LocalDate.of(2026,8,1),LocalDate.of(2026,10,8),codes="2330,3661",
        rules=RadarType.entries.associateWith{if(it==RadarType.A_EARLY_BREAKOUT)setOf("price","volume")else emptySet()},sectors=setOf(StockSector.SEMICONDUCTOR))
    private fun snapshot(s:BtSettings=settings())=BtSettingsCodec.encode(s)
    private fun entry(id:String,s:JSONObject,stamp:Long)=JSONObject().put("id",id).put("settings",s).put("startedAt",stamp).put("state","DONE").put("profit",13253)
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
    private fun text(v:View)=all(v).filterIsInstance<TextView>().joinToString("\n"){it.text.toString()}

    @Test fun comparesArchivedRulesAndTradingValuesWithoutChangingEitherSnapshot(){
        val old=settings().copy(exitRules=ExitRules.example(),stockPolicy=StockPolicy(setOf("2330"),500.0))
        val now=old.copy(rules=old.rules+mapOf(RadarType.A_EARLY_BREAKOUT to setOf("volume","macd"),RadarType.B_DEEP_REVERSAL to setOf("floor")),
            sectors=setOf(StockSector.SEMICONDUCTOR,StockSector.SHIPPING),maxHoldingStocks=15,targetNetPct=5.0,
            stockPolicy=StockPolicy(setOf("3661"),400.0),exitRules=listOf(old.exitRules.first().copy(days=20,maDays=null)))
        val a=snapshot(old);val b=snapshot(now);val originalA=a.toString();val originalB=b.toString()
        val diff=BacktestSettingsDiff.compare(b,a);val lines=diff.changes.map{it.text}
        assertTrue(diff.unavailable.isEmpty())
        assertTrue(diff.changes.any{it.kind==SettingChangeKind.ADDED&&it.text.startsWith("A 區 · MACD")})
        assertTrue(diff.changes.any{it.kind==SettingChangeKind.REMOVED&&it.text=="A 區 · 股價 ≥ 50 元"})
        assertTrue(lines.contains("最高持倉：25 檔 → 15 檔"))
        assertTrue(lines.contains("獲利賣出：3%（扣費稅） → 5%（扣費稅）"))
        assertTrue(lines.contains("下車第 1 組／留倉天數：超過 15 個日曆日 → 超過 20 個日曆日"))
        assertTrue(diff.changes.any{it.kind==SettingChangeKind.REMOVED&&it.text=="下車第 1 組／跌破均線 · 20 日線"})
        assertTrue(diff.changes.any{it.kind==SettingChangeKind.ADDED&&it.text=="禁買名單 · 3661"})
        assertTrue(diff.changes.any{it.kind==SettingChangeKind.REMOVED&&it.text=="禁買名單 · 2330"})
        assertEquals(originalA,a.toString());assertEquals(originalB,b.toString())
    }
    @Test fun orderAndFormattingAndEmptyExitSlotsDoNotCreateFalseDifferences(){
        val a=snapshot();val b=JSONObject(a.toString())
        b.getJSONObject("rules").put(RadarType.A_EARLY_BREAKOUT.name,JSONArray(listOf("volume","price","price")))
        b.put("codes","3661， 2330  2330").put("capital",3000000).put("targetNetPct",3)
            .put("exitRules",ExitRules.json(List(5){ExitRule()})).put("strategyLabel","不同顯示文字").put("ruleLabels",JSONObject())
        val diff=BacktestSettingsDiff.compare(b,a)
        assertTrue(diff.changes.isEmpty());assertTrue(diff.unavailable.isEmpty())
    }
    @Test fun removedNamesComeFromTheCorrectIdAndMissingSnapshotsAreNeverEmptySelections(){
        val a=snapshot();a.remove("ruleLabelMap")
        // Legacy label arrays use declaration order, whereas ID arrays were alphabetically sorted.
        a.getJSONObject("rules").put(RadarType.A_EARLY_BREAKOUT.name,JSONArray(listOf("macd","price")))
        a.getJSONObject("ruleLabels").put(RadarType.A_EARLY_BREAKOUT.name,JSONArray(listOf("股價 ≥ 50 元","MACD 起轉")))
        val b=snapshot(settings().copy(rules=settings().rules+mapOf(RadarType.A_EARLY_BREAKOUT to setOf("macd"))))
        val diff=BacktestSettingsDiff.compare(b,a)
        assertEquals(listOf(SettingChange(SettingChangeKind.REMOVED,"A 區 · 股價 ≥ 50 元")),diff.changes)
        val partial=JSONObject(a.toString());partial.remove("rules");partial.remove("stockPolicy")
        val unavailable=BacktestSettingsDiff.compare(b,partial)
        assertTrue(unavailable.changes.none{it.text.startsWith("A 區")||it.text.startsWith("禁買名單")})
        assertTrue(unavailable.unavailable.contains("A 區"));assertTrue(unavailable.unavailable.contains("禁買／限價設定"))
        assertTrue(BacktestSettingsDiff.compare(b,null).changes.isEmpty())
    }
    @Test fun versionAndDefinitionChangesRemainVisible(){
        val a=snapshot();val b=JSONObject(a.toString())
        b.getJSONObject("ruleLabelMap").getJSONObject(RadarType.A_EARLY_BREAKOUT.name).put("price","股價 ≥ 60 元")
        b.put("rulesVersion","future-version")
        val diff=BacktestSettingsDiff.compare(b,a)
        assertTrue(diff.changes.contains(SettingChange(SettingChangeKind.CHANGED,"A 區 · 股價 ≥ 50 元 → 股價 ≥ 60 元")))
        assertTrue(diff.changes.any{it.text.startsWith("條件定義版本：")})
    }
    @Test fun banCountsUseEachSavedSnapshotNotTheCurrentGlobalList(){
        val zero=snapshot();val two=snapshot(settings().copy(stockPolicy=StockPolicy(setOf("2330","3661"))))
        val missing=JSONObject(zero.toString()).apply{remove("stockPolicy")}
        val duplicate=JSONObject(two.toString()).apply{getJSONObject("stockPolicy").put("banned",JSONArray(listOf("2330","3661","2330")))}
        assertEquals(0,BacktestJournalUi.bannedCount(zero));assertEquals(2,BacktestJournalUi.bannedCount(duplicate));assertNull(BacktestJournalUi.bannedCount(missing))
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("stock-entry-policy",0).edit().putString("policy",StockPolicy(setOf("1597")).json().toString()).commit()
        val ctl=Robolectric.buildActivity(BacktestJournalActivity::class.java).create().start();val a=ctl.get()
        a.renderEntries(listOf(entry("two",two,3),entry("zero",zero,2),entry("unknown",missing,1)))
        val root=a.window.decorView
        assertEquals("禁股 2 檔",root.findViewWithTag<TextView>("journal-banned-two").text.toString())
        assertEquals("禁股 0 檔",root.findViewWithTag<TextView>("journal-banned-zero").text.toString())
        assertEquals("禁股數未記錄",root.findViewWithTag<TextView>("journal-banned-unknown").text.toString())
        assertEquals(setOf("1597"),com.rex.twboardingscanner.data.StockPolicyStore(app).read().banned)
        ctl.stop().destroy()
    }
    @Test fun paginationComparesWithTheNextOlderRecordEvenOutsideTheVisiblePage(){
        val ctl=Robolectric.buildActivity(BacktestJournalActivity::class.java).create().start();val a=ctl.get()
        val rows=(0..21).map{i->entry("run-$i",snapshot(settings().copy(targetNetPct=(i+1).toDouble())),1000L+i*1000)}
        a.renderEntries(rows) // deliberately oldest first; UI must normalize chronological order
        val decor=a.window.decorView
        val boundary=decor.findViewWithTag<View>("journal-diff-run-2")
        assertTrue(text(boundary).contains("run-1"))
        assertTrue(text(boundary).contains("2%（扣費稅） → 3%（扣費稅）"))
        assertNull(decor.findViewWithTag<View>("journal-diff-run-1"))
        decor.findViewWithTag<View>("journal-load-more").performClick()
        assertNotNull(decor.findViewWithTag<View>("journal-diff-run-1"))
        assertTrue(text(decor.findViewWithTag<View>("journal-diff-run-0")).contains("無前次紀錄"))
        ctl.stop().destroy()
    }
    @Test fun diffPreviewAndExpandedDetailsFitPhonesAndIncludeAllChangeKinds(){
        val ctl=Robolectric.buildActivity(BacktestJournalActivity::class.java).create().start();val a=ctl.get()
        val prior=entry("previous",snapshot(),1791619200000L).put("state","CANCELED")
        val s=settings();val changed=s.copy(rules=s.rules+mapOf(RadarType.A_EARLY_BREAKOUT to setOf("macd","rsi","converge","ma5up")),
            targetNetPct=5.0,maxHoldingStocks=15,capital=5000000.0,exitRules=ExitRules.example(),stockPolicy=StockPolicy(setOf("1597","2330","3661")))
        val current=entry("current",snapshot(changed),1791622800000L)
        for(scale in listOf(1f,1.3f)){
            RuntimeEnvironment.setFontScale(scale)
            val view=BacktestDiffView(a,current,prior)
            assertTrue(text(view).contains("已停止"))
            assertTrue(text(view).contains("＋ 新增"));assertTrue(text(view).contains("− 移除"));assertTrue(text(view).contains("↔ 修改"))
            capture(view,"journal-diff-preview-$scale")
            view.findViewWithTag<View>("journal-diff-toggle").performClick()
            BacktestSettingsDiff.compare(current.getJSONObject("settings"),prior.getJSONObject("settings")).changes.forEach{assertTrue(text(view).contains(it.text))}
            capture(view,"journal-diff-expanded-$scale")
            a.renderEntries(listOf(current,prior));capture(a.window.decorView,"journal-diff-page-$scale",800)
        }
        val same=BacktestDiffView(a,current,entry("same",snapshot(changed),1L));assertTrue(text(same).contains("條件與前次相同"))
        val missing=BacktestDiffView(a,current,JSONObject().put("id","old"));assertTrue(text(missing).contains("無法確認是否相同"))
        ctl.stop().destroy()
    }
    private fun capture(v:View,name:String,height:Int?=null){
        val width=NeonUi.dp(v.context,if(height==null)312 else 360)
        v.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(if(height==null)0 else NeonUi.dp(v.context,height),if(height==null)View.MeasureSpec.UNSPECIFIED else View.MeasureSpec.EXACTLY))
        v.layout(0,0,width,v.measuredHeight)
        all(v).filter{it.visibility==View.VISIBLE}.forEach{child->
            child.jumpDrawablesToCurrentState()
            if(child is TextView&&child.layout!=null&&child.text.isNotEmpty())assertTrue("Clipped ${child.text}",child.layout.height<=child.height-child.compoundPaddingTop-child.compoundPaddingBottom+2)
        }
        val bmp=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(bmp))
        val f=File("build/ui-previews/$name.png");f.parentFile.mkdirs();f.outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)};bmp.recycle()
    }
}
