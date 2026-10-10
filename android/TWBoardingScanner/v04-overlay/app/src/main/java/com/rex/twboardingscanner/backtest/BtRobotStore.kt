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
    private class Db(c:Context):SQLiteOpenHelper(c,"backtest-robot.db",null,2){
        override fun onCreate(db:SQLiteDatabase){
            db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY, created INTEGER NOT NULL, json TEXT NOT NULL)")
            db.execSQL("CREATE TABLE trials(n INTEGER PRIMARY KEY AUTOINCREMENT, session TEXT NOT NULL, key TEXT NOT NULL, summary TEXT NOT NULL, report BLOB NOT NULL, cleared INTEGER NOT NULL DEFAULT 0, UNIQUE(session,key))")
            db.execSQL("CREATE INDEX trial_session ON trials(session,n)")
        }
        override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int){if(old<2)db.execSQL("ALTER TABLE trials ADD COLUMN cleared INTEGER NOT NULL DEFAULT 0")}
    }
    companion object { private val lock=Any();private var helper:Db?=null
        private fun database(c:Context)=synchronized(lock){(helper?:Db(c.applicationContext).also{helper=it}).writableDatabase}
    }
    private val db get()=database(c)
    private val prefs=c.getSharedPreferences("backtest_robot",0)
    fun conditionFile(id:String)=File(dataset(id).parentFile,"$id.conditions.gz")
    fun active()=prefs.getString("active","").orEmpty()
    fun dataset(id:String):File {require(id.matches(Regex("[A-Za-z0-9-]+")));return File(c.filesDir,"robot-data/$id.bin.gz")}
    fun session(id:String=active()):JSONObject?=synchronized(lock){db.rawQuery("SELECT json FROM sessions WHERE id=?",arrayOf(id)).use{if(it.moveToFirst())JSONObject(it.getString(0))else null}}
    private fun putSession(o:JSONObject){check(db.update("sessions",ContentValues().apply{put("json",o.toString())},"id=?",arrayOf(o.getString("id")))==1)}
    fun sessions():List<JSONObject> = synchronized(lock){val out=mutableListOf<JSONObject>();db.rawQuery("SELECT json FROM sessions ORDER BY created DESC",null).use{while(it.moveToNext())out+=JSONObject(it.getString(0))};out}
    fun create(s:BtSettings,required:String="0",forbidden:String="0"):String=synchronized(lock){
        BtRobotSpace.validate(s);BtRobotSpace.validateConstraints(required,forbidden)
        session()?.let{if(it.optString("state") in setOf("RUNNING","RETRY"))putSession(it.put("state","PAUSED").put("token","").put("message","建立新測試；原進度已保存"))}
        val id=UUID.randomUUID().toString()
        val o=JSONObject().put("id",id).put("created",System.currentTimeMillis()).put("state","PAUSED").put("token","")
            .put("settings",BtSettingsCodec.encode(s)).put("tested",0).put("bestKey","").put("bestSeq",0).put("ack",0)
            .put("seed",BigInteger(80,java.security.SecureRandom()).toString()).put("cursor","0").put("message","按開始測試啟動")
            .put("appVersion",c.packageManager.getPackageInfo(c.packageName,0).versionName).put("searchVersion",if(BtRobotSpace.required(forbidden).signum()==0)1 else 2).put("required",required).put("forbidden",forbidden).put("engineVersion",BtRobotCheckpoint.ENGINE).put("cleared",0)
        check(db.insertOrThrow("sessions",null,ContentValues().apply{put("id",id);put("created",o.getLong("created"));put("json",o.toString())})>0)
        check(prefs.edit().putString("active",id).commit());id
    }
    fun activate(id:String)=synchronized(lock){require(session(id)!=null);check(prefs.edit().putString("active",id).commit())}
    fun resume(id:String):String=synchronized(lock){
        val o=session(id)?:error("找不到測試紀錄")
        require(o.getString("state")!="DONE"){"此測試已完成"}
        BtRobotSpace.validate(BtSettingsCodec.decode(o.getJSONObject("settings")))
        BtRobotSpace.validateConstraints(o.optString("required","0"),o.optString("forbidden","0"))
        require(o.optInt("searchVersion",1) in 1..2){"搜尋版本不相容"}
        val token=UUID.randomUUID().toString();putSession(o.put("state","RUNNING").put("token",token).put("message","排入測試工作…").put("updatedAt",System.currentTimeMillis()));token
    }
    fun current(id:String,token:String)=synchronized(lock){session(id)?.let{it.optString("token")==token&&token.isNotEmpty()&&it.optString("state") in setOf("RUNNING","RETRY")}?:false}
    fun pause(id:String)=synchronized(lock){session(id)?.let{if(it.optString("state")!="DONE")putSession(it.put("state","PAUSED").put("token","").put("message","已暫停；完成組合及歷史資料保留"))}}
    fun status(id:String,token:String,message:String,state:String="RUNNING")=synchronized(lock){
        if(current(id,token))putSession(session(id)!!.put("message",message).put("state",state).put("updatedAt",System.currentTimeMillis()))
    }
    fun coverage(id:String,token:String,loaded:Int,requested:Int,digest:String)=synchronized(lock){
        if(current(id,token))putSession(session(id)!!.put("loaded",loaded).put("requested",requested).put("datasetSha256",digest))
    }
    fun seen(id:String,key:String)=synchronized(lock){db.rawQuery("SELECT 1 FROM trials WHERE session=? AND key=? LIMIT 1",arrayOf(id,key)).use{it.moveToFirst()}}
    fun next(id:String):BtRobotSpace.Candidate?=synchronized(lock){
        val s=session(id)?:error("找不到測試紀錄")
        BtRobotSpace.next(BtRobotSpace.key(BtSettingsCodec.decode(s.getJSONObject("settings")).rules),s.optString("bestKey").ifBlank{null},BigInteger(s.getString("cursor")),BigInteger(s.getString("seed")),s.optString("required","0"),s.optString("forbidden","0")){seen(id,it)}
    }
    fun remaining(id:String):BigInteger {val s=session(id);return BtRobotSpace.total(s?.optString("required","0")?:"0",s?.optString("forbidden","0")?:"0")-BigInteger.valueOf(s?.optLong("tested")?:0L)}
    fun retained(id:String)=synchronized(lock){db.rawQuery("SELECT COUNT(*) FROM trials WHERE session=? AND cleared=0",arrayOf(id)).use{it.moveToFirst();it.getLong(0)}}
    fun clearZero(id:String):Int=synchronized(lock){
        val s=session(id)?:return@synchronized 0;var removed=0
        db.beginTransaction()
        try{
            var after=0L
            while(true){
                // Read each bounded page before changing it: Android may refill a CursorWindow by
                // rerunning its query, which must not skip rows removed from the cleared=0 filter.
                val page=mutableListOf<Triple<Long,String,Double>>()
                db.rawQuery("SELECT n,key,summary FROM trials WHERE session=? AND cleared=0 AND n>? ORDER BY n LIMIT 500",arrayOf(id,after.toString())).use{rows->while(rows.moveToNext())page+=Triple(rows.getLong(0),rows.getString(1),JSONObject(rows.getString(2)).getDouble("profit"))}
                if(page.isEmpty())break
                after=page.last().first
                page.filter{kotlin.math.abs(it.third)<0.005}.forEach{row->
                    db.update("trials",ContentValues().apply{put("cleared",1);put("report",ByteArray(0))},"session=? AND key=?",arrayOf(id,row.second));removed++
                }
            }
            s.put("cleared",s.optLong("cleared")+removed)
            // The tested ledger remains intact, so cleaned keys can never be scheduled again.
            val best=s.optString("bestKey")
            if(best.isNotEmpty()&&report(id,best)==null){
                s.put("bestKey","").put("bestSeq",0);s.remove("bestProfit")
                db.rawQuery("SELECT summary FROM trials WHERE session=? AND cleared=0",arrayOf(id)).use{rows->while(rows.moveToNext()){
                    val r=JSONObject(rows.getString(0));if(!s.has("bestProfit")||r.getDouble("profit")>s.getDouble("bestProfit"))s.put("bestKey",r.getString("key")).put("bestProfit",r.getDouble("profit")).put("bestSeq",r.getLong("seq"))
                }}
            }
            putSession(s);db.setTransactionSuccessful()
        }finally{db.endTransaction()};removed
    }
    internal fun <T> locked(action:(SQLiteDatabase)->T):T=synchronized(lock){action(db)}
    fun commit(id:String,token:String,candidate:BtRobotSpace.Candidate,result:BtResult,started:Long):Boolean=synchronized(lock){
        if(!current(id,token)||seen(id,candidate.key))return@synchronized false
        BtRobotSpace.validate(result.settings);require(BtRobotSpace.key(result.settings.rules)==candidate.key)
        require(result.run.profit.isFinite())
        val s=session(id)!!
        require(result.settings==BtSettingsCodec.decode(s.getJSONObject("settings")).copy(rules=BtRobotSpace.rules(candidate.key))){"測試條件與批次不一致"}
        if(com.rex.twboardingscanner.data.StockPolicyStore(c).read()!=result.settings.stockPolicy)return@synchronized false
        require(BtRobotSpace.includes(candidate.key,s.optString("required","0"),s.optString("forbidden","0")));val finished=System.currentTimeMillis();val seq=s.getLong("tested")+1
        val report=JSONObject().put("id",UUID.nameUUIDFromBytes("$id:${candidate.key}".toByteArray(Charsets.UTF_8)).toString())
            .put("journalVersion",1).put("strategyVersion",result.settings.strategyVersion).put("robotSession",id).put("robotSequence",seq)
            .put("state","DONE").put("appVersion",s.getString("appVersion"))
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
            s.put("updatedAt",finished).put("tested",seq).put("cursor",candidate.cursor.toString()).put("message","已完成第 $seq 組；持續搜尋更高收益")
            putSession(s);db.setTransactionSuccessful()
        }finally{db.endTransaction()}
        com.rex.twboardingscanner.data.StockPolicyStore(c).candidates(com.rex.twboardingscanner.domain.StockScope.ROBOT,BacktestStore(c).limitedJson(result.run),"機器人 ${id.take(8)} · 第 $seq 組",result.settings.stockPolicy)
        better
    }
    fun acknowledge(id:String,seq:Long)=synchronized(lock){session(id)?.let{putSession(it.put("ack",maxOf(it.optLong("ack"),seq)))}}
    fun trials(id:String,limit:Int=20,offset:Int=0):List<JSONObject> = synchronized(lock){
        require(limit in 1..1000&&offset>=0);val out=mutableListOf<JSONObject>()
        db.rawQuery("SELECT summary FROM trials WHERE session=? AND cleared=0 ORDER BY n DESC LIMIT ? OFFSET ?",arrayOf(id,limit.toString(),offset.toString())).use{while(it.moveToNext())out+=JSONObject(it.getString(0))};out
    }
    /** Keyset paging keeps stock search responsive without loading all compressed reports. */
    fun searchPage(before:Long=Long.MAX_VALUE):List<JSONObject> = synchronized(lock){
        val out=mutableListOf<JSONObject>()
        db.rawQuery("SELECT n,session,key FROM trials WHERE cleared=0 AND n<? ORDER BY n DESC LIMIT 30",arrayOf(before.toString())).use{r->while(r.moveToNext())out+=JSONObject().put("n",r.getLong(0)).put("session",r.getString(1)).put("key",r.getString(2))};out
    }
    fun report(id:String,key:String):JSONObject?=synchronized(lock){
        db.rawQuery("SELECT report FROM trials WHERE session=? AND key=? AND cleared=0",arrayOf(id,key)).use{cursor->
            if(!cursor.moveToFirst())null else GZIPInputStream(ByteArrayInputStream(cursor.getBlob(0))).bufferedReader().use{JSONObject(it.readText())}
        }
    }
}
