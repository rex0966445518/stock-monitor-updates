package com.rex.twboardingscanner.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.rex.twboardingscanner.backtest.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RobotScanAnimationTest {
    @Test fun progressUsesActualStageCountsAndNeverInventsUnknownPercentOrEta(){
        val s="本次執行：A 準備起漲\n依本次條件掃描 851 / 1350 檔\n條件掃描：預估剩餘約 12秒（本階段）"
        val frame=RobotScanFrame.read(s)
        assertEquals(63,frame.percent);assertEquals(851L,frame.done);assertEquals(1350L,frame.total)
        assertTrue(frame.eta.contains("12秒"))
        for(status in listOf("等待網路…","歷史日線 0 / 0","掃描 5 / 3","掃描 1 / 9999999999999999999999"))assertNull(RobotScanFrame.read(status).percent)
        assertEquals(100,RobotScanFrame.read("掃描 100／100").percent)
    }
    @Test fun motionStopsOnHideBackgroundAndDetachAndProducesPreview(){
        val controller=Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(com.google.android.material.R.style.Theme_MaterialComponents_DayNight_NoActionBar)
        controller.setup();val activity=controller.get()
        val panel=RobotScanPanel(activity){}
        activity.setContentView(panel)
        panel.update(true,"條件掃描 68 / 100 檔\n預估剩餘約 12秒")
        val w=720;panel.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1200,View.MeasureSpec.AT_MOST));panel.layout(0,0,w,panel.measuredHeight)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertTrue(panel.illustration.active);assertTrue(panel.illustration.animationRunning)
        assertEquals(68,panel.illustration.percentage)
        val bitmap=Bitmap.createBitmap(w,panel.height,Bitmap.Config.ARGB_8888);panel.draw(Canvas(bitmap))
        val output=File("build/ui-previews/robot-scan-animation.png");output.parentFile.mkdirs()
        output.outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
        assertNotEquals(bitmap.getPixel(360,50),bitmap.getPixel(360,360))
        panel.foreground(false);assertFalse(panel.illustration.animationRunning)
        panel.foreground(true);assertTrue(panel.illustration.animationRunning)
        panel.update(false,"完成");assertEquals(View.GONE,panel.visibility);assertFalse(panel.illustration.animationRunning)
        panel.update(true,"等待網路");assertNull(panel.illustration.percentage)
        activity.setContentView(View(activity));assertFalse(panel.illustration.animationRunning)
        controller.pause().stop().destroy()
    }
    @Test fun historicalScreenTracksWorkerStateAndKeepsStopControlAvailable(){
        val app=RuntimeEnvironment.getApplication();val store=BacktestStore(app)
        store.resetCurrent()
        val day=LocalDate.of(2026,8,1)
        store.begin("animation-fixture",BtSettings(day,day.plusDays(7)))
        store.update("animation-fixture","歷史日線 7 / 10 檔\n預估剩餘約 3秒")
        val controller=Robolectric.buildActivity(BacktestActivity::class.java).setup();val activity=controller.get()
        shadowOf(Looper.getMainLooper()).idle()
        val panel=activity.window.decorView.findViewWithTag<RobotScanPanel>("robot-scan-panel")
        assertNotNull(panel);assertEquals(View.VISIBLE,panel.visibility);assertEquals(70,panel.illustration.percentage)
        assertNotNull(panel.findViewWithTag<View>("robot-scan-stop"))
        controller.pause();assertFalse(panel.illustration.animationRunning)
        store.update("animation-fixture","回測失敗","ERROR")
        controller.resume();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE,panel.visibility);assertFalse(panel.illustration.animationRunning)
        controller.pause().stop().destroy();store.resetCurrent()
    }
}
