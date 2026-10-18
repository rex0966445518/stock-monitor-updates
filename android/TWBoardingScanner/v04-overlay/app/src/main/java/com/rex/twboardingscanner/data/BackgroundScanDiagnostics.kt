package com.rex.twboardingscanner.data

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Local-only diagnostics. Never sends private device data to a server. */
object BackgroundScanDiagnostics {
    fun collect(context:Context):String {
        val app=context.applicationContext
        val s=BackgroundScanState.read(app)
        val now=System.currentTimeMillis()
        val pm=app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val unrestricted=runCatching{pm.isIgnoringBatteryOptimizations(app.packageName)}.getOrNull()
        val notifications=runCatching{NotificationManagerCompat.from(app).areNotificationsEnabled()}.getOrNull()
        val jobs=runCatching{
            WorkManager.getInstance(app).getWorkInfosForUniqueWork(BackgroundScanState.WORK)
                .get(5,TimeUnit.SECONDS).joinToString("；"){
                    "state="+it.state+" attempts="+it.runAttemptCount+" id="+it.id.toString().take(8)
                }
        }.getOrElse{"查詢失敗："+(it.message?:"未知錯誤")}
        fun age(ts:Long):String=if(ts<=0)"未紀錄" else "${((now-ts).coerceAtLeast(0))/1000} 秒前"
        val appVersion=runCatching{app.packageManager.getPackageInfo(app.packageName,0).versionName}.getOrNull()
        return buildString{
            appendLine("AI-離職神器 背景掃描診斷（僅儲存在本機）")
            appendLine("App: $appVersion · Android SDK: ${Build.VERSION.SDK_INT}")
            appendLine("狀態：${s.state}；工作批次：${s.id.take(8)}；擁有者：${s.owner.ifBlank{"尚未啟動"}}")
            appendLine("訊息：${s.message}")
            appendLine("已掃描：${s.done}/${s.total}（${s.percent}%）；已分析：${s.analyzed}；待查核：${s.pending}")
            appendLine("最後服務心跳：${age(s.heartbeatAt)}；最後股數進展：${age(s.lastAdvanceAt)}")
            appendLine("通知權限：${if(notifications==true)"已允許" else if(notifications==false)"已關閉" else "未知"}")
            appendLine("忽略電池最佳化：${if(unrestricted==true)"是" else if(unrestricted==false)"否" else "未知"}")
            appendLine("WorkManager：$jobs")
            appendLine("檢查時刻：${java.time.Instant.ofEpochMilli(now)}")
            appendLine()
            appendLine("最近程式錯誤診斷：")
            appendLine(ScanDiagnostics.read(app).takeLast(2400))
        }
    }
}
