package com.rex.twboardingscanner.ui

import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import com.rex.twboardingscanner.backtest.*
import com.rex.twboardingscanner.domain.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
class RobotOptionMenusTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun clean(){
        runCatching{androidx.work.WorkManager.initialize(app,androidx.work.Configuration.Builder().build())}
        app.getSharedPreferences("backtest_robot",0).edit().clear().commit()
        app.getSharedPreferences("robot-params-draft",0).edit().clear().commit()
    }
    private fun find(v:View,tag:String):View? {
        if(v.tag==tag)return v
        if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),tag)?.let{return it}
        return null
    }
    private fun settings()=BtRobotSpace.settings().copy(marketGuardVersion=MarketCrashGuard.VERSION)
    private fun awaitBatch(store:BtRobotStore,old:String) {
        val end=System.nanoTime()+5_000_000_000L
        while(store.active()==old&&System.nanoTime()<end){Thread.sleep(10);Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()}
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNotEquals("Latest save message: ${org.robolectric.shadows.ShadowToast.getTextOfLatestToast()}",old,store.active())
    }
    @Test fun disabledRadarConstraintsDoNotRejectActiveRadarTrials(){
        val type=RadarType.D_MAIN_FORCE
        val on=BtRobotSpace.key(RadarType.entries.associateWith{if(it==type)setOf(ScanConditions.forRadar(type).first().id)else emptySet()})
        val enabled=setOf(RadarType.A_EARLY_BREAKOUT)
        assertTrue(BtRobotSpace.includes("0",on,"0",enabled))
        assertFalse(BtRobotSpace.includes(on,on,"0",enabled))
        assertFalse(BtRobotSpace.includes("0",on,"0",RadarType.entries.toSet()))
    }
    @Test fun everyRadarOpensItsOwnRuleMenuEvenInGridMode(){
        val store=BtRobotStore(app);store.create(settings(),sweep=BtRobotSweep(10,12,3,4))
        val ctl=Robolectric.buildActivity(BtRobotActivity::class.java).setup();val a=ctl.get()
        assertNull(find(a.window.decorView,"robot-required"))
        val options=find(a.window.decorView,"robot-condition-settings")!!
        assertNotNull(find(options,"robot-sectors"))
        for(type in RadarType.entries){
            find(options,"robot-rules-${type.name}")!!.performClick()
            val dialog=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            val id=ScanConditions.forRadar(type).first().id
            val row=find(dialog.window!!.decorView,"required-${type.name}-$id") as BtRuleChoice
            assertEquals(BtRuleChoice.Choice.FREE,row.choice)
            row.performClick();assertEquals(BtRuleChoice.Choice.REQUIRED,row.choice)
            row.performClick();assertEquals(BtRuleChoice.Choice.EXCLUDED,row.choice)
            dialog.dismiss()
        }
        ctl.pause().stop().destroy()
    }
    @Test fun savingRequiredAndExcludedLeavesGridResultsAndCreatesConditionBatch(){
        val store=BtRobotStore(app);val old=store.create(settings(),sweep=BtRobotSweep(10,12,3,4))
        val ctl=Robolectric.buildActivity(BtRobotActivity::class.java).setup()
        val type=RadarType.A_EARLY_BREAKOUT
        val ids=ScanConditions.forRadar(type).take(2).map{it.id}
        val on=BtRobotSpace.key(RadarType.entries.associateWith{if(it==type)setOf(ids[0])else emptySet()})
        val off=BtRobotSpace.key(RadarType.entries.associateWith{if(it==type)setOf(ids[1])else emptySet()})
        ctl.get().applyRequired(on,off);awaitBatch(store,old)
        val next=store.session()!!
        assertTrue(next.isNull("sweep"));assertEquals(on,next.getString("required"));assertEquals(off,next.getString("forbidden"))
        assertNotNull(BtRobotSweep.read(store.session(old)!!.optJSONObject("sweep")))
        ctl.pause().stop().destroy()
    }
    @Test fun industryMenuSavesOnlyKeptSectorsAndPreservesOldSnapshot(){
        val store=BtRobotStore(app);val old=store.create(settings(),sweep=BtRobotSweep(10,12,3,4))
        val ctl=Robolectric.buildActivity(BtRobotActivity::class.java).setup();val a=ctl.get()
        find(a.window.decorView,"robot-sectors")!!.performClick()
        val dialog=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val root=dialog.window!!.decorView
        StockSector.entries.forEach{sector->(find(root,"robot-sector-${sector.name}") as CheckBox).isChecked=sector==StockSector.SEMICONDUCTOR}
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();awaitBatch(store,old)
        assertEquals(setOf(StockSector.SEMICONDUCTOR),BtSettingsCodec.decode(store.session()!!.getJSONObject("settings")).sectors)
        assertEquals(StockSector.entries.toSet(),BtSettingsCodec.decode(store.session(old)!!.getJSONObject("settings")).sectors)
        assertNotNull(BtRobotSweep.read(store.session()!!.optJSONObject("sweep")))
        ctl.pause().stop().destroy()
    }
}
