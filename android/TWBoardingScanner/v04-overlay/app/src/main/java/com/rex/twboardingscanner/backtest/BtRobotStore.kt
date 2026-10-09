package com.rex.twboardingscanner.backtest

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.*
import java.math.BigInteger
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Transactions commit a completed trial, its best score and search cursor together. */
class BtRobotStore(private val c:Context){
    private class Db(c:Context):SQLiteOpenHelper(c,"backtest-robot.db",null,1){
        override fun onCreate(db:SQLiteDatabase){
            db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY, created INTEGER NOT NULL, json TEXT NOT NULL)")
            db.execSQL("CREATE TABLE trials(n INTEGER PRIMARY KEY AUTOINCREMENT, session TEXT NOT NULL, key TEXT NOT NULL, summary TEXT NOT NULL, report BLOB NOT NULL, UNIQUE(session,key))")
            db.execSQL("CREATE INDEX trial_session ON trials(session,n)")
        }
        override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int){error("不支援的機器人資料版本")}
    }
    companion object { private val lock=Any();private var helper:Db?=null
        private fun database(c:Context)=synchronized(lock){(helper?:Db(c.applicationContext).also{helper=it}).writableDatabase}
    }
    private val db get()=database(c)
    private val prefs=c.getSharedPreferences("backtest_robot",0)
    fun active()=prefs.getString("active","").orEmpty()
    fun dataset(id:String):File {require(id.matches(Regex("[A-Za-z0-9-]+")));return File(c.filesDir,"robot-data/$id.bin.gz")}
    fun session(id:String=active()):JSONObject?=synchronized(lock){db.rawQuery("SELECT json FROM sessions WHERE id=?",arrayOf(id)).use{if(it.moveToFirst())JSONObject(it.getString(0))else null}}
    private fun putSession(o:JSONObject){check(db.update("sessions",ContentValues().apply{put("json",o.toString())},"id=?",arrayOf(o.getString("id")))==1)}
    fun sessions():List<JSONObject> = synchronized(lock){val out=mutableListOf<JSONObject>();db.rawQuery("SELECT json FROM sessions ORDER BY created DESC",null).use{while(it.moveToNext())out+=JSONObject(it.getString(0))};out}
    fun create(s:BtSettings):String=synchronized(lock){
        BtRobotSpace.validate(s)
        session()?.let{if(it.optString("state")=="RUNNING")putSession(it.put("state","PAUSED").put("token","").put("message","建立新測試；原進度已保存"))}
        val id=UUID.randomUUID().toString()
        val o=JSONObject().put("id",id).put("created",System.currentTimeMillis()).put("state","PAUSED").put("token","")
            .put("settings",BtSettingsCodec.encode(s)).put("tested",0).put("bestKey","").put("bestSeq",0).put("ack",0)
            .put("seed",BigInteger(80,java.security.SecureRandom()).toString()).put("cursor","0").put("message","按開始測試啟動")
            .put("appVersion",c.packageManager.getPackageInfo(c.packageName,0).versionName).put("searchVersion",1)
        check(db.insertOrThrow("sessions",null,ContentValues().apply{put("id",id);put("created",o.getLong("created"));put("json",o.toString())})>0)
        check(prefs.edit().putString("active",id).commit());id
    }
    fun activate(id:String)=synchronized(lock){require(session(id)!=null);check(prefs.edit().putString("active",id).commit())}
    fun resume(id:String):String=synchronized(lock){
        val o=session(id)?:error("找不到測試紀錄")
        require(o.getString("state")!="DONE"){"此測試已完成"}
        BtRobotSpace.validate(BtSettingsCodec.decode(o.getJSONObject("settings")))
        val token=UUID.randomUUID().toString();putSession(o.put("state","RUNNING").put("token",token).put("message","排入測試工作…"));token
    }
    fun current(id:String,token:String)=synchronized(lock){session(id)?.let{it.optString("token")==token&&token.isNotEmpty()&&it.optString("state")=="RUNNING"}?:false}
    fun pause(id:String)=synchronized(lock){session(id)?.let{if(it.optString("state")!="DONE")putSession(it.put("state","PAUSED").put("token","").put("message","已暫停；完成組合及歷史資料保留"))}}
    fun status(id:String,token:String,message:String,state:String="RUNNING")=synchronized(lock){
        if(current(id,token))putSession(session(id)!!.put("message",message).put("state",state))
    }
    fun coverage(id:String,token:String,loaded:Int,requested:Int,digest:String)=synchronized(lock){
        if(current(id,token))putSession(session(id)!!.put("loaded",loaded).put("requested",requested).put("datasetSha256",digest))
    }
    fun seen(id:String,key:String)=synchronized(lock){db.rawQuery("SELECT 1 FROM trials WHERE session=? AND key=? LIMIT 1",arrayOf(id,key)).use{it.moveToFirst()}}
    fun next(id:String):BtRobotSpace.Candidate?=synchronized(lock){
        val s=session(id)?:error("找不到測試紀錄")
        BtRobotSpace.next(BtRobotSpace.key(BtSettingsCodec.decode(s.getJSONObject("settings")).rules),s.optString("bestKey").ifBlank{null},BigInteger(s.getString("cursor")),BigInteger(s.getString("seed"))){seen(id,it)}
    }
    fun remaining(id:String)=BtRobotSpace.total-BigInteger.valueOf(session(id)?.optLong("tested")?:0L)
    fun commit(id:String,token:String,candidate:BtRobotSpace.Candidate,result:BtResult,started:Long):Boolean=synchronized(lock){
        if(!current(id,token)||seen(id,candidate.key))return@synchronized false
        BtRobotSpace.validate(result.settings);require(BtRobotSpace.key(result.settings.rules)==candidate.key)
        require(result.run.profit.isFinite())
        val s=session(id)!!;val finished=System.currentTimeMillis();val seq=s.getLong("tested")+1
        val report=JSONObject().put("id","robot-$id-$seq").put("state","DONE").put("appVersion",s.getString("appVersion"))
            .put("startedAt",started).put("finishedAt",finished).put("settings",BtSettingsCodec.encode(result.settings))
            .put("requested",result.requested).put("loaded",result.loaded).put("excluded",org.json.JSONArray(result.excluded))
            .put("pendingChecks",result.pendingChecks).put("note",result.note).put("run",BacktestStore(c).runJson(result.run))
        val summary=JSONObject().put("key",candidate.key).put("seq",seq).put("startedAt",started).put("finishedAt",finished)
            .put("profit",result.run.profit).put("drawdown",result.run.drawdown).put("buys",result.run.buys).put("closed",result.run.closed.size)
            .put("realized",result.run.realized).put("unrealized",result.run.unrealized).put("pendingChecks",result.pendingChecks)
        val bytes=ByteArrayOutputStream().also{buffer->GZIPOutputStream(buffer).use{it.write(report.toString().toByteArray(Charsets.UTF_8))}}.toByteArray()
        val better=s.optString("bestKey").isEmpty()||result.run.profit>s.getDouble("bestProfit")+0.005
        db.beginTransaction()
        try{
            db.insertOrThrow("trials",null,ContentValues().apply{put("session",id);put("key",candidate.key);put("summary",summary.toString());put("report",bytes)})
            if(better)s.put("bestKey",candidate.key).put("bestProfit",result.run.profit).put("bestSeq",seq)
            s.put("tested",seq).put("cursor",candidate.cursor.toString()).put("message","已完成第 $seq 組；持續搜尋更高收益")
            putSession(s);db.setTransactionSuccessful()
        }finally{db.endTransaction()}
        better
    }
    fun acknowledge(id:String,seq:Long)=synchronized(lock){session(id)?.let{putSession(it.put("ack",maxOf(it.optLong("ack"),seq)))}}
    fun trials(id:String,limit:Int=20,offset:Int=0):List<JSONObject> = synchronized(lock){
        require(limit in 1..1000&&offset>=0);val out=mutableListOf<JSONObject>()
        db.rawQuery("SELECT summary FROM trials WHERE session=? ORDER BY n DESC LIMIT ? OFFSET ?",arrayOf(id,limit.toString(),offset.toString())).use{while(it.moveToNext())out+=JSONObject(it.getString(0))};out
    }
    fun report(id:String,key:String):JSONObject?=synchronized(lock){
        db.rawQuery("SELECT report FROM trials WHERE session=? AND key=?",arrayOf(id,key)).use{cursor->
            if(!cursor.moveToFirst())null else GZIPInputStream(ByteArrayInputStream(cursor.getBlob(0))).bufferedReader().use{JSONObject(it.readText())}
        }
    }
}
