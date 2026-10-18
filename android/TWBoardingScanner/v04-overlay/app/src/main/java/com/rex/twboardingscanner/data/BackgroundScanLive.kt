package com.rex.twboardingscanner.data

import com.rex.twboardingscanner.domain.SignalResult

/**
 * Lightweight process-local preview for a foreground Activity observing a scan.
 * The worker remains the owner of computation and persistence. After process
 * death, WorkManager restarts the batch; completed data lives in ScanArchiveStore.
 */
object BackgroundScanLive {
    data class Preview(
        val id:String, val version:Long,
        val main:List<SignalResult>, val paper:List<SignalResult>
    )
    @Volatile private var latest:Preview?=null

    @Synchronized fun publish(id:String,main:List<SignalResult>,paper:List<SignalResult>) {
        val version=(latest?.takeIf{it.id==id}?.version?:0L)+1
        latest=Preview(id,version,main,paper)
    }

    fun read(id:String):Preview?=latest?.takeIf{it.id==id}

    @Synchronized fun clear(id:String){
        if(latest?.id==id)latest=null
    }
}