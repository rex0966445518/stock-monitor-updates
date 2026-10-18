package com.rex.twboardingscanner.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The main market scan belongs to WorkManager, not a visible Activity.
 * A snapshot of the selected rules is persisted before work is enqueued;
 * UI re-creation never changes the selection of a running batch.
 */
object BackgroundScanState {
    const val WORK = "main-market-scan-background-v1"
    private const val PREFS = "main-market-scan-state-v1"
    private val running = setOf("QUEUED", "RUNNING", "RETRY")

    data class Status(
        val id:String, val state:String, val message:String,
        val done:Int, val total:Int, val analyzed:Int,
        val excluded:Int, val insufficient:Int, val failed:Int,
        val pending:Int, val started:Long, val folderId:String
    ) {
        val active get()=state in running
        val percent get()=if(total>0) (done.toLong()*100/total).toInt().coerceIn(0,100) else 0
    }

    private fun prefs(c:Context)=c.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)

    fun read(c:Context):Status {
        val p=prefs(c)
        return Status(
            p.getString("id","").orEmpty(),p.getString("state","IDLE").orEmpty(),
            p.getString("message","").orEmpty(),p.getInt("done",0),p.getInt("total",0),
            p.getInt("analyzed",0),p.getInt("excluded",0),p.getInt("insufficient",0),
            p.getInt("failed",0),p.getInt("pending",0),p.getLong("started",0),
            p.getString("folder","").orEmpty()
        )
    }

    @Synchronized fun begin(c:Context,settings:JSONObject):String {
        check(!read(c).active){"全市場掃描尚未完成，請先停止或等待完成"}
        val id=UUID.randomUUID().toString()
        val target=File(c.applicationContext.filesDir,"main-market-scan-"+id+".json")
        val temp=File(target.path+".tmp")
        temp.writeText(settings.toString())
        check(temp.renameTo(target)){"無法保存背景掃描條件"}
        check(prefs(c).edit().putString("id",id).putString("state","QUEUED")
            .putString("message","正在排入背景掃描").putInt("done",0).putInt("total",0)
            .putInt("analyzed",0).putInt("excluded",0).putInt("insufficient",0)
            .putInt("failed",0).putInt("pending",0).putString("folder","")
            .putLong("started",System.currentTimeMillis()).commit()){"無法保存掃描進度"}
        return id
    }

    fun settings(c:Context,id:String):JSONObject {
        require(Regex("[a-f0-9-]{36}").matches(id)){"無效的背景掃描 ID"}
        return JSONObject(File(c.applicationContext.filesDir,"main-market-scan-"+id+".json").readText())
    }

    fun current(c:Context,id:String)=read(c).let{it.id==id&&it.active}

    @Synchronized fun report(c:Context,id:String,message:String,
                             done:Int?=null,total:Int?=null,analyzed:Int?=null,
                             excluded:Int?=null,insufficient:Int?=null,failed:Int?=null,
                             pending:Int?=null,state:String="RUNNING") {
        val old=read(c)
        if(old.id!=id||!old.active)return
        val edit=prefs(c).edit().putString("state",state).putString("message",message)
        done?.let{edit.putInt("done",it)}
        total?.let{edit.putInt("total",it)}
        analyzed?.let{edit.putInt("analyzed",it)}
        excluded?.let{edit.putInt("excluded",it)}
        insufficient?.let{edit.putInt("insufficient",it)}
        failed?.let{edit.putInt("failed",it)}
        pending?.let{edit.putInt("pending",it)}
        edit.apply()
    }

    @Synchronized fun complete(c:Context,id:String,folder:String,message:String) {
        if(!current(c,id))return
        prefs(c).edit().putString("state","DONE").putString("message",message)
            .putString("folder",folder).commit()
    }

    @Synchronized fun failure(c:Context,id:String,message:String) {
        if(!current(c,id))return
        prefs(c).edit().putString("state","ERROR").putString("message",message).commit()
    }

    @Synchronized fun cancel(c:Context,id:String) {
        if(!current(c,id))return
        prefs(c).edit().putString("state","CANCELED")
            .putString("message","已停止背景掃描，先前完成的掃描資料夾不會刪除").commit()
    }
}