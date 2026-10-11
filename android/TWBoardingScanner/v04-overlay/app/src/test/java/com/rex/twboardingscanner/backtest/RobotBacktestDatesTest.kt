package com.rex.twboardingscanner.backtest

import android.app.DatePickerDialog
import android.content.DialogInterface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.rex.twboardingscanner.domain.MarketCrashGuard
import com.rex.twboardingscanner.ui.BtRobotActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
class RobotBacktestDatesTest{
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun clean(){
        app.getSharedPreferences("backtest_robot",0).edit().clear().commit()
        app.getSharedPreferences("robot-params-draft",0).edit().clear().commit()
        app.getSharedPreferences("stock-entry-policy",0).edit().clear().commit()
    }
    private fun getByTag(view:View,tag:String):View?{
        if(view.tag==tag)return view
        if(view is ViewGroup)for(i in 0 until view.childCount)getByTag(view.getChildAt(i),tag)?.let{return it}
        return null
    }
    private fun initial()=BtRobotSpace.settings().copy(
        start=LocalDate.of(2026,8,1),end=LocalDate.of(2026,10,8),
        marketGuardVersion=MarketCrashGuard.VERSION
    )
    @Test fun selectedHistoryDatesValidateOrderBoundsAndMaximumLength(){
        val today=LocalDate.of(2026,10,11)
        val s=initial()
        val d=RobotBacktestDates.of(s)
        assertEquals(d,RobotBacktestDates.parse("2026-08-01","2026-10-08"))
        assertEquals(s,d.validated(today).applyTo(s))
        assertEquals(LocalDate.of(2016,1,1),RobotBacktestDates.parse("2016-01-01","2016-01-01").validated(today).start)
        assertThrows(Exception::class.java){RobotBacktestDates.parse("2026-10-08","2026-08-01").validated(today)}
        assertThrows(Exception::class.java){RobotBacktestDates.parse("2025-01-01","2026-12-01").validated(today)}
        assertThrows(Exception::class.java){RobotBacktestDates.parse("2015-12-31","2016-01-02").validated(today)}
        assertThrows(Exception::class.java){RobotBacktestDates.parse("2026-10-11","2026-10-11").validated(today)}
        assertThrows(Exception::class.java){RobotBacktestDates.parse("2023-01-01","2026-01-02").validated(today)}
        assertEquals(730,
            java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(2024,1,1),
                RobotBacktestDates.parse("2024-01-01","2025-12-31").validated(today).end)
                .toInt())
    }
    @Test fun robotDateButtonsPickBothEndpointsAndKeepExistingBatchUntouched(){
        val store=BtRobotStore(app)
        val id=store.create(initial(),sweep=BtRobotSweep(10,50,2,30))
        val before=store.session(id)!!.getJSONObject("settings").toString()
        val activity=Robolectric.buildActivity(BtRobotActivity::class.java).setup()
        val root=activity.get().window.decorView
        val start=getByTag(root,"robot-start-date") as TextView
        val end=getByTag(root,"robot-end-date") as TextView
        val label=getByTag(root,"robot-date-range") as TextView
        assertTrue(start.text.toString().contains("2026-08-01"))
        assertTrue(end.text.toString().contains("2026-10-08"))
        assertTrue(start.performClick())
        val first=ShadowDialog.getLatestDialog() as DatePickerDialog
        assertEquals(2026,first.datePicker.year)
        first.datePicker.updateDate(2026,6,1)
        // Robolectric does not always forward synthetic AlertDialog button clicks to
        // DatePickerDialog's date-set listener. Call the same platform positive handler.
        first.onClick(first,DialogInterface.BUTTON_POSITIVE)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("Expected July 1, actual: ${start.text}",start.text.toString().contains("2026-07-01"))
        assertTrue(label.text.toString().contains("2026-07-01 → 2026-10-08"))
        assertTrue(end.performClick())
        val last=ShadowDialog.getLatestDialog() as DatePickerDialog
        last.datePicker.updateDate(2026,8,30)
        last.onClick(last,DialogInterface.BUTTON_POSITIVE)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(label.text.toString().contains("2026-07-01 → 2026-09-30"))
        assertEquals(id,store.active())
        assertEquals(before,store.session(id)!!.getJSONObject("settings").toString())
        // The user can edit an invalid intermediate interval, but cannot start a run with it.
        start.performClick()
        val invalid=ShadowDialog.getLatestDialog() as DatePickerDialog
        invalid.datePicker.updateDate(2026,9,5)
        invalid.onClick(invalid,DialogInterface.BUTTON_POSITIVE)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue((getByTag(root,"robot-parameter-notice") as TextView).text.toString().contains("結束日期不可早於起始日期"))
        val starts=(getByTag(root,"robot-date-range") as TextView).text.toString()
        assertTrue(starts.contains("2026-10-05 → 2026-09-30"))
        assertEquals(before,store.session(id)!!.getJSONObject("settings").toString())
        activity.pause().stop().destroy()
        // Unsubmitted date edits remain a draft when the user leaves the page.
        val reopened=Robolectric.buildActivity(BtRobotActivity::class.java).setup()
        val refreshed=reopened.get().window.decorView
        assertTrue((getByTag(refreshed,"robot-start-date") as TextView).text.toString().contains("2026-10-05"))
        assertTrue((getByTag(refreshed,"robot-end-date") as TextView).text.toString().contains("2026-09-30"))
        assertEquals(before,store.session(id)!!.getJSONObject("settings").toString())
        reopened.pause().stop().destroy()
    }
    @Test fun changingDateNeverClonesOtherPeriodDataButUnchangedPeriodCanReuseCache(){
        val store=BtRobotStore(app)
        val s=initial()
        val first=store.create(s)
        val token=store.resume(first)
        // Dummy bytes are sufficient for testing cache isolation; no market prices are fabricated.
        val old=store.dataset(first)
        old.parentFile?.mkdirs()
        old.writeBytes(byteArrayOf(7,3,5,11))
        store.coverage(first,token,1,1,BtRobotCheckpoint.digest(old))
        store.pause(first)
        val changed=store.create(s.copy(start=s.start.minusDays(4),end=s.end.minusDays(4)))
        BtRobotCheckpoint.cloneData(store,first,changed)
        assertFalse(store.dataset(changed).exists())
        assertFalse(store.session(changed)!!.has("datasetSha256"))
        val same=store.create(s.copy(capital=7000000.0,maxHoldingStocks=21,targetNetPct=2.0))
        BtRobotCheckpoint.cloneData(store,first,same)
        assertArrayEquals(old.readBytes(),store.dataset(same).readBytes())
        assertEquals(store.session(first)!!.getString("datasetSha256"),store.session(same)!!.getString("datasetSha256"))
        old.delete();store.dataset(same).delete()
    }
}
