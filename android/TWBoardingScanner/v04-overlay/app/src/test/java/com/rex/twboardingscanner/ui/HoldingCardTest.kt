package com.rex.twboardingscanner.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.rex.twboardingscanner.R
import com.rex.twboardingscanner.data.StockPolicyStore
import com.rex.twboardingscanner.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HoldingCardTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun clean(){app.getSharedPreferences("stock-entry-policy",0).edit().clear().commit()}
    private fun lot()=JSONObject().put("code","1597").put("name","直得").put("shares",1000).put("radar","C")
        .put("entryDate","2026-10-01").put("entryTime","13:30（收盤模型）").put("entry",155.0)
        .put("mark",142.5).put("markDate","2026-10-08").put("cost",155221.0)
        .put("holdingChange",-12.5).put("holdingChangePct",-8.06).put("unrealized",-13353.0).put("unrealizedPct",-8.6)
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
    private fun button(v:View)=v.findViewWithTag<TextView>("holding-ban")
    private fun times(v:View)=all(v).filter{it.tag=="holding-purchase-time"}.map{(it as TextView).text.toString()}
    @Test fun banPersistsForAllScopesAndUpdatesEveryVisibleLot(){
        val ctl=Robolectric.buildActivity(Activity::class.java).setup().visible();val a=ctl.get();a.setTheme(R.style.Theme_TWBoardingScanner)
        val store=StockPolicyStore(a)
        store.change{StockPolicy(setOf("2330"),100.0,StockScope.entries.associateWith{setOf("1597")})}
        val data=lot();val original=data.toString()
        val first=BacktestProfitCard(a,data,false,"2026-10-08")
        val second=BacktestProfitCard(a,lot().put("entryDate","2026-09-30"),false,"2026-10-08")
        a.setContentView(NeonUi.vertical(a).apply{addView(first);addView(second)})
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(first.isAttachedToWindow);assertTrue(second.isAttachedToWindow)
        assertTrue(button(first).isEnabled);button(first).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val saved=StockPolicyStore(a).read()
        assertEquals(setOf("1597","2330"),saved.banned)
        StockScope.entries.forEach{assertEquals(StockDecision.BANNED,saved.decision("1597",155.0,it))}
        assertEquals("直得",StockPolicyStore(a).name("1597"))
        for(card in listOf(first,second)){assertEquals("已禁買",button(card).text.toString());assertFalse(button(card).isEnabled)}
        assertEquals(original,data.toString())
        val reopened=BacktestProfitCard(a,lot(),false,"2026-10-08")
        assertEquals("已禁買",button(reopened).text.toString());assertFalse(button(reopened).isEnabled)
        store.change{it.copy(banned=it.banned-"1597")}
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(button(first).isEnabled);assertTrue(button(second).isEnabled)
        ctl.pause().stop().destroy()
    }
    @Test fun bothPriceBoxesShowRecordedPurchaseTimeWithoutInventingMissingValues(){
        val ctl=Robolectric.buildActivity(Activity::class.java).setup();val a=ctl.get();a.setTheme(R.style.Theme_TWBoardingScanner)
        assertEquals(listOf("買入 10月1日 13時30分","買入 10月1日 13時30分"),times(BacktestProfitCard(a,lot(),false,"2026-10-08")))
        for(raw in listOf("","09:00–13:30","25:90")){
            val card=BacktestProfitCard(a,lot().put("entryTime",raw),false,"2026-10-08")
            assertEquals(List(2){"買入 10月1日 時間未記錄"},times(card))
        }
        val missing=BacktestProfitCard(a,JSONObject(),false,"2026-10-08")
        assertFalse(button(missing).isEnabled)
        assertEquals(List(2){"買入 日期未記錄 時間未記錄"},times(missing))
        val sold=BacktestProfitCard(a,lot(),true,"2026-10-08")
        assertNull(button(sold));assertTrue(times(sold).isEmpty())
        ctl.pause().stop().destroy()
    }
    @Test fun narrowCardAndLargeFontKeepActionAndTimeInsideTheCard(){
        val ctl=Robolectric.buildActivity(Activity::class.java).setup();val a=ctl.get();a.setTheme(R.style.Theme_TWBoardingScanner)
        for(scale in listOf(1f,1.3f)){
            RuntimeEnvironment.setFontScale(scale)
            val card=BacktestProfitCard(a,lot(),false,"2026-10-08")
            val width=NeonUi.dp(a,336)
            card.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            card.layout(0,0,width,card.measuredHeight)
            all(card).forEach{v->
                v.jumpDrawablesToCurrentState()
                if(v is TextView&&v.layout!=null&&v.text.isNotEmpty()){
                    assertTrue("Clipped ${v.text}",v.layout.height<=v.height-v.compoundPaddingTop-v.compoundPaddingBottom+2)
                    for(line in 0 until v.layout.lineCount)assertTrue("Wide ${v.text}",v.layout.getLineWidth(line)<=v.width-v.compoundPaddingLeft-v.compoundPaddingRight+2)
                }
            }
            assertTrue(button(card).height>=NeonUi.dp(a,48))
            val bitmap=Bitmap.createBitmap(card.width,card.height,Bitmap.Config.ARGB_8888);card.draw(Canvas(bitmap))
            val file=File("build/ui-previews/holding-actions-${scale}.png");file.parentFile.mkdirs()
            file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
        ctl.pause().stop().destroy()
    }
}
