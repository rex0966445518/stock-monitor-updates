package com.rex.twboardingscanner.data

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs long user-requested scans outside JobScheduler. The service is started
 * directly by a visible Activity, before navigating to another application.
 * The notification stays visible for the entire scan, including phone lock.
 *
 * This cannot override system force-stop or Android 15+ dataSync time limits.
 * A durable WorkManager recovery job is scheduled before leaving the Activity.
 */
class FullMarketScanService:Service() {
    companion object {
        const val ACTION_START="com.rex.twboardingscanner.SCAN_START"
        const val ACTION_STOP="com.rex.twboardingscanner.SCAN_STOP"
        private const val EXTRA_RUN="scanRunId"

        fun start(context:Context,id:String) {
            val app=context.applicationContext
            ContextCompat.startForegroundService(app,
                Intent(app,FullMarketScanService::class.java)
                    .setAction(ACTION_START).putExtra(EXTRA_RUN,id))
        }
    }

    private val executor=Executors.newSingleThreadExecutor()
    private val stopped=AtomicBoolean(false)
    private val handler=Handler(Looper.getMainLooper())
    @Volatile private var activeRun:String?=null
    @Volatile private var activeOwner:String?=null
    private val pulse=object:Runnable {
        override fun run(){
            val id=activeRun
            val owner=activeOwner
            if(stopped.get()||id==null||owner==null||
                !BackgroundScanState.owns(applicationContext,id,owner))return
            BackgroundScanState.pulse(applicationContext,id,owner)
            val s=BackgroundScanState.read(applicationContext)
            runCatching{
                FullMarketScanNotification.manager(applicationContext).notify(
                    FullMarketScanNotification.ID,
                    FullMarketScanNotification.build(applicationContext,s.done,s.total,s.message))
            }
            handler.postDelayed(this,5000L)
        }
    }

    override fun onBind(intent:Intent?):IBinder?=null

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==ACTION_STOP){
            FullMarketScanWorker.stop(applicationContext)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val id=intent?.getStringExtra(EXTRA_RUN)
        if(id.isNullOrBlank()||!BackgroundScanState.current(applicationContext,id)){
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if(activeRun==id && activeOwner!=null)return START_NOT_STICKY
        if(activeRun!=null){stopSelf(startId);return START_NOT_STICKY}
        try{
            // Must promote immediately while the user's Start button is visible.
            val state=BackgroundScanState.read(applicationContext)
            val notification=FullMarketScanNotification.build(this,state.done,state.total,
                "持續掃描服務啟動中")
            ServiceCompat.startForeground(this,FullMarketScanNotification.ID,notification,
                if(Build.VERSION.SDK_INT>=29)ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        }catch(e:Exception){
            ScanDiagnostics.failure(applicationContext,e)
            BackgroundScanState.report(applicationContext,id,
                "Android 拒絕啟用持續背景服務，系統將嘗試備援掃描："+(e.message?:"").take(80),
                state="RETRY")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val owner="service:"+UUID.randomUUID()
        if(!BackgroundScanState.claim(applicationContext,id,owner)){
            stopSelf(startId)
            return START_NOT_STICKY
        }
        stopped.set(false)
        activeRun=id
        activeOwner=owner
        BackgroundScanState.report(applicationContext,id,
            "已啟用持續背景服務 · 開始全市場掃描",state="RUNNING",owner=owner)
        handler.post(pulse)
        executor.execute {
            try{
                FullMarketScanRunner(applicationContext,id,owner,
                    canceled={stopped.get()||Thread.currentThread().isInterrupted},
                    onNotification={done,total,message->
                        if(BackgroundScanState.owns(applicationContext,id,owner)){
                            FullMarketScanNotification.manager(applicationContext).notify(
                                FullMarketScanNotification.ID,
                                FullMarketScanNotification.build(applicationContext,done,total,message))
                        }
                    }).runScan()
            }catch(e:Exception){
                if(BackgroundScanState.owns(applicationContext,id,owner)){
                    ScanDiagnostics.failure(applicationContext,e)
                    BackgroundScanState.report(applicationContext,id,
                        "持續背景服務中斷，系統將重試："+(e.message?:"").take(95),
                        state="RETRY",owner=owner)
                    runCatching{FullMarketScanWorker.scheduleRecovery(applicationContext,id,30)}
                }
            }catch(e:OutOfMemoryError){
                if(BackgroundScanState.owns(applicationContext,id,owner)){
                    BackgroundScanState.failure(applicationContext,id,
                        "持續掃描記憶體不足；請回 App 重新啟動",owner)
                }
            }finally{
                handler.removeCallbacks(pulse)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+ stops dataSync after an aggregate 6h in 24h. */
    override fun onTimeout(startId:Int,fgsType:Int){
        val id=activeRun;val owner=activeOwner
        stopped.set(true)
        if(id!=null&&owner!=null&&BackgroundScanState.owns(applicationContext,id,owner)){
            BackgroundScanState.report(applicationContext,id,
                "Android 持續背景服務時數已到上限，改由系統排程接續",
                state="RETRY",owner=owner)
            runCatching{FullMarketScanWorker.scheduleRecovery(applicationContext,id,20)}
        }
        stopSelf(startId)
    }

    override fun onDestroy(){
        stopped.set(true)
        handler.removeCallbacks(pulse)
        val id=activeRun;val owner=activeOwner
        if(id!=null&&owner!=null&&BackgroundScanState.owns(applicationContext,id,owner)){
            BackgroundScanState.report(applicationContext,id,
                "背景服務已停止，等待系統恢復掃描",state="RETRY",owner=owner)
            runCatching{FullMarketScanWorker.scheduleRecovery(applicationContext,id,30)}
        }
        executor.shutdownNow()
        super.onDestroy()
    }
}
