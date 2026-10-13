package com.rex.twboardingscanner.ui

import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.backtest.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
class RadarOptionsAuditTest {
    private fun find(v:View,predicate:(View)->Boolean):View? {
        if(predicate(v))return v
        if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),predicate)?.let{return it}
        return null
    }
    private fun view(v:View,tag:String)=find(v){it.tag==tag}!!
    private val core=mapOf(
        RadarType.A_EARLY_BREAKOUT to setOf("macd","ma5up","above3","box","burst"),
        RadarType.B_DEEP_REVERSAL to setOf("drawdown","floor","higherLow","declineVolume","ma5"),
        RadarType.C_LONG_RED_VOLUME to setOf("red","gain","body","burst","closeHigh"),
        RadarType.D_MAIN_FORCE to setOf(DPattern.LIMIT,DPattern.GREEN,DPattern.GAP,DPattern.VOLUME),
        RadarType.E_DIF_TOUCH to setOf(EPattern.EVENT,EPattern.CROSS),
        RadarType.F_VOLUME_CONTRACTION to setOf(FPattern.EVENT,"f_average","f_dif")
    ).mapValues{it.value+ScanConditions.MOMENTUM_IDS}
    @Test fun allSixFamiliesExposeEveryRuleAndSaveCoreSelectionsInScannerAndBacktest(){
        assertEquals(listOf("準備起漲","深跌反轉","突發爆量","主力進場","DIF金叉","縮量上漲"),RadarType.entries.map{it.displayName})
        val main=Robolectric.buildActivity(MainActivity::class.java).setup()
        val bt=Robolectric.buildActivity(BacktestActivity::class.java).setup()
        for(type in RadarType.entries){
            val rules=ScanConditions.forRadar(type)
            assertTrue(rules.map{it.id}.containsAll(core.getValue(type)))
            MainActivity::class.java.getDeclaredMethod("showRadarConditions",RadarType::class.java).apply{isAccessible=true}.invoke(main.get(),type)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            var dialog=ShadowDialog.getLatestDialog() as AlertDialog
            rules.forEach{rule->
                val box=view(dialog.window!!.decorView,"scanner-rule-${type.name}-${rule.id}") as CheckBox
                val was=box.isChecked;box.performClick();assertEquals(!was,box.isChecked)
                box.isChecked=rule.id in core.getValue(type)
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            val saved=main.get().getSharedPreferences("scanner_filters",0).getStringSet("rules_${ScanConditions.VERSION}_${type.name}",null)
            assertEquals(core.getValue(type),saved)
            BacktestActivity::class.java.getDeclaredMethod("editRules",RadarType::class.java).apply{isAccessible=true}.invoke(bt.get(),type)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            dialog=ShadowDialog.getLatestDialog() as AlertDialog
            rules.forEach{rule->
                val box=view(dialog.window!!.decorView,"backtest-rule-${type.name}-${rule.id}") as CheckBox
                val was=box.isChecked;box.performClick();assertEquals(!was,box.isChecked)
                box.isChecked=rule.id in core.getValue(type)
            }
            find(dialog.window!!.decorView){it is TextView&&it.text.toString()=="套用"}!!.performClick()
            val draft=BacktestActivity::class.java.getDeclaredMethod("draft").apply{isAccessible=true}.invoke(bt.get()) as BtSettings
            assertEquals(core.getValue(type),draft.rules[type])
        }
        bt.pause().stop().destroy();main.pause().stop().destroy()
    }
    @Test fun robotCoreOptionsCanBeRequiredAndSavedForEveryCategory(){
        val ctl=Robolectric.buildActivity(BtRobotActivity::class.java).setup()
        for(type in RadarType.entries){
            var saved:String?=null
            BtRobotRulesDialog.show(ctl.get(),"0",initialType=type){key,_->saved=key}
            val dialog=ShadowDialog.getLatestDialog() as AlertDialog
            ScanConditions.forRadar(type).forEach{rule->assertNotNull(view(dialog.window!!.decorView,"required-${type.name}-${rule.id}"))}
            core.getValue(type).forEach{id->
                val option=view(dialog.window!!.decorView,"required-${type.name}-$id") as BtRuleChoice
                option.performClick();assertEquals(BtRuleChoice.Choice.REQUIRED,option.choice)
            }
            view(dialog.window!!.decorView,"save-required-rules").performClick()
            assertEquals(core.getValue(type),BtRobotSpace.rules(saved!!)[type])
        }
        ctl.pause().stop().destroy()
    }
}
