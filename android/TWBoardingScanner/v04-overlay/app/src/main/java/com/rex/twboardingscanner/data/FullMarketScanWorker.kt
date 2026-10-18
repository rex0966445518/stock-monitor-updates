package com.rex.twboardingscanner.data

import android.content.Context
import android.os.Build
import android.content.pm.ServiceInfo
import androidx.work.*
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Recovery fallback only. A user-initiated foreground Service handles the
 * actual scan. Android 16 applies JobScheduler quotas even to foreground
 * WorkManager tasks, so do not make WorkManager the primary long scan runner.
 */
class FullMarketScanWorker(c:Context,p:WorkerParameters):Worker(c,p){
    companion object {
        private const val DEFAULT_RECOVERY_SECONDS=90L

        fun start(c:Context,config:org.json.JSONObject):String {
            val app=c.applicationContext
            val id=BackgroundScanState.begin(app,config)
            try {
                // Called synchronously from the Start button while an Activity
                // is visible; Android 12+ cannot freely start FGS in background.
                FullMarketScanService.start(app,id)
                scheduleRecovery(app,id,DEFAULT_RECOVERY_SECONDS)
            }catch(e:Exception){
                BackgroundScanState.report(app,id,
                    "持續背景服務啟動失敗，將改用系統排程："+(e.message?:"").take(130),
                    state="RETRY")
                ScanDiagnostics.failure(app,e)
                try {scheduleRecovery(app,id,0)} catch(error:Exception) {
                    BackgroundScanState.failure(app,id,"背景服務與備援排程都無法啟動："+(error.message?:""))
                    throw error
                }
            }
            return id
        }

        fun scheduleRecovery(c:Context,id:String,delaySeconds:Long=DEFAULT_RECOVERY_SECONDS){
            val req=OneTimeWorkRequestBuilder<FullMarketScanWorker>()
                .setInputData(workDataOf("run" to id))
                .setInitialDelay(delaySeconds,TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR,30,TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(c).enqueueUniqueWork(
                BackgroundScanState.WORK,ExistingWorkPolicy.REPLACE,req)
        }

        fun stop(c:Context){
            val app=c.applicationContext
            val id=BackgroundScanState.read(app).id
            BackgroundScanState.cancel(app,id)
            BackgroundScanLive.clear(id)
            app.stopService(android.content.Intent(app,FullMarketScanService::class.java))
            WorkManager.getInstance(app).cancelUniqueWork(BackgroundScanState.WORK)
        }

        /** Called only from the foreground UI when Android killed the service. */
        fun resume(c:Context):Boolean {
            val state=BackgroundScanState.read(c)
            if(!state.active)return false
            if(BackgroundScanState.freshService(c,state.id))return true
            FullMarketScanService.start(c,state.id)
            scheduleRecovery(c,state.id)
            return true
        }
    }

    override fun doWork():Result{
        val c=applicationContext
        val id=inputData.getString("run")?:return Result.failure()
        if(!BackgroundScanState.current(c,id))return Result.success()
        // Do not duplicate the scan while the direct foreground service is alive.
        if(BackgroundScanState.freshService(c,id))return Result.retry()
        val owner="worker:"+UUID.randomUUID()
        if(!BackgroundScanState.claim(c,id,owner))return Result.retry()
        return try{
            val note=FullMarketScanNotification.build(c,0,0,"系統正在接續背景掃描")
            val info=if(Build.VERSION.SDK_INT>=29) ForegroundInfo(
                FullMarketScanNotification.ID,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else ForegroundInfo(FullMarketScanNotification.ID,note)
            try{setForegroundAsync(info).get()}
            catch(e:Exception){
                // Never silently label a quota/restricted job as a live service.
                ScanDiagnostics.failure(c,e)
                BackgroundScanState.report(c,id,"Android 限制持續通知，背景排程重試中："+
                    (e.cause?.message?:e.message?:"").take(90),state="RETRY",owner=owner)
                return Result.retry()
            }
            FullMarketScanRunner(c,id,owner,
                canceled={isStopped},
                onNotification={done,total,message->
                    FullMarketScanNotification.manager(c).notify(
                        FullMarketScanNotification.ID,
                        FullMarketScanNotification.build(c,done,total,message))
                }).runScan()
            Result.success()
        }catch(e:Exception){
            if(!BackgroundScanState.owns(c,id,owner))return Result.success()
            ScanDiagnostics.failure(c,e)
            if(runAttemptCount>=4){
                BackgroundScanState.failure(c,id,"背景掃描重試多次仍失敗："+(e.message?:"").take(140),owner)
                Result.failure()
            }else{
                BackgroundScanState.report(c,id,
                    "系統備援掃描中斷，將重試："+(e.message?:"").take(90),
                    state="RETRY",owner=owner)
                Result.retry()
            }
        }catch(e:OutOfMemoryError){
            BackgroundScanState.failure(c,id,"背景掃描記憶體不足，請降低同時處理數量",owner)
            Result.failure()
        }
    }

    override fun onStopped(){
        super.onStopped()
        val id=inputData.getString("run")?:return
        val owner=BackgroundScanState.read(applicationContext).owner
        if(owner.startsWith("worker:")&&BackgroundScanState.owns(applicationContext,id,owner)){
            BackgroundScanState.report(applicationContext,id,
                "Android 已暫停背景排程，等待系統再次執行",
                state="RETRY",owner=owner)
        }
    }
}
