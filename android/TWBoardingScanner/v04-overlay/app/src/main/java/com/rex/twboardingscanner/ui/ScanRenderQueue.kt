package com.rex.twboardingscanner.ui

import android.os.Handler

/** At most one pending frame; fast cache/enrichment producers cannot flood the UI queue. */
internal class ScanRenderQueue(private val handler:Handler,private val interval:Long=350L) {
    private var latest:(()->Unit)?=null
    private var scheduled=false
    private val frame=Runnable {
        val action=synchronized(this){scheduled=false;latest.also{latest=null}}
        action?.invoke()
    }
    @Synchronized fun submit(action:()->Unit){
        latest=action
        if(!scheduled){scheduled=true;handler.postDelayed(frame,interval)}
    }
    @Synchronized fun cancel(){handler.removeCallbacks(frame);latest=null;scheduled=false}
}
