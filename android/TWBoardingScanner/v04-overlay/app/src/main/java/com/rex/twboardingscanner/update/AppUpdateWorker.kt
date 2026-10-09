package com.rex.twboardingscanner.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.rex.twboardingscanner.ui.AppUpdateActivity

class AppUpdateWorker(c:Context,p:WorkerParameters):Worker(c,p){
    companion object {
        const val WORK="app-github-update"
        fun start(c:Context):String {
            val store=AppUpdateStore(c);val id=store.begin()
            try{
                val request=OneTimeWorkRequestBuilder<AppUpdateWorker>().setInputData(workDataOf("id" to id)).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
                WorkManager.getInstance(c).enqueueUniqueWork(WORK,ExistingWorkPolicy.REPLACE,request)
            }catch(e:Exception){store.status(id,"ERROR","無法啟動更新，請重試");throw e}
            return id
        }
    }
    override fun doWork():Result {
        val store=AppUpdateStore(applicationContext);val id=inputData.getString("id")?:return Result.failure()
        val cancel={isStopped||!store.current(id)}
        return try{
            check(!cancel());setForegroundAsync(notification()).get()
            store.status(id,"CHECKING","正在向 GitHub 檢查最新版…")
            val source=AppUpdateSource();val spec=source.latest();check(!cancel())
            store.setSpec(id,spec)
            if(spec.versionCode<=AppUpdateInstaller.code(AppUpdateInstaller.installed(applicationContext))){
                store.status(id,"CURRENT","目前已是最新版本");return Result.success()
            }
            var last=0L
            val file=source.download(spec,store.directory,cancel){bytes,message->
                val now=System.currentTimeMillis()
                if(now-last>=400||bytes==spec.size){store.status(id,if(bytes==spec.size)"VERIFYING" else "DOWNLOADING",message,bytes);last=now}
            }
            check(!cancel());store.status(id,"VERIFYING","驗證安裝包版本與簽章…",spec.size)
            AppUpdateInstaller.verify(applicationContext,file,spec);check(!cancel())
            store.status(id,"READY","下載完成，已驗證更新包，可安裝",spec.size);Result.success()
        }catch(e:Exception){
            if(!isStopped)store.status(id,"ERROR",if(e is java.io.IOException)"網路中斷或下載失敗，已保留可用進度，請重試" else e.message?.take(160)?:"更新失敗，請重試")
            Result.failure()
        }
    }
    private fun notification():ForegroundInfo {
        val manager=applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("app_updates","軟件更新",NotificationManager.IMPORTANCE_LOW))
        val pending=PendingIntent.getActivity(applicationContext,4210,Intent(applicationContext,AppUpdateActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n=NotificationCompat.Builder(applicationContext,"app_updates").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("台股上車掃描器更新中").setContentText("點擊查看下載進度").setContentIntent(pending).setOngoing(true).build()
        return if(Build.VERSION.SDK_INT>=29)ForegroundInfo(4210,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)else ForegroundInfo(4210,n)
    }
}
