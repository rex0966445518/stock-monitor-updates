package com.rex.twboardingscanner.backtest

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-started, direct dataSync foreground service. It owns the heavy D/ABCDEF
 * robot backtest loop independently of Activity visibility and JobScheduler
 * quotas. WorkManager is only a recovery path when Android stops this service.
 *
 * Android force-stop or OEM policies cannot be bypassed; a job fallback may be
 * delayed and must not pretend to be continuously running.
 */
class BtRobotService:Service(){
    companion object {
        const val ACTION_START="com.rex.twboardingscanner.ROBOT_START"
        const val ACTION_STOP="com.rex.twboardingscanner.ROBOT_STOP"
        private const val EXTRA_SESSION="session"
        private const val EXTRA_TOKEN="token"
        fun start(c:Context,id:String,token:String){
            val app=c.applicationContext
            ContextCompat.startForegroundService(app,Intent(app,BtRobotService::class.java)
                .setAction(ACTION_START).putExtra(EXTRA_SESSION,id).putExtra(EXTRA_TOKEN,token))
        }
    }

    private val executor=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    private val stopped=AtomicBoolean(false)
    @Volatile private var activeId:String?=null
    @Volatile private var activeToken:String?=null
    @Volatile private var activeOwner:String?=null
    private var wakeLock:PowerManager.WakeLock?=null

    private val heartbeat=object:Runnable{
        override fun run(){
            val id=activeId?:return
            val token=activeToken?:return
            val owner=activeOwner?:return
            if(stopped.get())return
            val store=BtRobotStore(applicationContext)
            if(!store.heartbeat(id,token,owner)){
                stopped.set(true)
                return
            }
            refreshWakeLock()
            store.session(id)?.let{session->runCatching{BtRobotNotification.notify(applicationContext,session)}}
            handler.postDelayed(this,5000L)
        }
    }

    override fun onBind(intent:Intent?):IBinder?=null

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==ACTION_STOP){
            activeId?.let{BtRobotWorker.pause(applicationContext,it)}
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val id=intent?.getStringExtra(EXTRA_SESSION)
        val token=intent?.getStringExtra(EXTRA_TOKEN)
        val store=BtRobotStore(applicationContext)
        if(id.isNullOrBlank()||token.isNullOrBlank()||!store.current(id,token)){
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if(activeId==id&&activeToken==token&&activeOwner!=null)return START_NOT_STICKY
        if(activeId!=null){
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try{
            val initial=store.session(id)!!
            ServiceCompat.startForeground(this,BtRobotNotification.ID,
                BtRobotNotification.build(this,initial),
                if(Build.VERSION.SDK_INT>=29)ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        }catch(e:Exception){
            store.status(id,token,"Android 不允許啟動持續回測服務："+(e.message?:"").take(110),"RETRY")
            runCatching{BtRobotWorker.scheduleRecovery(applicationContext,id,token,15)}
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val owner="service:"+UUID.randomUUID()
        if(!store.claim(id,token,owner)){
            stopSelf(startId)
            return START_NOT_STICKY
        }
        stopped.set(false)
        activeId=id;activeToken=token;activeOwner=owner
        store.status(id,token,"持續運算服務啟動，從已保存的條件分段接續","RUNNING",owner)
        refreshWakeLock()
        handler.post(heartbeat)

        executor.execute{
            try{
                while(!stopped.get()&&store.owns(id,token,owner)){
                    when(BtRobotRunner.runSlice(applicationContext,id,token,owner,
                        stopped={stopped.get()||Thread.currentThread().isInterrupted},
                        onBest={seq,profit->notifyBest(seq,profit)})){
                        BtRobotRunner.Outcome.CONTINUE->continue
                        BtRobotRunner.Outcome.DONE,
                        BtRobotRunner.Outcome.STOP,
                        BtRobotRunner.Outcome.FAILED->break
                        BtRobotRunner.Outcome.RETRY->{
                            store.release(id,token,owner,"本輪已保存；系統正在安排備援接續")
                            runCatching{BtRobotWorker.scheduleRecovery(applicationContext,id,token,20)}
                            break
                        }
                    }
                }
            }catch(e:Exception){
                if(store.owns(id,token,owner)){
                    store.release(id,token,owner,"背景回測暫停："+(e.message?:"").take(100)+"；等待接續")
                    runCatching{BtRobotWorker.scheduleRecovery(applicationContext,id,token,20)}
                }
            }catch(e:OutOfMemoryError){
                if(store.owns(id,token,owner)){
                    store.status(id,token,"記憶體不足，本輪未提交；原交易紀錄與快取保留","ERROR",owner)
                }
            }finally{
                handler.removeCallbacks(heartbeat)
                releaseWakeLock()
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+ may time out dataSync after its daily foreground quota. */
    override fun onTimeout(startId:Int,fgsType:Int){
        interrupted("Android 背景服務執行時間已達系統上限，排程接續")
        stopSelf(startId)
    }

    private fun interrupted(message:String){
        stopped.set(true)
        val id=activeId;val token=activeToken;val owner=activeOwner
        if(id!=null&&token!=null&&owner!=null){
            val store=BtRobotStore(applicationContext)
            if(store.release(id,token,owner,message))
                runCatching{BtRobotWorker.scheduleRecovery(applicationContext,id,token,30)}
        }
    }

    override fun onDestroy(){
        interrupted("Android 已停止機器人前景服務；從安全斷點等待系統接續")
        handler.removeCallbacks(heartbeat)
        releaseWakeLock()
        executor.shutdownNow()
        super.onDestroy()
    }

    /** Renew a bounded CPU wake lock while a user-authorized scan is active. */
    private fun refreshWakeLock(){
        if(stopped.get())return
        runCatching{
            if(wakeLock?.isHeld!=true){
                val power=getSystemService(Context.POWER_SERVICE) as PowerManager
                val lock=wakeLock?:power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "TWBoardingScanner:RobotBacktest").also{
                        it.setReferenceCounted(false)
                        wakeLock=it
                    }
                lock.acquire(8*60*1000L)
            }
        }
    }
    private fun releaseWakeLock(){
        runCatching{if(wakeLock?.isHeld==true)wakeLock?.release()}
    }
    private fun notifyBest(seq:Long,profit:Double){
        runCatching{
            val manager=getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.createNotificationChannel(android.app.NotificationChannel(
                "robot-best","機器人最佳收益",android.app.NotificationManager.IMPORTANCE_DEFAULT))
            val open=android.app.PendingIntent.getActivity(this,4221,
                Intent(this,com.rex.twboardingscanner.ui.BtRobotActivity::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            manager.notify(4221,androidx.core.app.NotificationCompat.Builder(this,"robot-best")
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("目前已測最佳 · 第 $seq 組")
                .setContentText(String.format(java.util.Locale.TAIWAN,"總損益 %+,.0f 元",profit))
                .setContentIntent(open).setAutoCancel(true).build())
        }
    }
}
