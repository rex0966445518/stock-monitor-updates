package com.rex.twboardingscanner.backtest

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.ui.BtRobotActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.math.BigInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class RobotTrialCountTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val e=RadarType.E_DIF_TOUCH
    private fun find(v:View,tag:String):View? {
        if(v.tag==tag)return v
        if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),tag)?.let{return it}
        return null
    }
    private fun required():String {
        val options=BtRobotSpace.allSlots.filter{it.first==e}
        assertTrue(options.size>=26)
        val fixed=options.dropLast(26).map{it.second}.toSet()
        return BtRobotSpace.key(RadarType.entries.associateWith{if(it==e)fixed else emptySet()})
    }
    private fun initWork(){runCatching{androidx.work.WorkManager.initialize(app,androidx.work.Configuration.Builder().build())}}
    @Test fun oldFinishedEOnlyPowerSetBatchPreviews26AndCanStartSingleMode() {
        initWork()
        val store=BtRobotStore(app);val must=required()
        val settings=BtRobotSpace.settings().copy(enabledRadars=setOf(e),stockScope=StockScope.ROBOT,marketGuardVersion=MarketCrashGuard.VERSION)
        val old=store.create(settings,must)
        assertEquals(BigInteger.ONE.shiftLeft(26),store.remaining(old))
        val token=store.resume(old);store.status(old,token,"舊組合完成","DONE")
        val ctl=Robolectric.buildActivity(BtRobotActivity::class.java).setup();val a=ctl.get();val root=a.window.decorView
        val total=find(root,"robot-trial-total") as TextView
        assertTrue(total.text.toString().contains("共 26 輪"))
        assertFalse(total.text.toString().contains("67,108,864"))
        assertTrue((find(root,"robot-trial-counts") as TextView).text.toString().contains("已完成 0 輪"))
        find(root,"robot-untested")!!.performClick()
        val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertTrue((find(dialog.window!!.decorView,"robot-untested-count") as TextView).text.toString().contains("未測試 26 輪"))
        dialog.dismiss()
        val id=a.policySession(RobotTradingParams.of(settings),null,RobotBacktestDates.of(settings))
        assertNotEquals(old,id);assertTrue(BtRobotSingleOption.active(store.session(id)))
        assertEquals(BigInteger.valueOf(26),store.remaining(id))
        assertEquals(setOf(e),BtSettingsCodec.decode(store.session(id)!!.getJSONObject("settings")).enabledRadars)
        assertEquals("DONE",store.session(old)!!.getString("state"))
        ctl.pause().stop().destroy()
    }
    @Test fun disablingOtherRadarsImmediatelyRecountsAndSavingUnchangedChecksCreatesEOnlyPlan() {
        initWork()
        val store=BtRobotStore(app);val must=required()
        val settings=BtRobotSpace.settings().copy(enabledRadars=RadarType.entries.toSet(),stockScope=StockScope.ROBOT,marketGuardVersion=MarketCrashGuard.VERSION)
        val old=store.create(settings,must)
        val ctl=Robolectric.buildActivity(BtRobotActivity::class.java).setup();val a=ctl.get();val root=a.window.decorView
        RadarType.entries.filter{it!=e}.forEach{find(root,"robot-enabled-${it.name}")!!.performClick()}
        assertTrue((find(root,"robot-trial-total") as TextView).text.toString().contains("共 26 輪"))
        a.applyRequired(must,"0")
        val deadline=System.nanoTime()+5_000_000_000L
        while(store.active()==old&&System.nanoTime()<deadline){Thread.sleep(10);Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()}
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNotEquals(old,store.active());assertTrue(BtRobotSingleOption.active(store.session()))
        assertEquals(BigInteger.valueOf(26),store.remaining(store.active()))
        val enabled=BtSettingsCodec.decode(store.session()!!.getJSONObject("settings")).enabledRadars
        assertEquals(setOf(e),enabled)
        var cursor=BigInteger.ZERO;val seen=mutableSetOf<String>()
        repeat(26){val next=BtRobotSingleOption.next(must,"0",enabled,cursor){it in seen}!!
            assertEquals(1,BigInteger(next.key,16).andNot(BigInteger(must,16)).bitCount())
            seen+=next.key;cursor=next.cursor}
        assertNull(BtRobotSingleOption.next(must,"0",enabled,cursor){it in seen})
        ctl.pause().stop().destroy()
    }
}
