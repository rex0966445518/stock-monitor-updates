package com.rex.twboardingscanner.ui

import android.view.View
import android.view.ViewGroup
import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.backtest.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
class FControlsTest {
    private fun find(v:View,tag:String):View? {
        if(v.tag==tag)return v
        if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),tag)?.let{return it}
        return null
    }
    private fun verify(root:View,prefix:String){
        val buttons=RadarType.entries.map{find(root,"$prefix-${it.name}")!!}
        buttons.forEachIndexed{i,b->
            val old=buttons.map{it.isSelected}
            assertTrue(b.performClick())
            assertNotEquals(old[i],b.isSelected)
            buttons.forEachIndexed{j,other->if(i!=j)assertEquals(old[j],other.isSelected)}
            assertTrue(b.performClick())
            assertEquals(old,buttons.map{it.isSelected})
        }
    }
    @Test fun scannerAndBacktestAndRobotExposeSixIndependentToggles(){
        val main=Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNotNull(find(main.get().window.decorView,"scanner-f-conditions"))
        verify(main.get().window.decorView,"scanner-enabled")
        main.pause().stop().destroy()
        val bt=Robolectric.buildActivity(BacktestActivity::class.java).setup()
        verify(bt.get().window.decorView,"backtest-enabled")
        bt.pause().stop().destroy()
        val robot=Robolectric.buildActivity(BtRobotActivity::class.java).setup()
        assertNotNull(find(robot.get().window.decorView,"robot-f-parameters"))
        assertNotNull(find(robot.get().window.decorView,"robot-rules-F_VOLUME_CONTRACTION"))
        verify(robot.get().window.decorView,"robot-enabled")
        robot.pause().stop().destroy()
    }
}
