package com.rex.twboardingscanner.backtest

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.rex.twboardingscanner.ui.BtRobotActivity
import java.util.Locale
import java.util.concurrent.TimeUnit

class BtRobotWorker(c:Context,p:WorkerParameters):Worker(c,p){
    companion object {
        const val WORK="backtest-robot"
        const val NOTE="固定區間內調整條件，屬樣本內最佳化，可能過度擬合；目前已測最佳不等於全組合最高或未來收益。現存上市櫃及目前產業分類有存活偏差；已勾選財報／法人缺歷史證據即不通過。A/B/E/F 排除當日日線，C 包含當日；C 同收盤成交屬理想化假設。交易時刻為日線模型，並非逐筆成交。扣買賣費稅及滑價；留倉以扣估計賣出費稅估值。股息與折讓金列應收不再投資；超過 5 個日曆日可扣費稅保本賣出，虧損續抱；另有下車條件時，以前一完整日線判斷、下一可交易日開盤模擬認賠出場。"
        fun start(c:Context,id:String){
            val store=BtRobotStore(c)
            require(BtSettingsCodec.decode(store.session(id)!!.getJSONObject("settings")).stockPolicy==com.rex.twboardingscanner.data.StockPolicyStore(c).read()){"禁股／限價已變更，請從機器人頁面開始新批次"}
            require(BtSettingsCodec.decode(store.session(id)!!.getJSONObject("settings")).marketGuardVersion>0){"舊批次未啟用大盤保護，請從機器人頁面開始新批次"}
            val token=store.resume(id)
            try{
                BtRobotService.start(c,id,token)
                scheduleRecovery(c,id,token,90)
            }catch(e:Exception){
                store.status(id,token,"前景服務無法啟動，切換系統備援排程："+(e.message?:"").take(120),"RETRY")
                try{enqueue(c,id,token,ExistingWorkPolicy.REPLACE,userStarted=true)}
                catch(error:Exception){store.status(id,token,"無法排入機器人工作："+error.message,"ERROR");throw error}
            }
        }
        internal fun request(id:String,token:String,delay:Long=0,userStarted:Boolean=false):OneTimeWorkRequest {
            val builder=OneTimeWorkRequestBuilder<BtRobotWorker>()
                .setInputData(workDataOf("session" to id,"token" to token,"userStarted" to userStarted))
                .setInitialDelay(delay,TimeUnit.SECONDS).setBackoffCriteria(BackoffPolicy.LINEAR,10,TimeUnit.SECONDS)
            if(RobotWorkPolicy.expedited(Build.VERSION.SDK_INT,userStarted,delay))builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            return builder.build()
        }
        private fun enqueue(c:Context,id:String,token:String,policy:ExistingWorkPolicy,delay:Long=0,userStarted:Boolean=false){
            WorkManager.getInstance(c).enqueueUniqueWork(WORK,policy,request(id,token,delay,userStarted)).result.get()
        }
        fun pause(c:Context,id:String){
            BtRobotStore(c).pause(id)
            c.applicationContext.stopService(Intent(c.applicationContext,BtRobotService::class.java))
            WorkManager.getInstance(c).cancelUniqueWork(WORK)
            WorkManager.getInstance(c).cancelUniqueWork(WORK+"-recover")
        }
        fun resumeForeground(c:Context,id:String,token:String){
            val store=BtRobotStore(c)
            if(!store.current(id,token)||store.freshService(id,token))return
            BtRobotService.start(c,id,token)
            scheduleRecovery(c,id,token,90)
        }
        fun scheduleRecovery(c:Context,id:String,token:String,delay:Long=90){
            if(!BtRobotStore(c).current(id,token))return
            val request=OneTimeWorkRequestBuilder<BtRobotRecoveryWorker>()
                .setInputData(workDataOf("session" to id,"token" to token))
                .setInitialDelay(delay,TimeUnit.SECONDS).build()
            WorkManager.getInstance(c).enqueueUniqueWork(WORK+"-recover",
                ExistingWorkPolicy.APPEND_OR_REPLACE,request)
        }
        fun recoverAfterStop(c:Context,id:String,token:String){
            val store=BtRobotStore(c);if(!store.current(id,token)||store.freshService(id,token))return
            store.status(id,token,"測試中斷，備援工作將讀取已儲存的條件分段","RETRY")
            val request=OneTimeWorkRequestBuilder<BtRobotRecoveryWorker>().setInputData(workDataOf("session" to id,"token" to token)).setInitialDelay(10,TimeUnit.SECONDS).build()
            WorkManager.getInstance(c).enqueueUniqueWork(WORK+"-recover",ExistingWorkPolicy.REPLACE,request)
        }
        internal fun recoveryAction(state:String,scheduled:Boolean,updatedAt:Long,now:Long):Int = when{
            scheduled->0
            state=="RUNNING"->1 // start the ten-second delay only after work is confirmed stopped
            state=="RETRY"&&now-updatedAt>=10000->2
            else->0 // includes a user's manual pause, completed and invalid-data states
        }
        internal fun recover(c:Context,id:String,token:String,now:Long=System.currentTimeMillis()){
            val store=BtRobotStore(c);if(!store.current(id,token))return
            if(store.freshService(id,token,now)){
                // Keep a single delayed watchdog while the service is healthy.
                // If the OS kills its process without onDestroy, the next run
                // can detect the stale heartbeat and resume via WorkManager.
                scheduleRecovery(c,id,token,90)
                return
            }
            val work=WorkManager.getInstance(c).getWorkInfosForUniqueWork(WORK).get()
            val s=store.session(id)!!
            val action=recoveryAction(s.optString("state"),work.any{!it.state.isFinished},s.optLong("updatedAt"),now)
            if(action==1)recoverAfterStop(c,id,token)
            else if(action==2){
                if(!store.current(id,token))return
                enqueue(c,id,token,ExistingWorkPolicy.REPLACE)
                store.status(id,token,"正在自動續跑；讀取已保存條件快取")
            }
        }
    }
    override fun doWork():Result {
        val id=inputData.getString("session")?:return Result.failure()
        val token=inputData.getString("token")?:return Result.failure()
        val store=BtRobotStore(applicationContext)
        if(!store.current(id,token))return Result.success()
        if(store.freshService(id,token))return Result.success()
        val owner="worker:"+java.util.UUID.randomUUID()
        if(!store.claim(id,token,owner))return Result.retry()
        try{
            if(RobotWorkPolicy.promoteForeground(Build.VERSION.SDK_INT,inputData.getBoolean("userStarted",false))){
                try{setForegroundAsync(foreground()).get()}
                catch(e:Exception){
                    if(!RobotWorkPolicy.foregroundDenied(e))throw e
                    store.status(id,token,"系統暫時限制備援常駐通知；等待恢復排程","RETRY",owner)
                    return Result.retry()
                }
            }
            return when(BtRobotRunner.runSlice(applicationContext,id,token,owner,
                stopped={isStopped},onBest={seq,profit->notifyBest(seq,profit)})){
                BtRobotRunner.Outcome.DONE,BtRobotRunner.Outcome.STOP->Result.success()
                BtRobotRunner.Outcome.FAILED->Result.failure()
                BtRobotRunner.Outcome.CONTINUE->{
                    if(store.owns(id,token,owner))
                        enqueue(applicationContext,id,token,ExistingWorkPolicy.APPEND_OR_REPLACE)
                    Result.success()
                }
                BtRobotRunner.Outcome.RETRY->{
                    if(store.owns(id,token,owner))
                        enqueue(applicationContext,id,token,ExistingWorkPolicy.APPEND_OR_REPLACE,10)
                    Result.success()
                }
            }
        }catch(e:Exception){
            if(!store.owns(id,token,owner))return Result.success()
            store.status(id,token,"備援排程異常："+(e.message?:"").take(100),"RETRY",owner)
            return Result.retry()
        } finally {
            if(store.owns(id,token,owner))store.release(id,token,owner,"等待背景備援下一段")
        }
    }
    override fun onStopped(){
        super.onStopped()
        val id=inputData.getString("session")?:return;val token=inputData.getString("token")?:return
        runCatching{recoverAfterStop(applicationContext,id,token)}
    }
    private fun manager():NotificationManager=(applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).also{
        it.createNotificationChannel(NotificationChannel("robot-progress","自動測試進度",NotificationManager.IMPORTANCE_LOW))
        it.createNotificationChannel(NotificationChannel("robot-best","機器人最佳收益",NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun intent()=PendingIntent.getActivity(applicationContext,4221,Intent(applicationContext,BtRobotActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun foreground():ForegroundInfo {
        manager()
        val n=NotificationCompat.Builder(applicationContext,"robot-progress").setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("自動回測機器人執行中")
            .setContentText("持續逐組回測；點擊可查看參數範圍進度或暫停").setContentIntent(intent()).setOngoing(true).build()
        return if(Build.VERSION.SDK_INT>=29)ForegroundInfo(4220,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)else ForegroundInfo(4220,n)
    }
    private fun notifyBest(seq:Long,profit:Double){runCatching{
        manager().notify(4221,NotificationCompat.Builder(applicationContext,"robot-best").setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("目前已測最佳 · 第 $seq 組")
            .setContentText(String.format(Locale.TAIWAN,"總損益 %+,.0f 元",profit))
            .setContentIntent(intent()).setAutoCancel(true).build())
    }}
}

class BtRobotRecoveryWorker(c:Context,p:WorkerParameters):Worker(c,p){
    override fun doWork():Result {
        val id=inputData.getString("session")?:return Result.success();val token=inputData.getString("token")?:return Result.success()
        return try{BtRobotWorker.recover(applicationContext,id,token);Result.success()}catch(_:Exception){Result.retry()}
    }
}
