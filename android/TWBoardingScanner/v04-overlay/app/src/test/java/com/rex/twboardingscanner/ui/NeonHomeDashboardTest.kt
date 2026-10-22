package com.rex.twboardingscanner.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.rex.twboardingscanner.domain.RadarType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
class NeonHomeDashboardTest {
    private fun all(v:View):List<View>{
        val children=if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))} else emptyList()
        return listOf(v)+children
    }
    @Test fun referenceHomeHasSevenWorkingConditionEntriesAndActionRouting() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        var update=0
        var scan=0
        val clicked=mutableListOf<RadarType>()
        val dash=NeonHomeDashboard(
            activity=activity,
            openScanner={scan++},
            openBacktest={},
            openRobot={},
            openUpdate={update++},
            openCondition={clicked.add(it)},
            openDiagnostics={},
            openPermissions={},
            openCsv={},
            openChangelog={}
        )
        val views=all(dash.view)
        for(type in RadarType.entries){
            val tile=views.firstOrNull{it.contentDescription?.toString()==type.label+"，開啟條件編輯"}
            assertNotNull("Homepage must show an editable "+type.label,tile)
            assertTrue(tile!!.performClick())
        }
        assertEquals(RadarType.entries,clicked)
        assertTrue(views.first{it.contentDescription?.toString()=="一鍵更新，取得最新版本"}.performClick())
        assertEquals(1,update)
        assertTrue(views.first{it.contentDescription?.toString()=="主掃描，找出潛力標的"}.performClick())
        assertEquals(1,scan)
    }

    @Test fun realCountsReplaceBlankStateWithoutHardcodedScreenshotFigures() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val dash=NeonHomeDashboard(activity,{},{},{},{},{},{},{},{},{})
        fun values()=all(dash.view).filterIsInstance<TextView>().map{it.text.toString()}
        dash.update(NeonHomeDashboard.Stats(null,null,null,null,null,
            "系統待命中","尚未開始掃描",false))
        assertFalse(values().contains("152"))
        assertFalse(values().contains("24"))
        assertTrue(values().count{it=="—"}>=4)
        dash.update(NeonHomeDashboard.Stats(152,24,5,6,1_800_000_000_000L,
            "掃描完成","已完成真實掃描",false))
        for(v in listOf("152","24","5","6"))assertTrue("Missing "+v,values().contains(v))
        assertTrue(values().any{it.contains("掃描完成")})
    }
}
