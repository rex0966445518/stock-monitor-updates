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

object BtSettingsCodec {
    fun capture(c:Context,start:LocalDate,end:LocalDate,capital:Double,codes:String):BtSettings {
        val prefs=c.getSharedPreferences("scanner_filters",0)
        val rules=com.rex.twboardingscanner.domain.RadarType.entries.associateWith { type ->
            val all=com.rex.twboardingscanner.domain.ScanConditions.forRadar(type)
            prefs.getStringSet("rules_${com.rex.twboardingscanner.domain.ScanConditions.VERSION}_${type.name}",null)?.intersect(all.map{it.id}.toSet())
                ?:all.filter{it.defaultEnabled}.map{it.id}.toSet()
        }
        val allSectors=com.rex.twboardingscanner.domain.StockSector.entries.toSet()
        val saved=prefs.getStringSet("enabled_sectors",null)
        val sectors=saved?.mapNotNull {name->allSectors.firstOrNull{it.name==name}}?.toMutableSet()?:allSectors.toMutableSet()
        if(saved!=null&&allSectors.filter{it!=com.rex.twboardingscanner.domain.StockSector.UNKNOWN}.all{it.name in saved})sectors.add(com.rex.twboardingscanner.domain.StockSector.UNKNOWN)
        return BtSettings(start,end,capital,codes,rules.mapValues{it.value.toSet()},sectors.ifEmpty{allSectors}.toSet())
    }
    fun encode(s:BtSettings)=JSONObject().put("strategyVersion",2).put("rulesVersion",com.rex.twboardingscanner.domain.ScanConditions.VERSION)
        .put("start",s.start.toString()).put("end",s.end.toString()).put("capital",s.capital).put("codes",s.codes)
        .put("targetNetPct",3).put("rules",JSONObject().apply{s.rules.forEach{(type,ids)->put(type.name,JSONArray(ids.sorted()))}})
        .put("sectors",JSONArray(s.sectors.map{it.name}.sorted()))
    fun decode(o:JSONObject):BtSettings {
        require(o.getInt("strategyVersion")==2&&o.getString("rulesVersion")==com.rex.twboardingscanner.domain.ScanConditions.VERSION){"回測條件版本已變更，請重新開始"}
        val rules=com.rex.twboardingscanner.domain.RadarType.entries.associateWith{type->val a=o.getJSONObject("rules").getJSONArray(type.name);(0 until a.length()).map{a.getString(it)}.toSet()}
        rules.forEach{(type,ids)->require(ids.all{id->com.rex.twboardingscanner.domain.ScanConditions.forRadar(type).any{it.id==id}})}
        val a=o.getJSONArray("sectors");val sectors=(0 until a.length()).map{com.rex.twboardingscanner.domain.StockSector.valueOf(a.getString(it))}.toSet()
        return BtSettings(LocalDate.parse(o.getString("start")),LocalDate.parse(o.getString("end")),o.getDouble("capital"),o.getString("codes"),rules,sectors)
    }
}

class BacktestStore(private val c:Context){
    private val prefs=c.getSharedPreferences("backtest_status",0)
    fun active()=prefs.getString("id","")?:""
    fun status()=prefs.getString("status","尚未開始")?:"尚未開始"
    fun state()=prefs.getString("state","")?:""
    fun begin(id:String,s:BtSettings){prefs.edit().putString("id",id).putString("state","RUNNING").putString("status","排入工作，等待網路…").putString("config",BtSettingsCodec.encode(s).toString()).commit()}
    fun update(id:String,status:String,state:String="RUNNING"){if(active()==id)prefs.edit().putString("status",status).putString("state",state).apply()}
    fun configuration():JSONObject?=runCatching{JSONObject(prefs.getString("config","")!!)}.getOrNull()
    fun resultId()=prefs.getString("resultId","")?:""
    fun result():JSONObject?=runCatching{val id=prefs.getString("resultId",null)?:return null;JSONObject(File(c.filesDir,"backtests/$id.json").readText())}.getOrNull()
    fun save(id:String,result:BtResult){
        val o=JSONObject().put("id",id).put("strategyVersion",2).put("settings",BtSettingsCodec.encode(result.settings)).put("requested",result.requested).put("loaded",result.loaded).put("excluded",JSONArray(result.excluded)).put("note",result.note).put("pendingChecks",result.pendingChecks)
            .put("run",runJson(result.run))
        val file=File(c.filesDir,"backtests/$id.json");file.parentFile?.mkdirs();val temp=File(file.path+".tmp");temp.writeText(o.toString());check(temp.renameTo(file))
        if(active()==id){prefs.edit().putString("resultId",id).putString("state","DONE").putString("status","回測完成，結果已保存").commit()}
    }
    private fun runJson(r:BtRun):JSONObject=JSONObject().put("profit",r.profit).put("equity",r.equity).put("cash",r.cash).put("realized",r.realized).put("unrealized",r.unrealized).put("dividend",r.dividendAccrued).put("drawdown",r.drawdown).put("winRate",r.winRate?:JSONObject.NULL).put("buys",r.buys)
        .put("closed",r.closed.size).put("holdings",JSONArray(r.holdings.map{JSONObject().put("code",it.code).put("name",it.name).put("radar",it.radar).put("markDate",it.markDate.toString()).put("mark",it.mark).put("cost",it.cost).put("entry",it.entry).put("entryDate",it.entryDate.toString()).put("entryTime","13:30（收盤模型）").put("lotId",it.lotId).put("target",it.target).put("shares",1000).put("unrealized",com.rex.twboardingscanner.paper.PaperEngine.netSell(it.mark)-it.cost)}))
        .put("curve",JSONArray(r.curve.map{JSONObject().put("date",it.date.toString()).put("equity",it.equity).put("selected",it.selected).put("buys",it.buys).put("sells",it.sells).put("skipped",it.skipped)}))
        .put("skipped",JSONArray(r.skipped.map{JSONObject().put("date",it.date.toString()).put("code",it.code).put("reason",it.reason)}))
        .put("trades",JSONArray(r.trades.map{JSONObject().put("date",it.date.toString()).put("time",it.time).put("timeKind",it.timeKind).put("signalDate",it.signalDate.toString()).put("dataDate",it.dataDate.toString()).put("lotId",it.lotId).put("target",it.target).put("code",it.code).put("name",it.name).put("radar",it.radar).put("side",it.side).put("shares",it.shares).put("price",it.price).put("fee",it.fee).put("tax",it.tax).put("pnl",it.pnl).put("reason",it.reason)}))
}

class BacktestWorker(c:Context,p:WorkerParameters):Worker(c,p){
    override fun doWork():Result {
        val store=BacktestStore(applicationContext);val runId=inputData.getString("id")?:return Result.failure()
        return try {
            setForegroundAsync(notification()).get()
            val s=BtSettingsCodec.decode(JSONObject(inputData.getString("settings")?:error("舊版回測工作不再支援，請重新開始")))
            require(s.start<=s.end&&s.end<LocalDate.now(com.rex.twboardingscanner.domain.RuleMetrics.TAIPEI))
            require(java.time.temporal.ChronoUnit.DAYS.between(s.start,s.end)<=730)
            val cancel={isStopped||store.active()!=runId}
            val progress={message:String->check(!cancel()){ "已取消回測" };store.update(runId,message)}
            val data=BacktestData(applicationContext).load(s,progress,cancel)
            val prepared=BacktestEngine.prepare(data.series,s,progress,cancel)
            val run=BacktestEngine.run(prepared,s,progress,cancel)
            check(!cancel()){ "已取消回測" }
            val note="現存上市櫃與目前產業分類，存在樣本／存活偏差；拆併股原始價未核對者排除。已勾 EPS／財報／法人缺少歷史證據時視為待查核，不可入選。A/B 排除當日日線，C 包含當日；C 完整收盤訊號與同收盤成交屬理想化假設，可能高估績效。日線不提供精確盤中成交時刻；13:30／09:00 皆為模型時間，觸價只記錄時段。股息列應收，不再投資；未達淨利 3% 的張數留倉估值，不強制賣出。"
            store.save(runId,BtResult(s,run,data.requested,data.series.size,data.excluded,note,prepared.pendingChecks));Result.success()
        }catch(e:Exception){store.update(runId,if(isStopped)"已取消；未完成結果不列為績效" else "回測中止：${e.message}",if(isStopped)"CANCELED" else "ERROR");Result.failure()}
    }
    private fun notification():ForegroundInfo {
        val manager=applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("backtest","歷史回測",NotificationManager.IMPORTANCE_LOW))
        val n=NotificationCompat.Builder(applicationContext,"backtest").setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("台股歷史回測執行中").setContentText("正在下載及計算歷史資料；可回 App 查看進度或取消").setOngoing(true).build()
        return if(Build.VERSION.SDK_INT>=29)ForegroundInfo(4201,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(4201,n)
    }
}
