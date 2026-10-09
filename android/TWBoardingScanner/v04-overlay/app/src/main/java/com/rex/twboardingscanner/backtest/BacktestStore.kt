package com.rex.twboardingscanner.backtest

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One immutable completed report per run, plus small journal entries for browsing. */
class BacktestStore(private val c:Context){
    companion object { private val lock=Any() }
    private val prefs=c.getSharedPreferences("backtest_status",0)
    private val reports=File(c.filesDir,"backtests")
    private val journal=File(c.filesDir,"backtest-journal")
    private fun valid(id:String)=id.matches(Regex("[A-Za-z0-9_-]{1,100}"))
    private fun reportFile(id:String):File {require(valid(id));return File(reports,"$id.json")}
    private fun journalFile(id:String):File {require(valid(id));return File(journal,"$id.json")}
    private fun read(file:File)=runCatching{JSONObject(file.readText())}.getOrNull()
    private fun write(file:File,data:JSONObject){
        file.parentFile?.mkdirs()
        val temp=File(file.path+".tmp")
        temp.outputStream().use{it.write(data.toString().toByteArray(Charsets.UTF_8));it.fd.sync()}
        check(temp.renameTo(file)){"無法保存回測日誌"}
    }
    fun active()=prefs.getString("id","")?:""
    fun status()=prefs.getString("status","尚未開始")?:"尚未開始"
    fun state()=prefs.getString("state","")?:""
    fun configuration():JSONObject?=runCatching{JSONObject(prefs.getString("config","")!!)}.getOrNull()
    fun saveDraft(settings:BtSettings)=synchronized(lock){prefs.edit().putString("config",BtSettingsCodec.encode(settings).toString()).commit()}
    fun resultId()=prefs.getString("resultId","")?:""
    fun result():JSONObject?=synchronized(lock){resultId().takeIf(::valid)?.let{read(reportFile(it))}}
    private fun appVersion()=runCatching{c.packageManager.getPackageInfo(c.packageName,0).versionName}.getOrNull()?:"未知"
    private fun currentEntry(id:String)=read(journalFile(id))?:JSONObject().put("id",id)
        .put("settings",configuration()?:JSONObject()).put("startedAt",0).put("legacyImported",true)
    private fun endCurrent(message:String){
        val id=active();if(!valid(id)||state()!="RUNNING")return
        val entry=currentEntry(id).put("state","CANCELED").put("message",message).put("finishedAt",System.currentTimeMillis())
        write(journalFile(id),entry)
    }
    fun begin(id:String,s:BtSettings)=synchronized(lock){
        require(valid(id)&&!journalFile(id).exists()&&!reportFile(id).exists()){"回測日誌編號已存在"}
        endCurrent("已被新的回測取代；未完成結果不列為績效")
        val settings=BtSettingsCodec.encode(s)
        val entry=JSONObject().put("id",id).put("journalVersion",1).put("strategyVersion",s.strategyVersion)
            .put("appVersion",appVersion()).put("startedAt",System.currentTimeMillis())
            .put("finishedAt",0).put("state","RUNNING").put("message","排入工作，等待網路…").put("settings",settings)
        write(journalFile(id),entry)
        check(prefs.edit().putString("id",id).putString("state","RUNNING").putString("status","排入工作，等待網路…")
            .putString("config",settings.toString()).remove("resultId").commit())
    }
    fun update(id:String,status:String,state:String="RUNNING")=synchronized(lock){
        if(active()!=id||this.state()!="RUNNING"||!valid(id))return@synchronized
        if(state!="RUNNING")write(journalFile(id),currentEntry(id).put("state",state).put("message",status).put("finishedAt",System.currentTimeMillis()))
        prefs.edit().putString("status",status).putString("state",state).commit()
        Unit
    }
    /** Invalidate the worker before cancellation. Historical reports and settings stay intact. */
    fun resetCurrent()=synchronized(lock){
        endCurrent("使用者清除歸零，當次回測已停止；未完成結果不列為績效")
        check(prefs.edit().remove("id").remove("resultId").putString("state","IDLE")
            .putString("status","已清除歸零；歷史日誌與回測設定保留").commit())
    }
    fun save(id:String,result:BtResult):Boolean=synchronized(lock){
        // A late worker cannot revive a canceled/reset run or overwrite a completed journal.
        if(active()!=id||state()!="RUNNING"||!valid(id)||reportFile(id).exists())return@synchronized false
        val entry=currentEntry(id)
        val settings=entry.optJSONObject("settings")?:BtSettingsCodec.encode(result.settings)
        val o=JSONObject(entry.toString()).put("strategyVersion",settings.optInt("strategyVersion",2)).put("state","DONE")
            .put("finishedAt",System.currentTimeMillis()).put("message","回測完成，結果已保存")
            .put("settings",settings).put("requested",result.requested).put("loaded",result.loaded)
            .put("excluded",JSONArray(result.excluded)).put("note",result.note).put("pendingChecks",result.pendingChecks).put("run",runJson(result.run))
        write(reportFile(id),o)
        write(journalFile(id),summary(o))
        check(prefs.edit().putString("resultId",id).putString("state","DONE").putString("status","回測完成，已新增獨立日誌").commit())
        true
    }
    private fun summary(o:JSONObject):JSONObject {
        val entry=JSONObject()
        listOf("id","journalVersion","strategyVersion","appVersion","startedAt","finishedAt","state","message","settings","legacyImported","requested","loaded","importDigest","importedAt","originalId").forEach{key->if(o.has(key))entry.put(key,o.get(key))}
        val run=o.optJSONObject("run")
        if(run!=null)entry.put("profit",run.getDouble("profit")).put("closed",run.getInt("closed"))
            .put("buys",run.optInt("buys",(run.optJSONArray("trades")?.length()?:0)-run.getInt("closed")))
            .put("holdings",run.getJSONArray("holdings").length()).put("state","DONE")
        return entry
    }
    /** Called on a background thread; import pre-journal reports without changing their content. */
    fun history():List<JSONObject> = synchronized(lock){
        reports.listFiles()?.filter{it.extension=="json"&&valid(it.nameWithoutExtension)}?.forEach{file->
            val index=journalFile(file.nameWithoutExtension)
            val prior=read(index)
            if(prior==null||prior.optString("state")!="DONE"){
                val data=read(file)
                if(data?.optJSONObject("run")!=null){
                    val entry=summary(data).put("id",file.nameWithoutExtension)
                    if(!entry.has("finishedAt"))entry.put("finishedAt",file.lastModified()).put("startedAt",0).put("legacyImported",true)
                    write(index,entry)
                }
            }
        }
        journal.listFiles()?.filter{it.extension=="json"}?.mapNotNull(::read)?.sortedWith(
            compareByDescending<JSONObject>{it.optLong("startedAt").takeIf{t->t>0}?:it.optLong("finishedAt")}.thenByDescending{it.optString("id")}
        ).orEmpty()
    }
    fun log(id:String):JSONObject?=synchronized(lock){
        if(!valid(id))return@synchronized null
        val report=read(reportFile(id));val entry=read(journalFile(id))
        if(report!=null){
            if(entry!=null)listOf("startedAt","finishedAt","legacyImported","state","appVersion").forEach{key->if(!report.has(key)&&entry.has(key))report.put(key,entry.get(key))}
            report
        }else entry
    }
    /** Restore a user-exported completed report as a separate archive; never activate a worker. */
    fun importReport(raw:JSONObject):String=synchronized(lock){
        val data=JSONObject(raw.toString());val settings=data.getJSONObject("settings");val run=data.getJSONObject("run")
        require(settings.getDouble("capital").isFinite()&&settings.getDouble("capital")>0){"本金無效"}
        java.time.LocalDate.parse(settings.getString("start"));java.time.LocalDate.parse(settings.getString("end"))
        listOf("profit","equity","cash","realized","dividend","drawdown").forEach{require(run.getDouble(it).isFinite()){"報告金額無效"}}
        require(run.getInt("closed")>=0)
        val curve=run.getJSONArray("curve");require(curve.length() in 1..1000){"每日紀錄無效"}
        for(i in 0 until curve.length()){val day=curve.getJSONObject(i);java.time.LocalDate.parse(day.getString("date"));require(day.getDouble("equity").isFinite())}
        val trades=run.getJSONArray("trades")
        for(i in 0 until trades.length()){val trade=trades.getJSONObject(i);java.time.LocalDate.parse(trade.getString("date"));require(trade.getString("side") in listOf("BUY","SELL"));require(trade.getDouble("price").isFinite()&&trade.getDouble("pnl").isFinite());trade.getString("code");trade.getString("name")}
        val holdings=run.getJSONArray("holdings")
        for(i in 0 until holdings.length()){val h=holdings.getJSONObject(i);h.getString("code");h.getString("name");h.getString("markDate");require(h.getDouble("mark").isFinite())}
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(raw.toString().toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
        journal.listFiles()?.mapNotNull(::read)?.firstOrNull{it.optString("importDigest")==digest}?.let{return@synchronized it.getString("id")}
        val id="import-"+java.util.UUID.randomUUID().toString()
        data.put("originalId",data.optString("id")).put("id",id).put("state","DONE").put("importDigest",digest).put("importedAt",System.currentTimeMillis())
        if(!data.has("excluded"))data.put("excluded",JSONArray())
        if(!data.has("note"))data.put("note","使用者匯入的歷史回測紀錄；保留原始策略與績效。")
        if(!data.has("requested"))data.put("requested",0)
        if(!data.has("loaded"))data.put("loaded",0)
        write(reportFile(id),data);write(journalFile(id),summary(data));id
    }
    private fun runJson(r:BtRun):JSONObject=JSONObject().put("profit",r.profit).put("equity",r.equity).put("cash",r.cash).put("realized",r.realized).put("unrealized",r.unrealized).put("dividend",r.dividendAccrued).put("rebateAccrued",r.rebateAccrued)
        .put("rebateMonths",JSONArray(r.rebateMonths.values.map{JSONObject().put("month",it.month).put("buyAmount",it.buyAmount).put("sellAmount",it.sellAmount).put("buyTrades",it.buyTrades).put("sellTrades",it.sellTrades).put("turnover",it.turnover).put("rate",it.rate).put("amount",it.amount)})).put("drawdown",r.drawdown).put("winRate",r.winRate?:JSONObject.NULL).put("buys",r.buys)
        .put("closed",r.closed.size).put("holdings",JSONArray(r.holdings.map{JSONObject().put("code",it.code).put("name",it.name).put("radar",it.radar).put("markDate",it.markDate.toString()).put("mark",it.mark).put("cost",it.cost).put("entry",it.entry).put("entryDate",it.entryDate.toString()).put("entryTime","13:30（收盤模型）").put("lotId",it.lotId).put("target",it.target).put("shares",1000).put("unrealized",com.rex.twboardingscanner.paper.PaperEngine.netSell(it.mark)-it.cost)}))
        .put("curve",JSONArray(r.curve.map{JSONObject().put("date",it.date.toString()).put("equity",it.equity).put("selected",it.selected).put("buys",it.buys).put("sells",it.sells).put("skipped",it.skipped)
            .put("dailyAccountingVersion",1).put("realized",it.realized?:JSONObject.NULL).put("holdingCost",it.holdingCost?:JSONObject.NULL)
            .put("holdingValue",it.holdingValue?:JSONObject.NULL).put("holdingLots",it.holdingLots?:JSONObject.NULL).put("cash",it.cash?:JSONObject.NULL)
            .put("dividendAccrued",it.dividendAccrued?:JSONObject.NULL).put("dayProfit",it.dayProfit?:JSONObject.NULL).put("staleLots",it.staleLots?:JSONObject.NULL).put("rebateAccrued",it.rebateAccrued?:JSONObject.NULL).put("rebateChange",it.rebateChange?:JSONObject.NULL)}))
        .put("skipped",JSONArray(r.skipped.map{JSONObject().put("date",it.date.toString()).put("code",it.code).put("reason",it.reason)}))
        .put("trades",JSONArray(r.trades.map{JSONObject().put("date",it.date.toString()).put("time",it.time).put("timeKind",it.timeKind).put("signalDate",it.signalDate.toString()).put("dataDate",it.dataDate.toString()).put("lotId",it.lotId).put("target",it.target).put("code",it.code).put("name",it.name).put("radar",it.radar).put("side",it.side).put("shares",it.shares).put("price",it.price).put("turnover",it.price*it.shares).put("rebateMonth",it.date.toString().take(7)).put("fee",it.fee).put("tax",it.tax).put("pnl",it.pnl).put("reason",it.reason)}))
}
