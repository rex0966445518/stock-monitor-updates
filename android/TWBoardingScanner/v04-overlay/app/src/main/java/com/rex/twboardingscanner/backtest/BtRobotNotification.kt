package com.rex.twboardingscanner.backtest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.rex.twboardingscanner.ui.BtRobotActivity

/** Long-running robot notification is not tied to a visible screen or JobScheduler. */
internal object BtRobotNotification {
    const val ID=4220
    const val CHANNEL="robot-foreground-v2"

    private fun manager(c:Context):NotificationManager {
        val m=c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        m.createNotificationChannel(NotificationChannel(CHANNEL,
            "自動測試機器人 · 持續運算",NotificationManager.IMPORTANCE_LOW))
        return m
    }

    fun notify(c:Context,session:org.json.JSONObject){
        manager(c).notify(ID,build(c,session))
    }

    fun build(c:Context,session:org.json.JSONObject):Notification {
        manager(c)
        val tested=session.optLong("tested")
        val sweep=BtRobotSweep.read(session.optJSONObject("sweep"))
        val total=runCatching{
            sweep?.total?.toString()?:BtRobotSingleOption.total(
                session.optString("required","0"),
                session.optString("forbidden","0"),
                BtSettingsCodec.decode(session.getJSONObject("settings")).enabledRadars,
                BtRobotSingleOption.baselineFirst(session)).toString()
        }.getOrDefault("—")
        val seq=tested+1
        val stageDone=if(session.optLong("phaseSeq")==seq)session.optInt("phaseDone")else 0
        val stageTotal=if(session.optLong("phaseSeq")==seq)session.optInt("phaseTotal")else 0
        val detail="已完成 $tested/$total 輪"+if(stageTotal>0)
            " · 本輪 $stageDone/$stageTotal 檔" else " · "+session.optString("message","準備運算").take(50)
        val open=PendingIntent.getActivity(c,4220,
            Intent(c,BtRobotActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(c,4223,
            Intent(c,BtRobotService::class.java).setAction(BtRobotService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(c,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("AI-離職神器 · 自動測試機器人運算中")
            .setContentText(detail).setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(stageTotal.coerceAtLeast(0),stageDone.coerceAtLeast(0),stageTotal<=0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"暫停回測",stop)
            .build()
    }
}
