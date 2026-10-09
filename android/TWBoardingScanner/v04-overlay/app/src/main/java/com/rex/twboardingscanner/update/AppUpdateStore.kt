package com.rex.twboardingscanner.update

import android.content.Context
import org.json.JSONObject
import java.io.File

class AppUpdateStore(c:Context){
    companion object {private val lock=Any();val busy=setOf("QUEUED","CHECKING","DOWNLOADING","VERIFYING")}
    private val prefs=c.getSharedPreferences("app_update",0)
    val directory=File(c.filesDir,"app-updates")
    fun snapshot():JSONObject=synchronized(lock){runCatching{JSONObject(prefs.getString("state","{}")!!)}.getOrDefault(JSONObject())}
    fun spec():AppUpdateSpec?=snapshot().optJSONObject("spec")?.let{runCatching{AppUpdateSource.parse(it.toString())}.getOrNull()}
    private fun write(o:JSONObject){check(prefs.edit().putString("state",o.toString()).commit())}
    fun begin():String=synchronized(lock){val id=java.util.UUID.randomUUID().toString();write(snapshot().put("id",id).put("status","QUEUED").put("message","等待網路，準備檢查更新…"));id}
    fun current(id:String)=snapshot().let{it.optString("id")==id&&it.optString("status") in busy}
    fun setSpec(id:String,s:AppUpdateSpec)=synchronized(lock){if(current(id)){val old=spec();write(snapshot().put("spec",s.json()).put("bytes",if(old?.sha256==s.sha256)File(directory,"${s.key}.part").length() else 0))}}
    fun status(id:String,state:String,message:String,bytes:Long?=null)=synchronized(lock){if(current(id)){val o=snapshot().put("status",state).put("message",message);if(bytes!=null)o.put("bytes",bytes);write(o)}}
    fun cancel()=synchronized(lock){write(snapshot().put("id","").put("status","PAUSED").put("message","已暫停，已下載部分保留；按重試可接續"))}
    fun apk(s:AppUpdateSpec)=File(directory,"${s.key}.apk")
}
