package com.rex.twboardingscanner.ui

import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ScanRenderQueueTest {
    @Test fun thousandsOfUpdatesProduceOneLatestFrameAndCompletionCancelsPartialFrames(){
        val queue=ScanRenderQueue(Handler(Looper.getMainLooper()))
        val shown=mutableListOf<Int>()
        repeat(2000){i->queue.submit{shown+=i}}
        assertTrue(shown.isEmpty())
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        assertEquals(listOf(1999),shown)
        queue.submit{shown+=-1}
        queue.cancel()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(listOf(1999),shown)
        queue.submit{shown+=2000}
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        assertEquals(listOf(1999,2000),shown)
    }
}
