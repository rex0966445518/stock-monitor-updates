package com.rex.twboardingscanner.backtest

/** Cheap stop checks on every candle, database/settings validation at most four times a second. */
internal class RobotCancellation(private val stopped:()->Boolean,private val invalid:()->Boolean,
    private val clock:()->Long={android.os.SystemClock.elapsedRealtime()}) {
    private var checkedAt:Long?=null
    private var invalidated=false
    @Synchronized fun check(force:Boolean=false):Boolean {
        if(stopped()||invalidated)return true
        val now=clock()
        if(force||checkedAt==null||now-checkedAt!!>=250){
            invalidated=invalid();checkedAt=now
        }
        return invalidated
    }
}
