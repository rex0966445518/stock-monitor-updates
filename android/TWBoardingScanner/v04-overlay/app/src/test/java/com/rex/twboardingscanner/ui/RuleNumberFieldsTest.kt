package com.rex.twboardingscanner.ui

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.rex.twboardingscanner.backtest.*
import com.rex.twboardingscanner.domain.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RuleNumberFieldsTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val d=RadarType.D_MAIN_FORCE
    private val f=RadarType.F_VOLUME_CONTRACTION
    @Before fun clean(){
        runCatching{androidx.work.WorkManager.initialize(app,androidx.work.Configuration.Builder().build())}
        for(name in listOf("scanner_filters","backtest_robot","robot-params-draft"))app.getSharedPreferences(name,0).edit().clear().commit()
    }
    private fun find(v:View,p:(View)->Boolean):View?{if(p(v))return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),p)?.let{return it};return null}
    private fun current()=ShadowDialog.getLatestDialog() as AlertDialog
    private fun idle(){Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()}
    private fun number(dialog:AlertDialog,id:String,key:String)=dialog.window!!.decorView.findViewWithTag<EditText>("rule-number-$id-$key")!!
    private fun open(a:Any,type:RadarType){a.javaClass.getDeclaredMethod(if(a is MainActivity)"showRadarConditions" else "editRules",RadarType::class.java).apply{isAccessible=true}.invoke(a,type);idle()}
    private fun clickApply(dialog:AlertDialog){find(dialog.window!!.decorView){it is TextView&&it.text.toString()=="套用"}!!.performClick();idle()}
    private fun mainD(a:MainActivity)=MainActivity::class.java.getDeclaredField("dConfig").apply{isAccessible=true}.get(a) as DPatternConfig
    @Test fun allPlaceholdersHaveNumericFieldsAndSharedDRangeStaysInSync(){
        val a=Robolectric.buildActivity(MainActivity::class.java).setup()
        open(a.get(),d);val dialog=current()
        number(dialog,DPattern.LIMIT,"d-lookback").setText("30")
        assertEquals("30",number(dialog,DPattern.GREEN,"d-lookback").text.toString())
        assertEquals("30",number(dialog,DPattern.GAP,"d-lookback").text.toString())
        number(dialog,DPattern.LIMIT,"d-limit").setText("2")
        number(dialog,DPattern.GREEN,"d-green").setText("5")
        number(dialog,DPattern.VOLUME,"d-volumeDays").setText("4")
        number(dialog,DPattern.VOLUME,"d-base").setText("25")
        number(dialog,DPattern.VOLUME,"d-multiple").setText("2.5")
        dialog.getButton(-1).performClick();idle()
        assertEquals(DPatternConfig(30,2,5,4,25,2.5),mainD(a.get()))
        open(a.get(),d);assertEquals("2.5",number(current(),DPattern.VOLUME,"d-multiple").text.toString());current().dismiss()
        for(type in RadarType.entries)assertFalse(ScanConditions.forRadar(type).any{Regex("\\b[NKM]\\b").containsMatchIn(it.label)&&it.id.startsWith("d_")})
        a.pause().stop().destroy()
    }
    @Test fun cancelAndInvalidNumbersDoNotChangeLiveSettings(){
        val a=Robolectric.buildActivity(MainActivity::class.java).setup();val before=mainD(a.get())
        open(a.get(),d);val dialog=current()
        number(dialog,DPattern.LIMIT,"d-lookback").setText("")
        dialog.getButton(-1).performClick();assertTrue(dialog.isShowing);assertEquals(before,mainD(a.get()))
        number(dialog,DPattern.LIMIT,"d-lookback").setText("3")
        dialog.getButton(-1).performClick();assertTrue(dialog.isShowing);assertEquals(before,mainD(a.get()))
        number(dialog,DPattern.LIMIT,"d-lookback").setText("40")
        dialog.getButton(-2).performClick();assertEquals(before,mainD(a.get()))
        a.pause().stop().destroy()
    }
    @Test fun backtestDFValuesSaveTogetherWithChecksAndReopen(){
        val ctl=Robolectric.buildActivity(BacktestActivity::class.java).setup();val a=ctl.get()
        open(a,d);number(current(),DPattern.LIMIT,"d-lookback").setText("35");clickApply(current())
        open(a,f);val dialog=current()
        number(dialog,FPattern.EVENT,"f-days").setText("3");number(dialog,FPattern.EVENT,"f-pct").setText("75.5")
        number(dialog,"f_average","f-averageDays").setText("10");number(dialog,"f_average","f-averagePct").setText("70")
        number(dialog,"f_ma","f-ma").setText("20");number(dialog,"f_gain","f-gainDays").setText("15")
        number(dialog,"f_gain","f-gain").setText("12.5");number(dialog,"f_min_volume","f-lots").setText("1500")
        clickApply(dialog)
        val settings=BacktestActivity::class.java.getDeclaredMethod("draft").apply{isAccessible=true}.invoke(a) as BtSettings
        assertEquals(35,settings.dConfig.lookback);assertEquals(FPatternConfig(3,75.5,10,70.0,1500,20,15,12.5),settings.fConfig)
        open(a,f);assertEquals("20",number(current(),"f_ma","f-ma").text.toString());current().dismiss()
        ctl.pause().stop().destroy()
    }
    @Test fun robotSavesNumberOnlyChangeAsNewBatchWithoutChangingRuleMask(){
        val store=BtRobotStore(app);val old=store.create(BtRobotSpace.settings().copy(stockScope=StockScope.ROBOT))
        val key=store.session(old)!!.getString("required")
        val ctl=Robolectric.buildActivity(BtRobotActivity::class.java).setup();val a=ctl.get()
        a.window.decorView.findViewWithTag<View>("robot-rules-${d.name}").performClick();idle()
        val dialog=current();number(dialog,DPattern.LIMIT,"d-lookback").setText("45")
        find(dialog.window!!.decorView){it is TextView&&it.text.toString()==f.label}!!.performClick()
        number(dialog,FPattern.EVENT,"f-days").setText("3")
        find(dialog.window!!.decorView){it is TextView&&it.text.toString()==d.label}!!.performClick()
        assertEquals("45",number(dialog,DPattern.LIMIT,"d-lookback").text.toString())
        dialog.window!!.decorView.findViewWithTag<View>("save-required-rules").performClick()
        val until=System.nanoTime()+5_000_000_000L
        while(store.active()==old&&System.nanoTime()<until){Thread.sleep(10);idle()}
        assertNotEquals(old,store.active());val saved=BtSettingsCodec.decode(store.session()!!.getJSONObject("settings"))
        assertEquals(45,saved.dConfig.lookback);assertEquals(3,saved.fConfig.days)
        assertEquals(key,store.session()!!.getString("required"))
        assertEquals(20,BtSettingsCodec.decode(store.session(old)!!.getJSONObject("settings")).dConfig.lookback)
        ctl.pause().stop().destroy()
    }
    @Test fun directNumberInputsRenderOnCompactDialog(){
        val ctl=Robolectric.buildActivity(BacktestActivity::class.java).setup();open(ctl.get(),d)
        val v=current().window!!.decorView
        v.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1500,View.MeasureSpec.EXACTLY));v.layout(0,0,720,1500)
        val image=android.graphics.Bitmap.createBitmap(720,1500,android.graphics.Bitmap.Config.ARGB_8888);v.draw(android.graphics.Canvas(image))
        val file=java.io.File("build/ui-previews/inline-d-rule-numbers.png");file.parentFile!!.mkdirs();file.outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        val input=number(current(),DPattern.LIMIT,"d-lookback");assertTrue(input.width>=160);assertTrue(input.height>=88)
        current().dismiss();ctl.pause().stop().destroy()
    }
}
