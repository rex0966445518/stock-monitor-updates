package com.rex.twboardingscanner.data

import android.content.Context
import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.backtest.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class StockPolicyStore(context:Context){
    private val c=context.applicationContext
    private val prefs=c.getSharedPreferences("stock-entry-policy",0)
    companion object{private val lock=Any();private val fileLock=Any()}
    fun read():StockPolicy=StockPolicy.read(prefs.getString("policy",null)?.let{JSONObject(it)})
    fun change(transform:(StockPolicy)->StockPolicy):Boolean=synchronized(lock){
        val prior=read();val next=transform(prior);next.validate();if(next==prior)return@synchronized false
        check(prefs.edit().putString("policy",next.json().toString()).commit()){"名單儲存失敗"}
        // Freeze old reports. A changed policy must not be mixed into a running comparison.
        val bt=BacktestStore(c);bt.update(bt.active(),"禁股／限價已變更；請以新設定重新回測，舊日誌保留","CANCELED")
        androidx.work.WorkManager.getInstance(c).cancelUniqueWork("historical-backtest")
        val robot=BtRobotStore(c);if(robot.active().isNotEmpty())BtRobotWorker.pause(c,robot.active())
        true
    }
    fun ban(code:String,name:String){change{it.copy(banned=it.banned+code)};prefs.edit().putString("name-$code",name).apply()}
    fun name(code:String)=prefs.getString("name-$code",null)?:StockDirectory(c).cached().firstOrNull{it.code==code}?.name?:"名稱待載入"
    fun allow(code:String,scopes:Set<StockScope>)=change{p->p.copy(overrides=StockScope.entries.mapNotNull{s->
        (if(s in scopes)p.overrides[s].orEmpty()+code else p.overrides[s].orEmpty()-code).takeIf{it.isNotEmpty()}?.let{s to it}
    }.toMap())}
    private fun file(scope:StockScope)=File(c.filesDir,"limited-${scope.name}.json")
    fun candidates(scope:StockScope):JSONObject=synchronized(fileLock){runCatching{JSONObject(file(scope).readText())}.getOrDefault(JSONObject().put("rows",JSONArray()))}
    fun candidates(scope:StockScope,rows:JSONArray,source:String,policy:StockPolicy)=synchronized(fileLock){
        if(read()!=policy)return@synchronized
        val data=JSONObject().put("source",source).put("savedAt",System.currentTimeMillis()).put("policy",policy.json()).put("rows",rows)
        val target=file(scope);val temp=File(target.path+".tmp")
        temp.outputStream().use{it.write(data.toString().toByteArray());it.fd.sync()};check(temp.renameTo(target))
    }
}
