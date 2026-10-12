package com.rex.twboardingscanner.ui

import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.rex.twboardingscanner.backtest.BtSettings
import com.rex.twboardingscanner.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
class EControlsTest{
    private fun find(v:View,tag:String):View?{
        if(v.tag==tag)return v
        if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),tag)?.let{return it}
        return null
    }
    @Test fun actualScannerEEditorPersistsThresholdAndIncludeCrossIndependently(){
        val ctl=Robolectric.buildActivity(MainActivity::class.java).setup()
        val host=ctl.get();val root=host.window.decorView
        assertNotNull(find(root,"scanner-enabled-E_DIF_TOUCH"))
        assertTrue(find(root,"scanner-e-conditions")!!.performClick())
        val menu=ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(find(menu.window!!.decorView,"scanner-e-parameters")!!.performClick())
        val editor=ShadowDialog.getLatestDialog() as AlertDialog
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        (find(editor.window!!.decorView,"e-tolerance") as EditText).setText("0.15")
        (find(editor.window!!.decorView,"e-include-cross") as CheckBox).isChecked=false
        editor.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(editor.isShowing)
        assertEquals(EPatternConfig(.15,false),EPatternConfig.read(JSONObject(host.getSharedPreferences("scanner_filters",0).getString("e_pattern_config",null)!!)))
        menu.dismiss();ctl.pause().stop().destroy()
    }
    @Test fun paperECanBeActivatedAndIsCapturedForTheSavedStrategy(){
        val ctl=Robolectric.buildActivity(PaperStrategyActivity::class.java).setup()
        val host=ctl.get()
        assertTrue(find(host.window.decorView,"paper-rules-E_DIF_TOUCH")!!.performClick())
        val menu=ShadowDialog.getLatestDialog() as AlertDialog
        val enabled=find(menu.window!!.decorView,"paper-enabled-E_DIF_TOUCH") as CheckBox
        enabled.isChecked=true
        menu.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val settings=host.capture()
        assertTrue(RadarType.E_DIF_TOUCH in settings.enabledRadars)
        assertTrue(EPattern.EVENT in settings.rules.getValue(RadarType.E_DIF_TOUCH))
        ctl.pause().stop().destroy()
    }
}
