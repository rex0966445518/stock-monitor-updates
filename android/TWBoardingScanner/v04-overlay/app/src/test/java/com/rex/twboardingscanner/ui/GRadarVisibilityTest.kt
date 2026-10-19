package com.rex.twboardingscanner.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.rex.twboardingscanner.domain.GPattern
import com.rex.twboardingscanner.domain.RadarType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** Regression for phones that still displayed only A–F despite G source rules. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
class GRadarVisibilityTest {
    private val g=RadarType.G_MA5_WAVE

    private fun tagged(root:View?,name:String):View? {
        if(root==null)return null
        if(root.tag==name)return root
        if(root is ViewGroup)for(i in 0 until root.childCount) {
            tagged(root.getChildAt(i),name)?.let{return it}
        }
        return null
    }

    private fun checkButton(root:View?,tag:String) {
        val view=tagged(root,tag)
        assertNotNull("G entry missing: $tag",view)
        assertEquals("G entry hidden: $tag",View.VISIBLE,view!!.visibility)
        assertTrue("G entry not labeled: $tag",(view as TextView).text.toString().contains("G"))
    }

    private fun checkG1G2(dialog:AlertDialog,prefix:String) {
        assertTrue(dialog.isShowing)
        assertNotNull("G1 missing",tagged(dialog.window?.decorView,"$prefix-${g.name}-${GPattern.EVENT}"))
        assertNotNull("G2 missing",tagged(dialog.window?.decorView,"$prefix-${g.name}-${GPattern.PULLBACK}"))
    }

    @Test fun mainSettingsBottomSheetShowsGAndOpensGEditor() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get()
        MainActivity::class.java.getDeclaredMethod("showSettings").apply{isAccessible=true}.invoke(activity)
        val sheet=ShadowDialog.getLatestDialog() as BottomSheetDialog
        assertTrue(sheet.isShowing)
        val decor=sheet.window!!.decorView
        checkButton(decor,"scanner-enabled-${g.name}")
        val editor=tagged(decor,"scanner-g-conditions")
        checkButton(decor,"scanner-g-conditions")
        assertTrue(editor!!.performClick())
        val rules=ShadowDialog.getLatestDialog() as AlertDialog
        checkG1G2(rules,"scanner-rule")
        rules.dismiss()
        sheet.dismiss()
        controller.pause().stop().destroy()
    }

    @Test fun backtestHasIndependentGToggleAndGRulesEditor() {
        val controller=Robolectric.buildActivity(BacktestActivity::class.java).setup()
        val activity=controller.get()
        val decor=activity.window.decorView
        checkButton(decor,"backtest-enabled-${g.name}")
        checkButton(decor,"backtest-rules-${g.name}")
        assertTrue(tagged(decor,"backtest-rules-${g.name}")!!.performClick())
        val dialog=ShadowDialog.getLatestDialog() as AlertDialog
        checkG1G2(dialog,"backtest-rule")
        dialog.dismiss()
        controller.pause().stop().destroy()
    }

    @Test fun robotHasGToggleRulesEditorAndG3ToG5Management() {
        val controller=Robolectric.buildActivity(BtRobotActivity::class.java).setup()
        val activity=controller.get()
        val decor=activity.window.decorView
        checkButton(decor,"robot-enabled-${g.name}")
        checkButton(decor,"robot-rules-${g.name}")
        checkButton(decor,"robot-g-parameters")
        assertTrue(tagged(decor,"robot-rules-${g.name}")!!.performClick())
        val dialog=ShadowDialog.getLatestDialog() as AlertDialog
        checkG1G2(dialog,"required")
        dialog.dismiss()
        controller.pause().stop().destroy()
    }
}
