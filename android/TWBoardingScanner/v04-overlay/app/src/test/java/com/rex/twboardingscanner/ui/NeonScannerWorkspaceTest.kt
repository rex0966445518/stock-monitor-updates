package com.rex.twboardingscanner.ui

import android.Manifest
import android.view.View
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.rex.twboardingscanner.R
import com.rex.twboardingscanner.data.BackgroundScanState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Protect the production scanner while upgrading the interior design. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
class NeonScannerWorkspaceTest {
    @Test fun scannerTilesUsePersistedCountsAndKeepOriginalOperations() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get()
        MainActivity::class.java.getDeclaredMethod("openScannerView")
            .apply{isAccessible=true}.invoke(activity)

        val start=activity.findViewById<MaterialButton>(R.id.startScanButton)
        assertEquals("開始掃描",start.text.toString())
        assertTrue(start.hasOnClickListeners())
        assertTrue(activity.findViewById<View>(R.id.settingsButton).hasOnClickListeners())
        assertEquals("—",activity.findViewById<TextView>(R.id.scanScannedMetric).text.toString())
        assertEquals("—",activity.findViewById<TextView>(R.id.scanMatchedMetric).text.toString())

        val id=BackgroundScanState.begin(activity,JSONObject("{}"))
        try {
            BackgroundScanState.report(activity,id,"已處理 42 / 90 檔",
                done=42,total=90,analyzed=40,failed=2,pending=5)
            MainActivity::class.java.getDeclaredMethod("syncBackgroundScan")
                .apply{isAccessible=true}.invoke(activity)
            assertEquals("42",activity.findViewById<TextView>(R.id.scanScannedMetric).text.toString())
            assertEquals("0",activity.findViewById<TextView>(R.id.scanMatchedMetric).text.toString())
            assertEquals("5",activity.findViewById<TextView>(R.id.scanPendingMetric).text.toString())
            assertEquals("2",activity.findViewById<TextView>(R.id.scanFailedMetric).text.toString())
            assertTrue(activity.findViewById<View>(R.id.navBacktest).performClick())
            assertEquals(BacktestActivity::class.java.name,
                Shadows.shadowOf(activity).nextStartedActivity.component?.className)
        } finally {
            BackgroundScanState.cancel(activity,id)
            controller.pause().stop().destroy()
        }
    }

    @Test fun bottomNavigationReturnsToHomeWithoutStartingScan() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get()
        MainActivity::class.java.getDeclaredMethod("openScannerView")
            .apply{isAccessible=true}.invoke(activity)
        val nav=activity.findViewById<View>(R.id.navHome)
        assertTrue(nav.hasOnClickListeners())
        assertTrue(nav.performClick())
        assertFalse(activity.findViewById<View>(R.id.startScanButton).isShown)
        controller.pause().stop().destroy()
    }
}
