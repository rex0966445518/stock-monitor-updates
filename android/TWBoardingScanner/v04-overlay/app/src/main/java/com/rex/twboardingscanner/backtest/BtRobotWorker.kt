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
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

class BtRobotWorker(c:Context,p:WorkerParameters):Worker(c,p){
    companion object {
        const val WORK="backtest-robot"
        const val NOTE="固定區間內調整條件，屬樣本內最佳化，可能過度擬合；目前已測最佳不等於全組合最高或未來收益。現存上市櫃及目前產業分類有存活偏差；已勾選財報／法人缺歷史證據即不通過。A/B 排除當日日線，C 包含當日；C 同收盤成交屬理想化假設。交易時刻為日線模型，並非逐筆成交。扣買賣費稅及滑價；留倉以扣估計賣出費稅估值。股息與折讓金列應收不再投資；超過 5 個日曆日可扣費稅保本賣出，虧損續抱。"
        private data class Runtime(val id:String,val data:BacktestData.Loaded,val index:BtConditionIndex,val execution:BtExecutionIndex)
        private val runLock=Any()
        private var cached:Runtime?=null
        fun start(c:Context,id:String){
            val store=BtRobotStore(c);val token=store.resume(id)
            try{enqueue(c,id,token,ExistingWorkPolicy.REPLACE)}catch(e:Exception){store.status(id,token,"無法排入測試：${e.message}","ERROR");throw e}
        }
        private fun enqueue(c:Context,id:String,token:String,policy:ExistingWorkPolicy,delay:Long=0){
            val request=OneTimeWorkRequestBuilder<BtRobotWorker>().setInputData(workDataOf("session" to id,"token" to token)).setInitialDelay(delay,TimeUnit.SECONDS).setBackoffCriteria(BackoffPolicy.LINEAR,10,TimeUnit.SECONDS).build()
            WorkManager.getInstance(c).enqueueUniqueWork(WORK,policy,request).result.get()
        }
        fun pause(c:Context,id:String){BtRobotStore(c).pause(id);WorkManager.getInstance(c).cancelUniqueWork(WORK);WorkManager.getInstance(c).cancelUniqueWork(WORK+"-recover")}
        fun recoverAfterStop(c:Context,id:String,token:String){
            val store=BtRobotStore(c);if(!store.current(id,token))return
            store.status(id,token,"測試中斷，10 秒後自動接續；沿用歷史條件快取","RETRY")
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
    override fun doWork():Result = synchronized(runLock){
        val id=inputData.getString("session")?:return@synchronized Result.failure()
        val token=inputData.getString("token")?:return@synchronized Result.failure()
        val store=BtRobotStore(applicationContext)
        val cancel={isStopped||!store.current(id,token)}
        if(cancel())return@synchronized Result.success()
        try{
            setForegroundAsync(foreground()).get()
            val session=store.session(id)!!
            require(BtRobotCheckpoint.compatible(session)){"回測引擎版本不相容；原紀錄可檢閱，請建立新測試"}
            val base=BtSettingsCodec.decode(session.getJSONObject("settings"));BtRobotSpace.validate(base)
            var lastProgress=0L
            val progress={msg:String->check(!cancel()){ "已暫停測試" };val now=System.currentTimeMillis();if(now-lastProgress>=800){store.status(id,token,msg);lastProgress=now}}
            val runtime=cached?.takeIf{it.id==id}?:run{
                val file=store.dataset(id)
                val data=if(file.exists()){
                    progress("讀取此測試的固定歷史資料…")
                    val expected=session.optString("datasetSha256")
                    if(expected.isNotEmpty())require(digest(file)==expected){"資料快照校驗失敗；請建立新測試，避免混用行情"}
                    BtRobotDataset.read(file)
                }else{
                    require(session.optLong("tested")==0L){"原歷史資料已遺失，不能接續比較；請建立新測試"}
                    val loaded=BacktestData(applicationContext).load(base,progress,cancel)
                    check(!cancel()){ "已暫停測試" };BtRobotDataset.write(file,loaded);loaded
                }
                store.coverage(id,token,data.series.size,data.requested,digest(file))
                val indexFile=store.conditionFile(id);val fingerprint=BtConditionIndex.fingerprint(digest(file),base)
                val index=if(indexFile.exists()){
                    progress("讀取已保存的歷史條件快取；不重新預算…")
                    BtConditionIndex.read(indexFile,data.series,fingerprint)
                }else{
                    val compiled=BtConditionIndex.build(data.series,base,progress,cancel)
                    check(!cancel()){ "已暫停測試" };compiled.save(indexFile,fingerprint);compiled
                }
                Runtime(id,data,index,BacktestEngine.executionIndex(data.series)).also{cached=it}
            }
            val batchStart=System.currentTimeMillis();var completed=0
            while(!cancel()&&completed<30&&(completed==0||System.currentTimeMillis()-batchStart<120000)){
                require(applicationContext.filesDir.usableSpace>128L*1024*1024){"儲存空間不足 128 MB，請先釋出空間"}
                val candidate=store.next(id)
                if(candidate==null){store.status(id,token,"所有組合測試完成；最高收益僅限本次歷史樣本","DONE");cached=null;return@synchronized Result.success()}
                val settings=base.copy(rules=BtRobotSpace.rules(candidate.key));val started=System.currentTimeMillis()
                val seq=store.session(id)!!.optLong("tested")+1
                store.status(id,token,"正在測試第 $seq 組 · ${candidate.key}")
                val prepared=runtime.index.prepare(settings,cancel)
                val run=BacktestEngine.run(prepared,settings,cancel=cancel,execution=runtime.execution)
                check(!cancel()){ "已暫停測試" }
                val better=store.commit(id,token,candidate,BtResult(settings,run,runtime.data.requested,runtime.data.series.size,runtime.data.excluded,NOTE,prepared.pendingChecks),started)
                if(better)notifyBest(seq,run.profit)
                completed++
            }
            if(cancel())return@synchronized Result.success()
            // Bounded batches yield to Android scheduling. A stopped batch commits only completed trials.
            enqueue(applicationContext,id,token,ExistingWorkPolicy.APPEND_OR_REPLACE)
            Result.success()
        }catch(e:Exception){
            if(cancel())return@synchronized if(isStopped)Result.retry() else Result.success()
            if(e is IllegalArgumentException){
                store.status(id,token,"需處理：${e.message?.take(180)}；完成紀錄保留，可重試","ERROR")
                return@synchronized Result.failure()
            }
            store.status(id,token,"中斷：${e.message?.take(120)}；10 秒後自動接續","RETRY")
            try{enqueue(applicationContext,id,token,ExistingWorkPolicy.APPEND_OR_REPLACE,10);Result.success()}catch(_:Exception){Result.retry()}
        }
    }
    override fun onStopped(){
        super.onStopped()
        val id=inputData.getString("session")?:return;val token=inputData.getString("token")?:return
        runCatching{recoverAfterStop(applicationContext,id,token)}
    }
    private fun digest(file:java.io.File):String {
        val hash=MessageDigest.getInstance("SHA-256");file.inputStream().use{val b=ByteArray(65536);while(true){val n=it.read(b);if(n<0)break;hash.update(b,0,n)}}
        return hash.digest().joinToString(""){"%02x".format(it)}
    }
    private fun manager():NotificationManager=(applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).also{
        it.createNotificationChannel(NotificationChannel("robot-progress","自動測試進度",NotificationManager.IMPORTANCE_LOW))
        it.createNotificationChannel(NotificationChannel("robot-best","機器人最佳收益",NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun intent()=PendingIntent.getActivity(applicationContext,4221,Intent(applicationContext,BtRobotActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun foreground():ForegroundInfo {
        manager()
        val n=NotificationCompat.Builder(applicationContext,"robot-progress").setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("自動回測機器人執行中")
            .setContentText("持續測試 ABC 組合；點擊查看進度或暫停").setContentIntent(intent()).setOngoing(true).build()
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
