package com.rex.twboardingscanner.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.rex.twboardingscanner.ui.MainActivity

/** Shared notification for the direct foreground service and recovery Worker. */
object FullMarketScanNotification {
    const val ID=4330
    const val CHANNEL="background-market-scan"
    fun manager(c:Context):NotificationManager {
        val mgr=c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(NotificationChannel(
            CHANNEL,"AI-離職神器持續掃描",NotificationManager.IMPORTANCE_LOW))
        return mgr
    }

    fun build(c:Context,done:Int,total:Int,message:String):Notification {
        manager(c)
        val open=PendingIntent.getActivity(c,4331,
            Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel=PendingIntent.getService(c,4332,
            Intent(c,FullMarketScanService::class.java).setAction(FullMarketScanService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val percent=if(total>0)(done.toLong()*100/total).toInt().coerceIn(0,100) else 0
        val detail=if(total>0)"已掃描 $done / $total 檔 · $percent% · $message" else message
        return NotificationCompat.Builder(c,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("AI-離職神器正在背景掃描")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(total.coerceAtLeast(0),done.coerceAtLeast(0),total<=0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"停止掃描",cancel)
            .build()
    }
}
