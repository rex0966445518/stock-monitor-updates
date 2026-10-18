package com.rex.twboardingscanner.data

import android.app.Notification
import android.content.ComponentName
import android.content.pm.ServiceInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FullMarketScanServiceTest {
    private val app get()=RuntimeEnvironment.getApplication()

    @Test fun directServiceIsRegisteredAsDataSyncAndSurvivesTaskRemoval(){
        val service=app.packageManager.getServiceInfo(
            ComponentName(app,FullMarketScanService::class.java),0)
        assertEquals(0,service.flags and ServiceInfo.FLAG_STOP_WITH_TASK)
        assertNotEquals(0,service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        assertFalse(service.exported)
    }

    @Test fun foregroundNotificationShowsRealProgressAndStopAction(){
        val note=FullMarketScanNotification.build(app,225,1000,"正在讀取台股")
        assertEquals(225,note.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(1000,note.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertFalse(note.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertTrue(note.flags and Notification.FLAG_ONGOING_EVENT!=0)
        assertTrue(note.actions.isNotEmpty())
    }
}
