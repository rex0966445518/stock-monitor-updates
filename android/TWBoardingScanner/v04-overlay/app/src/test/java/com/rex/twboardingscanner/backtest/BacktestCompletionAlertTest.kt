package com.rex.twboardingscanner.backtest

import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class BacktestCompletionAlertTest {
    @Test fun normalAudibleModeSpeaksAndBothQuietModesVibrate(){
        assertFalse(BacktestCompletionAlert.useVibration(AudioManager.RINGER_MODE_NORMAL,5,false))
        assertTrue(BacktestCompletionAlert.useVibration(AudioManager.RINGER_MODE_SILENT,5,false))
        assertTrue(BacktestCompletionAlert.useVibration(AudioManager.RINGER_MODE_VIBRATE,5,false))
        assertTrue(BacktestCompletionAlert.useVibration(AudioManager.RINGER_MODE_NORMAL,0,false))
        assertTrue(BacktestCompletionAlert.useVibration(AudioManager.RINGER_MODE_NORMAL,5,true))
        val pattern=BacktestCompletionAlert.vibrationPattern()
        assertEquals(5,pattern.withIndex().count{it.index%2==1&&it.value>0})
    }
    @Test fun everyCompletedRunCanNotifyOnlyOnceAcrossReopens(){
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("backtest-completion-alerts",0).edit().clear().commit()
        assertTrue(BacktestCompletionAlert.claim(app,"run-a"))
        assertFalse(BacktestCompletionAlert.claim(app,"run-a"))
        assertTrue(BacktestCompletionAlert.claim(app,"run-b"))
        assertFalse(BacktestCompletionAlert.claim(app,"run-a"))
        assertEquals("歷史回測完成！",BacktestCompletionAlert.MESSAGE)
    }
}
