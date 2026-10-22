package com.rex.twboardingscanner.ui

import android.Manifest
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.rex.twboardingscanner.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
class CompactScannerLayoutTest {
    private fun dp(n:Int)=NeonUi.dp(RuntimeEnvironment.getApplication(),n)
    private fun checkVisibleText(v:View){
        if(v.visibility!=View.VISIBLE)return
        if(v is TextView&&v.text.isNotBlank()&&v.layout!=null){
            // For compact navigation, the text must not disappear inside a tiny button.
            assertTrue("Text clipped in ${v.id}: ${v.text}",
                v.layout.height<=v.height-v.compoundPaddingTop-v.compoundPaddingBottom+3)
        }
        if(v is ViewGroup)repeat(v.childCount){checkVisibleText(v.getChildAt(it))}
    }
    @Test fun mainScreenMakesRoomForCardsWithoutHidingControls(){
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val activity=Robolectric.buildActivity(MainActivity::class.java).setup()
        // The screenshot-inspired homepage is now the default. Exercise the legacy scanner
        // viewport after selecting 主掃描 so its original controls remain regression tested.
        MainActivity::class.java.getDeclaredMethod("openScannerView").apply{isAccessible=true}.invoke(activity.get())
        val body=activity.get().findViewById<View>(android.R.id.content)
        for(scale in listOf(1f,1.3f)){
            RuntimeEnvironment.setFontScale(scale)
            body.measure(View.MeasureSpec.makeMeasureSpec(dp(360),View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(800),View.MeasureSpec.EXACTLY))
            body.layout(0,0,dp(360),dp(800))
            val results=activity.get().findViewById<View>(R.id.recycler)
            assertTrue("Results should retain substantial space, top=${results.top}",results.top<dp(525))
            assertTrue("Results viewport too short: ${results.height}",results.height>dp(215))
            val buttons=listOf(R.id.startScanButton,R.id.settingsButton,R.id.paperButton,
                R.id.backtestButton,R.id.robotButton,R.id.marketGuardButton)
            buttons.forEach{id->
                val button=activity.get().findViewById<View>(id)
                assertTrue("Button must remain usable: $id",button.height>=dp(36))
                assertTrue("Button should be compact: $id",button.height<=dp(44))
                assertTrue(button.isShown)
            }
            checkVisibleText(body)
        }
        activity.pause().stop().destroy()
    }
}
