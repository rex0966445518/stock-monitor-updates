package com.rex.twboardingscanner.backtest

import androidx.work.OutOfQuotaPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutionException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class RobotWorkPolicyTest {
    @Test fun modernBackgroundNeverPromotesAndOnlyUserStartsAreExpedited() {
        assertTrue(RobotWorkPolicy.promoteForeground(30))
        listOf(31,34,35,36).forEach { sdk->
            assertFalse(RobotWorkPolicy.promoteForeground(sdk))
            assertTrue(RobotWorkPolicy.promoteForeground(sdk,true))
            assertTrue(RobotWorkPolicy.expedited(sdk,true,0))
            assertFalse(RobotWorkPolicy.expedited(sdk,false,0))
            assertFalse(RobotWorkPolicy.expedited(sdk,true,10))
        }
        assertFalse(RobotWorkPolicy.expedited(30,true,0))
        val start=BtRobotWorker.request("session","token",userStarted=true)
        assertTrue(start.workSpec.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST,start.workSpec.outOfQuotaPolicy)
        assertEquals("session",start.workSpec.input.getString("session"))
        assertTrue(start.workSpec.input.getBoolean("userStarted",false))
        assertFalse(BtRobotWorker.request("session","token").workSpec.expedited)
        assertFalse(BtRobotWorker.request("session","token",10,true).workSpec.expedited)
    }
    @Test @Config(sdk=[30]) fun legacyRequestKeepsOrdinaryWork() {
        assertFalse(BtRobotWorker.request("session","token",userStarted=true).workSpec.expedited)
    }
    @Test fun wrappedPlatformRejectionIsRecognizedWithoutSwallowingOtherErrors() {
        assertTrue(RobotWorkPolicy.foregroundDenied(ExecutionException(IllegalStateException("startForegroundService() not allowed due to mAllowStartForeground"))))
        assertFalse(RobotWorkPolicy.foregroundDenied(java.io.IOException("network unavailable")))
        assertFalse(RobotWorkPolicy.foregroundDenied(IllegalArgumentException("invalid settings")))
        assertFalse(RobotWorkPolicy.expired(1000,1000+RobotWorkPolicy.SLICE_MS-1))
        assertTrue(RobotWorkPolicy.expired(1000,1000+RobotWorkPolicy.SLICE_MS))
    }
}
