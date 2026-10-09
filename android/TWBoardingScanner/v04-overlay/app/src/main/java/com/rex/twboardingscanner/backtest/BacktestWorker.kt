package com.rex.twboardingscanner.backtest

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

class BacktestStore(private val c:Context){
    private val prefs=c.getSharedPreferences("backtest_status",0)
    fun active()=prefs.getString("id","")?:""
    fun status()=prefs.getString("status","尚未開始")?:"尚未開始"
    fun state()=prefs.getString("state","")?:""
    fun begin(id:String,s:BtSettings){prefs.edit().putString("id",id).putString("state","RUNNING").putString("status","排入工作，等待網路…").putString("config",config(s).toString()).commit()}
    fun update(id:String,status:String,state:String="RUNNING"){if(active()==id)prefs.edit().putString("status",status).putString("state",state).apply()}
    fun configuration():JSONObject?=runCatching{JSONObject(prefs.getString("config","")!!)}.getOrNull()
    fun resultId()=prefs.getString("resultId","")?:""
    fun result():JSONObject?=runCatching{val id=prefs.getString("resultId",null)?:return null;JSONObject(File(c.filesDir,"backtests/$id.json").readText())}.getOrNull()
    fun save(id:String,result:BtResult){
        val o=JSONObject().put("id",id).put("settings",config(result.settings)).put("requested",result.requested).put("loaded",result.loaded).put("excluded",JSONArray(result.excluded)).put("note",result.note)
            .put("run",runJson(result.run)).put("baseline",runJson(result.baseline))
        val file=File(c.filesDir,"backtests/$id.json");file.parentFile?.mkdirs();val temp=File(file.path+".tmp");temp.writeText(o.toString());check(temp.renameTo(file))
        if(active()==id){prefs.edit().putString("resultId",id).putString("state","DONE").putString("status","回測完成，結果已保存").commit()}
    }
    private fun config(s:BtSettings)=JSONObject().put("start",s.start.toString()).put("end",s.end.toString()).put("capital",s.capital).put("optimize",s.optimize).put("codes",s.codes)
    private fun runJson(r:BtRun):JSONObject=JSONObject().put("profit",r.profit).put("equity",r.equity).put("cash",r.cash).put("realized",r.realized).put("dividend",r.dividendAccrued).put("drawdown",r.drawdown).put("winRate",r.winRate?:JSONObject.NULL)
        .put("closed",r.closed.size).put("holdings",JSONArray(r.holdings.map{JSONObject().put("code",it.code).put("name",it.name).put("markDate",it.markDate.toString()).put("mark",it.mark).put("cost",it.cost)}))
        .put("curve",JSONArray(r.curve.map{JSONObject().put("date",it.date.toString()).put("equity",it.equity)}))
        .put("folds",JSONArray(r.folds.map{JSONObject().put("start",it.start.toString()).put("trainStart",it.trainStart.toString()).put("trainEnd",it.trainEnd.toString()).put("params",it.params.title).put("trials",it.trials).put("note",it.note).put("evaluations",JSONArray(it.evaluations.map{e->JSONObject().put("params",e.params.title).put("profit",e.profit).put("drawdown",e.drawdown).put("closed",e.closed).put("score",e.score)}))}))
        .put("trades",JSONArray(r.trades.map{JSONObject().put("date",it.date.toString()).put("signalDate",it.signalDate.toString()).put("code",it.code).put("name",it.name).put("radar",it.radar).put("side",it.side).put("shares",it.shares).put("price",it.price).put("fee",it.fee).put("tax",it.tax).put("pnl",it.pnl).put("reason",it.reason)}))
}

class BacktestWorker(c:Context,p:WorkerParameters):Worker(c,p){
    override fun doWork():Result {
        val store=BacktestStore(applicationContext);val runId=inputData.getString("id")?:return Result.failure()
        return try {
            setForegroundAsync(notification()).get()
            val s=BtSettings(LocalDate.parse(inputData.getString("start")),LocalDate.parse(inputData.getString("end")),inputData.getDouble("capital",3000000.0),inputData.getBoolean("optimize",true),inputData.getString("codes")?:"")
            require(s.start<=s.end&&s.end<LocalDate.now(com.rex.twboardingscanner.domain.RuleMetrics.TAIPEI))
            require(java.time.temporal.ChronoUnit.DAYS.between(s.start,s.end)<=730)
            val cancel={isStopped||store.active()!=runId}
            val progress={message:String->check(!cancel()){ "已取消回測" };store.update(runId,message)}
            val data=BacktestData(applicationContext).load(s,progress,cancel)
            val prepared=BacktestEngine.prepare(data.series,s,progress,cancel)
            val (run,baseline)=BacktestEngine.run(prepared,s,progress,cancel)
            check(!cancel()){ "已取消回測" }
            val note="現存上市櫃樣本，含存活偏差；非歷史完整成分股。歷史日線來自 Yahoo Finance，財報／法人不參與。拆併股原始價未核對者排除，可能造成額外樣本偏差。股息列應收，不供再投資，未計個人股息稅。"+
                if(s.optimize&&run.folds.isEmpty())" 區間不足 80 個交易日，已改為基準回測，未宣稱優化。" else ""
            store.save(runId,BtResult(s,run,baseline,data.requested,data.series.size,data.excluded,note));Result.success()
        }catch(e:Exception){store.update(runId,if(isStopped)"已取消；未完成結果不列為績效" else "回測中止：${e.message}",if(isStopped)"CANCELED" else "ERROR");Result.failure()}
    }
    private fun notification():ForegroundInfo {
        val manager=applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("backtest","歷史回測",NotificationManager.IMPORTANCE_LOW))
        val n=NotificationCompat.Builder(applicationContext,"backtest").setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("台股歷史回測執行中").setContentText("正在下載及計算歷史資料；可回 App 查看進度或取消").setOngoing(true).build()
        return if(Build.VERSION.SDK_INT>=29)ForegroundInfo(4201,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(4201,n)
    }
}
