package com.rex.twboardingscanner.ui

import android.view.View
import android.view.ViewGroup
import com.rex.twboardingscanner.backtest.*
import com.rex.twboardingscanner.domain.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class RobotDStartTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun clean(){
        app.getSharedPreferences("backtest_robot",0).edit().clear().commit()
        app.getSharedPreferences("robot-params-draft",0).edit().clear().commit()
        runCatching{androidx.work.WorkManager.initialize(app,androidx.work.Configuration.Builder().build())}
    }
    private fun find(v:View,tag:String):View? {
        if(v.tag==tag)return v
        if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),tag)?.let{return it}
        return null
    }
    @Test fun oldEmptyDSeedCanStartWithOnlyDEnabledInGridMode(){
        val initial=BtRobotSpace.settings().copy(marketGuardVersion=MarketCrashGuard.VERSION,
            rules=defaultBtRules()+mapOf(RadarType.D_MAIN_FORCE to emptySet()))
        val store=BtRobotStore(app);val grid=BtRobotSweep(10,12,3,4)
        val old=store.create(initial,sweep=grid)
        val ctl=Robolectric.buildActivity(BtRobotActivity::class.java).setup();val a=ctl.get()
        RadarType.entries.take(4).forEach{type->find(a.window.decorView,"robot-enabled-${type.name}")!!.performClick()}
        val next=a.policySession(RobotTradingParams.of(initial),grid,RobotBacktestDates.of(initial))
        val actual=BtSettingsCodec.decode(store.session(next)!!.getJSONObject("settings"))
        assertEquals(setOf(RadarType.D_MAIN_FORCE),actual.enabledRadars)
        assertEquals(defaultBtRules().getValue(RadarType.D_MAIN_FORCE),actual.rules[RadarType.D_MAIN_FORCE])
        assertTrue(actual.rules.getValue(RadarType.D_MAIN_FORCE).isNotEmpty())
        assertEquals(initial.rules,BtSettingsCodec.decode(store.session(old)!!.getJSONObject("settings")).rules)
        assertEquals(grid,BtRobotSweep.read(store.session(next)!!.optJSONObject("sweep")))
        ctl.pause().stop().destroy()
    }
    @Test fun freeExplorationAllowsEmptySeedAndExistingDChoicesStayIntact(){
        val empty=RadarType.entries.associateWith{emptySet<String>()}
        val initial=BtRobotSpace.settings().copy(rules=empty,enabledRadars=setOf(RadarType.D_MAIN_FORCE))
        assertEquals(initial,RobotRuleSelection.prepare(initial,false))
        val custom=initial.copy(rules=empty+mapOf(RadarType.D_MAIN_FORCE to setOf(ScanConditions.forRadar(RadarType.D_MAIN_FORCE).first().id)))
        assertEquals(custom,RobotRuleSelection.prepare(custom,true))
        assertThrows(IllegalArgumentException::class.java){RobotRuleSelection.prepare(initial.copy(enabledRadars=emptySet()),false)}
    }
}
