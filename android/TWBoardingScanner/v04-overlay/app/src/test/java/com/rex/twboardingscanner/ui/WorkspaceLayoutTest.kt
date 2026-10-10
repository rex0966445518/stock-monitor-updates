package com.rex.twboardingscanner.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.rex.twboardingscanner.R
import com.rex.twboardingscanner.data.StockDirectory
import com.rex.twboardingscanner.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Visual regression artifacts and interaction checks for the reorganized workspace. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceLayoutTest {
    private fun layout(v:View,height:Int=800){
        v.measure(View.MeasureSpec.makeMeasureSpec(NeonUi.dp(v.context,360),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(NeonUi.dp(v.context,height),View.MeasureSpec.EXACTLY))
        v.layout(0,0,v.measuredWidth,v.measuredHeight)
    }
    private fun check(v:View){
        if(v.visibility!=View.VISIBLE)return
        if(v is TextView&&v.text.isNotEmpty()&&v.layout!=null&&v !is EditText){
            assertTrue("Clipped text: ${v.text}",v.layout.height<=v.height-v.compoundPaddingTop-v.compoundPaddingBottom+2)
        }
        if(v is ViewGroup)repeat(v.childCount){check(v.getChildAt(it))}
    }
    private fun screenshot(v:View,name:String){
        fun settle(view:View){view.jumpDrawablesToCurrentState();if(view is ViewGroup)repeat(view.childCount){settle(view.getChildAt(it))}}
        settle(v)
        val bitmap=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(bitmap))
        val file=File("build/ui-previews/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun pagesAndExpandedSettingsRemainReadableAtLargeFont(){
        val app=RuntimeEnvironment.getApplication()
        StockDirectory(app).remember(listOf(MarketStock("2330","台積電",Market.TWSE,StockSector.ELECTRONICS,100.0,105.0,98.0,103.0,3.0,1383,null,1.0)))
        val screens=listOf<Class<out Activity>>(BacktestActivity::class.java,BtRobotActivity::class.java,PaperTradingActivity::class.java,StockToolsActivity::class.java,BacktestJournalActivity::class.java,AppUpdateActivity::class.java)
        for(scale in listOf(1f,1.3f)){
            RuntimeEnvironment.setFontScale(scale)
            for(type in screens){
                app.getSharedPreferences("ui_layout",0).edit().clear().commit()
                val controller=Robolectric.buildActivity(type).create().start();val a=controller.get()
                val content=a.findViewById<View>(android.R.id.content)
                layout(content);check(content)
                val back=content.findViewWithTag<View>("page-back")
                val rect=android.graphics.Rect(0,0,back.width,back.height)
                (content as ViewGroup).offsetDescendantRectToMyCoords(back,rect)
                assertTrue("Return button outside viewport in ${type.simpleName}: $rect",rect.left>=0&&rect.right<=content.width&&rect.bottom<=content.height)
                screenshot(content,"workspace-${type.simpleName}-${if(scale==1f)"360" else "large-font"}")
                if(a is BacktestActivity){
                    val button=content.findViewWithTag<View>("disclosure-backtest-trading");button.performClick()
                    layout(content);check(content);screenshot(content,"workspace-backtest-settings-${scale}")
                    val input=content.findViewWithTag<EditText>("max-holding-stocks");input.setText("12")
                    button.performClick();button.performClick();assertEquals("12",input.text.toString())
                    if(scale==1f){
                        content.findViewWithTag<View>("backtest-exit-rules").performClick()
                        val dialog=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
                        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                        val decor=dialog.window!!.decorView
                        layout(decor);check(decor)
                        val save=decor.findViewWithTag<View>("exit-save")
                        val bounds=android.graphics.Rect(0,0,save.width,save.height)
                        (decor as ViewGroup).offsetDescendantRectToMyCoords(save,bounds)
                        assertTrue("Save action must remain reachable: $bounds",save.isShown&&save.width>0&&save.height>=NeonUi.dp(a,48)&&bounds.bottom<=decor.height&&bounds.left>=0&&bounds.right<=decor.width)
                        screenshot(decor,"workspace-exit-editor-360")
                        save.performClick()
                        assertEquals(1,com.rex.twboardingscanner.data.ExitRuleStore(a).read(StockScope.BACKTEST).count{it.active})
                        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    }
                }
                if(a is StockToolsActivity){
                    for(mode in listOf("LIMIT","SEARCH")){
                        content.findViewWithTag<View>("tools-$mode").performClick();layout(content);check(content)
                        screenshot(content,"workspace-tools-$mode-$scale")
                    }
                }
                if(a is BacktestJournalActivity){a.renderEntries(emptyList());layout(content);check(content);screenshot(content,"workspace-journal-empty-$scale")}
                controller.stop().destroy()
            }
        }
    }
    @Test fun scannerSheetCanReopenWithoutLosingItsControls(){
        val app=RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get()
        val content=a.findViewById<View>(android.R.id.content);layout(content)
        repeat(2){
            a.findViewById<View>(R.id.settingsButton).performClick()
            val dialog=ShadowDialog.getLatestDialog();assertTrue(dialog.isShowing)
            val decor=dialog.window!!.decorView;layout(decor);check(decor)
            assertNotNull(decor.findViewById<View>(R.id.exitRulesButton))
            if(it==0)screenshot(decor,"workspace-scanner-settings")
            dialog.dismiss()
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertNotNull(content.findViewById<View>(R.id.exitRulesButton))
            assertEquals(View.GONE,a.findViewById<View>(R.id.settingsPanel).visibility)
        }
        controller.pause().stop().destroy()
    }
}
