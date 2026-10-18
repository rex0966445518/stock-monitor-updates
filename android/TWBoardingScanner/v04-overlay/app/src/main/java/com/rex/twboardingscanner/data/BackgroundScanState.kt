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
        val pending:Int, val started:Long, val folderId:String,
        val owner:String="", val heartbeatAt:Long=0L, val lastAdvanceAt:Long=0L
    ) {
        val active get()=state in setOf("QUEUED","RUNNING","RETRY")
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
            p.getString("folder","").orEmpty(),
            p.getString("owner","").orEmpty(),p.getLong("heartbeatAt",0L),
            p.getLong("lastAdvanceAt",0L)
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
            .putString("owner","").putLong("heartbeatAt",0L).putLong("lastAdvanceAt",0L)
            .putLong("started",System.currentTimeMillis()).commit()){"無法保存掃描進度"}
        return id
    }

    fun settings(c:Context,id:String):JSONObject {
        require(Regex("[a-f0-9-]{36}").matches(id)){"無效的背景掃描 ID"}
        return JSONObject(File(c.applicationContext.filesDir,"main-market-scan-"+id+".json").readText())
    }

    fun current(c:Context,id:String)=read(c).let{it.id==id&&it.active}

    /** Only one execution engine may own a scan at a time. A dead process loses
     * its heartbeat, permitting the scheduled fallback to take over. */
    @Synchronized fun claim(c:Context,id:String,owner:String,staleMs:Long=45_000L,
                            now:Long=System.currentTimeMillis()):Boolean {
        require(owner.isNotBlank())
        val old=read(c)
        if(old.id!=id||!old.active)return false
        if(old.owner.isNotBlank()&&old.owner!=owner&&now-old.heartbeatAt<staleMs)return false
        return prefs(c).edit().putString("owner",owner)
            .putLong("heartbeatAt",now).commit()
    }

    fun owns(c:Context,id:String,owner:String):Boolean=read(c).let{it.id==id&&it.active&&it.owner==owner}

    /** A heartbeat proves that the engine is awake, independent of network
     * progress (where one HTTP request can take many seconds). */
    @Synchronized fun pulse(c:Context,id:String,owner:String,now:Long=System.currentTimeMillis()){
        if(!owns(c,id,owner))return
        prefs(c).edit().putLong("heartbeatAt",now).apply()
    }

    fun freshService(c:Context,id:String,now:Long=System.currentTimeMillis()):Boolean =
        read(c).let{it.id==id&&it.active&&it.owner.startsWith("service:")&&
            it.heartbeatAt>0&&now>=it.heartbeatAt&&now-it.heartbeatAt<45_000L}


    @Synchronized fun report(c:Context,id:String,message:String,
                             done:Int?=null,total:Int?=null,analyzed:Int?=null,
                             excluded:Int?=null,insufficient:Int?=null,failed:Int?=null,
                             pending:Int?=null,state:String="RUNNING",owner:String?=null) {
        val old=read(c)
        if(old.id!=id||!old.active||(owner!=null&&old.owner!=owner))return
        val edit=prefs(c).edit().putString("state",state).putString("message",message)
        if(owner!=null)edit.putLong("heartbeatAt",System.currentTimeMillis())
        if(done!=null&&done>old.done)edit.putLong("lastAdvanceAt",System.currentTimeMillis())
        done?.let{edit.putInt("done",it)}
        total?.let{edit.putInt("total",it)}
        analyzed?.let{edit.putInt("analyzed",it)}
        excluded?.let{edit.putInt("excluded",it)}
        insufficient?.let{edit.putInt("insufficient",it)}
        failed?.let{edit.putInt("failed",it)}
        pending?.let{edit.putInt("pending",it)}
        edit.apply()
    }

    @Synchronized fun complete(c:Context,id:String,folder:String,message:String,owner:String?=null) {
        if(!current(c,id)||(owner!=null&&!owns(c,id,owner)))return
        prefs(c).edit().putString("state","DONE").putString("message",message)
            .putString("folder",folder).commit()
    }

    @Synchronized fun failure(c:Context,id:String,message:String,owner:String?=null) {
        if(!current(c,id)||(owner!=null&&!owns(c,id,owner)))return
        prefs(c).edit().putString("state","ERROR").putString("message",message).commit()
    }

    @Synchronized fun cancel(c:Context,id:String) {
        if(!current(c,id))return
        prefs(c).edit().putString("state","CANCELED")
            .putString("message","已停止背景掃描，先前完成的掃描資料夾不會刪除").putString("owner","").commit()
    }
}