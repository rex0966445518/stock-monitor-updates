package com.rex.twboardingscanner.backtest

/** Android 12+ background continuations must not unconditionally start an FGS. */
object RobotWorkPolicy {
    const val SLICE_MS=7*60_000L
    fun promoteForeground(sdk:Int)=sdk<31
    fun expedited(sdk:Int,userStarted:Boolean,delaySeconds:Long)=sdk>=31&&userStarted&&delaySeconds==0L
    fun expired(start:Long,now:Long)=now-start>=SLICE_MS
    fun foregroundDenied(error:Throwable):Boolean {
        var current:Throwable?=error
        repeat(12){
            val e=current?:return false
            if(e.javaClass.simpleName=="ForegroundServiceStartNotAllowedException"||
                (e.message?.contains("startForegroundService() not allowed")==true)||
                (e.message?.contains("mAllowStartForeground")==true))return true
            if(e.cause===e)return false
            current=e.cause
        }
        return false
    }
}
