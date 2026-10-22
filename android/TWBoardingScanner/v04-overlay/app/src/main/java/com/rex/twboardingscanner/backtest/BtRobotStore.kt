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
    internal class Db(c:Context,name:String="backtest-robot.db"):SQLiteOpenHelper(c,name,null,5){
        override fun onCreate(db:SQLiteDatabase){
            db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY, created INTEGER NOT NULL, json TEXT NOT NULL)")
            db.execSQL("CREATE TABLE trials(n INTEGER PRIMARY KEY AUTOINCREMENT, session TEXT NOT NULL, key TEXT NOT NULL, summary TEXT NOT NULL, report BLOB NOT NULL, cleared INTEGER NOT NULL DEFAULT 0, radars INTEGER NOT NULL DEFAULT 0, net_profit REAL NOT NULL DEFAULT 0, summary_valid INTEGER NOT NULL DEFAULT 1, favorite INTEGER NOT NULL DEFAULT 0, UNIQUE(session,key))")
            db.execSQL("CREATE INDEX trial_session ON trials(session,n)")
            db.execSQL("CREATE INDEX trial_rank ON trials(cleared,net_profit DESC,n DESC)")
            db.execSQL("CREATE TABLE robot_meta(name TEXT PRIMARY KEY,value INTEGER NOT NULL)")
            db.execSQL("INSERT INTO robot_meta(name,value) VALUES('history_index',1)")
        }
        override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int){
            if(old<2)db.execSQL("ALTER TABLE trials ADD COLUMN cleared INTEGER NOT NULL DEFAULT 0")
            if(old<3){
                db.execSQL("ALTER TABLE trials ADD COLUMN radars INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trials ADD COLUMN net_profit REAL NOT NULL DEFAULT 0")
            }
            if(old<4)db.execSQL("ALTER TABLE trials ADD COLUMN summary_valid INTEGER NOT NULL DEFAULT 1")
            if(old<5)db.execSQL("ALTER TABLE trials ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE TABLE IF NOT EXISTS robot_meta(name TEXT PRIMARY KEY,value INTEGER NOT NULL)")
            if(old<4)db.execSQL("INSERT OR REPLACE INTO robot_meta(name,value) VALUES('history_index',?)",arrayOf(0))
        }
    }
    companion object { private val lock=Any();private val historyIndexLock=Any();private var helper:Db?=null
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
    fun create(s:BtSettings,required:String="0",forbidden:String="0",sourceJournalId:String?=null,sweep:BtRobotSweep?=null,singleOption:Boolean=false,includeBaseline:Boolean=true):String=synchronized(lock){
        BtRobotSpace.validate(s);BtRobotSpace.validateConstraints(required,forbidden)
        require(sweep==null||required=="0"&&forbidden=="0"){"參數網格固定本批次 ABCDEF，不可同時調整必選／排除條件"}
        require(sourceJournalId==null||sourceJournalId.matches(Regex("[A-Za-z0-9_-]{1,100}"))){"日誌編號不合法"}
        session()?.let{if(it.optString("state") in setOf("RUNNING","RETRY"))putSession(it.put("state","PAUSED").put("token","").put("message","建立新測試；原進度已保存"))}
        val id=UUID.randomUUID().toString()
        val o=JSONObject().put("id",id).put("created",System.currentTimeMillis()).put("state","PAUSED").put("token","")
            .put("settings",BtSettingsCodec.encode(s)).put("sourceJournalId",sourceJournalId?:JSONObject.NULL)
            .put("sweep",sweep?.json()?:JSONObject.NULL)
            .put("flowRevision",com.rex.twboardingscanner.data.TradeSideVolumeStore(c).revision())
            .put("dChipRevision",com.rex.twboardingscanner.data.DChipStore(c).revision()).put("tested",0).put("bestKey","").put("bestSeq",0).put("ack",0)
            .put("seed",BigInteger(80,java.security.SecureRandom()).toString()).put("cursor","0").put("message","按開始測試啟動").put("owner","").put("heartbeatAt",0L).put("phaseSeq",0L).put("phaseDone",0)
            .put("analysisVersion",2).put("appVersion",c.packageManager.getPackageInfo(c.packageName,0).versionName).put("searchVersion",if(singleOption&&sweep==null)if(includeBaseline)10 else 9 else 8).put("required",required).put("forbidden",forbidden).put("engineVersion",BtRobotCheckpoint.ENGINE).put("cleared",0)
        check(db.insertOrThrow("sessions",null,ContentValues().apply{put("id",id);put("created",o.getLong("created"));put("json",o.toString())})>0)
        check(prefs.edit().putString("active",id).commit());id
    }
    fun createSingle(s:BtSettings,required:String="0",forbidden:String="0",sourceJournalId:String?=null,sweep:BtRobotSweep?=null,includeBaseline:Boolean=true)=
        create(s,required,forbidden,sourceJournalId,sweep,singleOption=true,includeBaseline=includeBaseline)
    fun activate(id:String)=synchronized(lock){require(session(id)!=null);check(prefs.edit().putString("active",id).commit())}
    fun resume(id:String):String=synchronized(lock){
        val o=session(id)?:error("找不到測試紀錄")
        require(o.getString("state")!="DONE"){"此測試已完成"}
        BtRobotSpace.validate(BtSettingsCodec.decode(o.getJSONObject("settings")))
        BtRobotSpace.validateConstraints(o.optString("required","0"),o.optString("forbidden","0"))
        require(o.optInt("searchVersion",1) in 1..10){"搜尋版本不相容"}
        if(o.has("flowRevision"))require(o.getString("flowRevision")==com.rex.twboardingscanner.data.TradeSideVolumeStore(c).revision()){
            "盤中內外盤資料已更新；請以新條件建立新批次，原測試紀錄保留"
        }
        if(o.has("dChipRevision"))require(o.getString("dChipRevision")==com.rex.twboardingscanner.data.DChipStore(c).revision()){
            "D 每日籌碼來源已變更；請建立新批次，原測試紀錄保留"
        }
        BtRobotSweep.read(o.optJSONObject("sweep"))?.let{sweep->
            require(o.optString("required","0")=="0"&&o.optString("forbidden","0")=="0"){"網格批次條件不可改動"}
            val tested=o.getLong("tested")
            require(tested in 0..sweep.total.toLong()&&BigInteger(o.getString("cursor"))==BigInteger.valueOf(tested)){"網格斷點數量不一致"}
        }
        if(BtRobotSingleOption.active(o)){
            val base=BtSettingsCodec.decode(o.getJSONObject("settings"))
            val total=BtRobotSingleOption.total(o.optString("required","0"),o.optString("forbidden","0"),base.enabledRadars,BtRobotSingleOption.baselineFirst(o))
            require(BigInteger(o.getString("cursor"))==BigInteger.valueOf(o.getLong("tested"))&&BigInteger.valueOf(o.getLong("tested"))<=total){"逐項測試斷點不一致"}
        }
        val token=UUID.randomUUID().toString();putSession(o.put("state","RUNNING").put("token",token).put("owner","").put("heartbeatAt",0L).put("message","排入測試工作…").put("updatedAt",System.currentTimeMillis()));token
    }
    fun current(id:String,token:String)=synchronized(lock){session(id)?.let{it.optString("token")==token&&token.isNotEmpty()&&it.optString("state") in setOf("RUNNING","RETRY")}?:false}

    @Synchronized fun owns(id:String,token:String,owner:String):Boolean=synchronized(lock){
        current(id,token)&&session(id)?.optString("owner")==owner&&owner.isNotBlank()
    }
    /** Exclusive execution lease for either the foreground service or fallback Worker. */
    fun claim(id:String,token:String,owner:String,now:Long=System.currentTimeMillis(),staleMs:Long=45000L):Boolean=synchronized(lock){
        if(!current(id,token)||owner.isBlank())return@synchronized false
        val s=session(id)!!
        val old=s.optString("owner")
        val age=now-s.optLong("heartbeatAt")
        if(old.isNotBlank()&&old!=owner&&age in 0 until staleMs)return@synchronized false
        putSession(s.put("owner",owner).put("heartbeatAt",now).put("updatedAt",now))
        true
    }
    fun freshService(id:String,token:String,now:Long=System.currentTimeMillis()):Boolean=synchronized(lock){
        val s=session(id)?:return@synchronized false
        current(id,token)&&s.optString("owner").startsWith("service:")&&
            s.optLong("heartbeatAt")>0L&&now-s.optLong("heartbeatAt") in 0L..45000L
    }
    fun heartbeat(id:String,token:String,owner:String,now:Long=System.currentTimeMillis()):Boolean=synchronized(lock){
        if(!owns(id,token,owner))return@synchronized false
        val s=session(id)!!.put("heartbeatAt",now)
        putSession(s)
        true
    }
    fun release(id:String,token:String,owner:String,message:String):Boolean=synchronized(lock){
        if(!owns(id,token,owner))return@synchronized false
        putSession(session(id)!!.put("owner","").put("heartbeatAt",0L)
            .put("state","RETRY").put("message",message).put("updatedAt",System.currentTimeMillis()))
        true
    }
    /** Stage progress is monotonic for an uncommitted trial even after Android restarts. */
    fun stage(id:String,token:String,owner:String,message:String,done:Int,total:Int):Boolean=synchronized(lock){
        if(!owns(id,token,owner))return@synchronized false
        val s=session(id)!!
        val sequence=s.optLong("tested")+1L
        val previous=if(s.optLong("phaseSeq")==sequence)s.optInt("phaseDone")else 0
        val current=done.coerceIn(0,total.coerceAtLeast(0))
        val progress=maxOf(previous,current)
        val label=if(current<previous)
            "恢復已保存的條件分段 $progress/$total 檔（重新載入快取 $current/$total）"
        else message
        putSession(s.put("phaseSeq",sequence).put("phaseDone",progress).put("phaseTotal",total)
            .put("message",label).put("state","RUNNING").put("updatedAt",System.currentTimeMillis()))
        true
    }
    fun pause(id:String)=synchronized(lock){session(id)?.let{if(it.optString("state")!="DONE")putSession(it.put("state","PAUSED").put("token","").put("owner","").put("heartbeatAt",0L).put("message","已暫停；完成組合及歷史資料保留"))}}

    fun status(id:String,token:String,message:String,state:String="RUNNING",owner:String?=null)=synchronized(lock){
        if(current(id,token)&&(owner==null||owns(id,token,owner)))putSession(session(id)!!.put("message",message).put("state",state).put("updatedAt",System.currentTimeMillis()))
    }
    fun coverage(id:String,token:String,loaded:Int,requested:Int,digest:String,owner:String?=null)=synchronized(lock){
        if(current(id,token)&&(owner==null||owns(id,token,owner)))putSession(session(id)!!.put("loaded",loaded).put("requested",requested).put("datasetSha256",digest))
    }
    fun seen(id:String,key:String)=synchronized(lock){db.rawQuery("SELECT 1 FROM trials WHERE session=? AND key=? LIMIT 1",arrayOf(id,key)).use{it.moveToFirst()}}
    fun next(id:String):BtRobotSpace.Candidate?=synchronized(lock){
        val s=session(id)?:error("找不到測試紀錄")
        val sweep=BtRobotSweep.read(s.optJSONObject("sweep"))
        if(sweep!=null)sweep.next(s.getLong("tested"))
        else if(BtRobotSingleOption.active(s)){
            val current=BtSettingsCodec.decode(s.getJSONObject("settings"))
            BtRobotSingleOption.next(s.optString("required","0"),s.optString("forbidden","0"),current.enabledRadars,BigInteger(s.getString("cursor")),BtRobotSingleOption.baselineFirst(s)){seen(id,it)}
        }else {
            val version=s.optInt("searchVersion",1)
            val current=BtSettingsCodec.decode(s.getJSONObject("settings"))
            BtRobotSpace.next(BtRobotSpace.key(current.rules),
                s.optString("bestKey").ifBlank{null},BigInteger(s.getString("cursor")),
                BigInteger(s.getString("seed")),
                s.optString("required","0"),s.optString("forbidden","0"),
                legacy=version<3,
                enabled=if(version<4)defaultActiveRadars() else current.enabledRadars,
                includeD=version>=4,includeCost=version>=5,includeE=version>=6,includeMomentum=version>=7,includeB=version>=8){seen(id,it)}
        }
    }
    fun remaining(id:String):BigInteger {val s=session(id)
        if(BtRobotSingleOption.active(s))return (BtRobotSingleOption.total(s!!.optString("required","0"),s.optString("forbidden","0"),BtSettingsCodec.decode(s.getJSONObject("settings")).enabledRadars,BtRobotSingleOption.baselineFirst(s))-BigInteger.valueOf(s.optLong("tested"))).max(BigInteger.ZERO)
        return (BtRobotSweep.read(s?.optJSONObject("sweep"))?.total?.let{BigInteger.valueOf(it.toLong())}
        ?:BtRobotSpace.total(s?.optString("required","0")?:"0",s?.optString("forbidden","0")?:"0",
            legacy=(s?.optInt("searchVersion",1)?:1)<3,
            enabled=if((s?.optInt("searchVersion",1)?:1)<4)defaultActiveRadars() else
                s?.let{BtSettingsCodec.decode(it.getJSONObject("settings")).enabledRadars}?:defaultActiveRadars(),
            includeD=(s?.optInt("searchVersion",1)?:1)>=4,
             includeCost=(s?.optInt("searchVersion",1)?:1)>=5,includeE=(s?.optInt("searchVersion",1)?:1)>=6,includeMomentum=(s?.optInt("searchVersion",1)?:1)>=7,includeB=(s?.optInt("searchVersion",1)?:1)>=8))-BigInteger.valueOf(s?.optLong("tested")?:0L)}
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
                db.rawQuery("SELECT n,key,summary FROM trials WHERE session=? AND cleared=0 AND favorite=0 AND n>? ORDER BY n LIMIT 500",arrayOf(id,after.toString())).use{rows->while(rows.moveToNext())page+=Triple(rows.getLong(0),rows.getString(1),JSONObject(rows.getString(2)).getDouble("profit"))}
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
    fun commit(id:String,token:String,candidate:BtRobotSpace.Candidate,result:BtResult,started:Long,signalCounts:Map<com.rex.twboardingscanner.domain.RadarType,Int> = emptyMap(),owner:String?=null):Boolean=synchronized(lock){
        if(!current(id,token)||(owner!=null&&!owns(id,token,owner))||seen(id,candidate.key))return@synchronized false
        BtRobotSpace.validate(result.settings)
        require(result.run.profit.isFinite())
        val s=session(id)!!
        val sweep=BtRobotSweep.read(s.optJSONObject("sweep"))
        val base=BtSettingsCodec.decode(s.getJSONObject("settings"))
        if(sweep==null)require(BtRobotSpace.key(result.settings.rules)==candidate.key){"ABCDEF 組合代碼不符"}
        else require(candidate.cursor==BigInteger.valueOf(s.getLong("tested")+1)&&
            sweep.next(s.getLong("tested"))?.key==candidate.key){"參數網格游標／組合不一致"}
        if(BtRobotSingleOption.active(s))require(next(id)==candidate){"逐項測試游標／條件不一致"}
        val expected=if(sweep==null)BtRobotSingleOption.settings(base,candidate.key,BtRobotSingleOption.baseline(s,candidate.key)) else sweep.settings(base,candidate.key)
        require(result.settings==expected){"測試條件與批次不一致"}
        if(com.rex.twboardingscanner.data.StockPolicyStore(c).read()!=result.settings.stockPolicy)return@synchronized false
        if(sweep==null&&!BtRobotSingleOption.baseline(s,candidate.key))require(BtRobotSpace.includes(candidate.key,s.optString("required","0"),s.optString("forbidden","0"),base.enabledRadars))
        val finished=System.currentTimeMillis();val seq=s.getLong("tested")+1
        val feedback=JSONObject();signalCounts.forEach{(type,count)->feedback.put(type.name.take(1),count)}
        val report=JSONObject().put("id",UUID.nameUUIDFromBytes("$id:${candidate.key}".toByteArray(Charsets.UTF_8)).toString())
            .put("journalVersion",1).put("strategyVersion",result.settings.strategyVersion).put("robotSession",id).put("robotSequence",seq)
            .put("state","DONE").put("appVersion",s.getString("appVersion"))
            .put("startedAt",started).put("finishedAt",finished).put("settings",BtSettingsCodec.encode(result.settings))
            .put("requested",result.requested).put("loaded",result.loaded).put("excluded",org.json.JSONArray(result.excluded))
            .put("signalCounts",feedback).put("pendingChecks",result.pendingChecks).put("note",result.note).put("run",BacktestStore(c).runJson(result.run))
        val summary=JSONObject().put("key",candidate.key).put("seq",seq).put("startedAt",started).put("finishedAt",finished)
            .put("mode",if(sweep==null)"ABCDEF" else "GRID").put("signalCounts",feedback)
            .put("maxHoldingStocks",result.settings.maxHoldingStocks?:JSONObject.NULL)
            .put("targetNetPct",result.settings.targetNetPct)
            .put("profit",result.run.profit).put("drawdown",result.run.drawdown).put("buys",result.run.buys).put("closed",result.run.closed.size)
            .put("realized",result.run.realized).put("unrealized",result.run.unrealized).put("pendingChecks",result.pendingChecks)
        if(BtRobotSingleOption.active(s)){
            val option=BtRobotSingleOption.option(s.optString("required","0"),candidate.key,base.enabledRadars)
            summary.put("baseline",result.settings.robotBaseline).put("mode","SINGLE_OPTION").put("testedOption",option?:JSONObject.NULL)
            report.put("baseline",result.settings.robotBaseline).put("testedOption",option?:JSONObject.NULL).put("robotSearchMode","SINGLE_OPTION")
        }
        val bytes=ByteArrayOutputStream().also{buffer->GZIPOutputStream(buffer).use{it.write(report.toString().toByteArray(Charsets.UTF_8))}}.toByteArray()
        val better=s.optString("bestKey").isEmpty()||result.run.profit>s.getDouble("bestProfit")+0.005
        db.beginTransaction()
        try{
            db.insertOrThrow("trials",null,ContentValues().apply{put("session",id);put("key",candidate.key);put("summary",summary.toString());put("report",bytes);put("radars",RobotHistoryCategory.mask(BtSettingsCodec.encode(result.settings),candidate.key,summary));put("net_profit",result.run.profit)})
            if(better)s.put("bestKey",candidate.key).put("bestProfit",result.run.profit).put("bestSeq",seq)
            s.put("updatedAt",finished).put("tested",seq).put("cursor",candidate.cursor.toString()).put("phaseSeq",0L).put("phaseDone",0)
                .put("message",if(BtRobotSingleOption.active(s))"逐項測試已完成第 $seq 組 · ${BtRobotSingleOption.label(s.optString("required","0"),candidate.key,base.enabledRadars)}"
                    else if(sweep==null)"已完成第 $seq 組；持續搜尋更高收益"
                    else "參數範圍第 $seq／${sweep.total} 組完成 · ${sweep.parseKey(candidate.key).label}")
            putSession(s);db.setTransactionSuccessful()
        }finally{db.endTransaction()}
        com.rex.twboardingscanner.data.StockPolicyStore(c).candidates(com.rex.twboardingscanner.domain.StockScope.ROBOT,BacktestStore(c).limitedJson(result.run),"機器人 ${id.take(8)} · 第 $seq 組",result.settings.stockPolicy)
        better
    }
    fun acknowledge(id:String,seq:Long)=synchronized(lock){session(id)?.let{putSession(it.put("ack",maxOf(it.optLong("ack"),seq)))}}
    fun trials(id:String,limit:Int=20,offset:Int=0):List<JSONObject> = synchronized(lock){
        require(limit in 1..1000&&offset>=0);val out=mutableListOf<JSONObject>()
        db.rawQuery("SELECT summary FROM trials WHERE session=? AND cleared=0 ORDER BY n DESC LIMIT ? OFFSET ?",arrayOf(id,limit.toString(),offset.toString())).use{while(it.moveToNext())BtTrialSummary.read(it.getString(0))?.let{row->out+=row}};out
    }
    /** Return real top-profit parameter combinations; an empty/cleared report never becomes best. */
    fun topTrials(id:String,limit:Int=10):List<JSONObject> = synchronized(lock){
        require(limit in 1..100)
        val top=mutableListOf<JSONObject>()
        db.rawQuery("SELECT summary FROM trials WHERE session=? AND cleared=0",arrayOf(id)).use{cursor->
            while(cursor.moveToNext()){
                val item=BtTrialSummary.read(cursor.getString(0))?:continue
                top.add(item)
                top.sortWith(compareByDescending<JSONObject>{it.getDouble("profit")}.thenBy{it.getLong("seq")})
                if(top.size>limit)top.removeAt(top.lastIndex)
            }
        }
        top
    }
    /** Keyset paging keeps stock search responsive without loading all compressed reports. */
    fun searchPage(before:Long=Long.MAX_VALUE):List<JSONObject> = synchronized(lock){
        val out=mutableListOf<JSONObject>()
        db.rawQuery("SELECT n,session,key FROM trials WHERE cleared=0 AND n<? ORDER BY n DESC LIMIT 30",arrayOf(before.toString())).use{r->while(r.moveToNext())out+=JSONObject().put("n",r.getLong(0)).put("session",r.getString(1)).put("key",r.getString(2))};out
    }
    /** All batches, newest first. Page summaries only; never decompress every trade report. */
    fun historyPage(before:Long=Long.MAX_VALUE,limit:Int=20):List<JSONObject> {
        require(limit in 1..100)
        ensureHistoryIndex()
        return synchronized(lock){
        val rows=mutableListOf<JSONObject>()
        db.rawQuery("SELECT t.n,t.session,t.key,t.summary,s.json,t.favorite FROM trials t LEFT JOIN sessions s ON s.id=t.session WHERE t.cleared=0 AND t.summary_valid=1 AND t.n<? ORDER BY t.n DESC LIMIT ?",
            arrayOf(before.toString(),limit.toString())).use{cursor->while(cursor.moveToNext()){
            rows+=JSONObject().put("n",cursor.getLong(0)).put("session",cursor.getString(1)).put("key",cursor.getString(2))
                .put("summary",JSONObject(cursor.getString(3))).put("batch",if(cursor.isNull(4))JSONObject.NULL else runCatching{JSONObject(cursor.getString(4))}.getOrNull()?:JSONObject.NULL).put("favorite",cursor.getInt(5)!=0)
        }}
        rows
    }
    }
    /** Only one index repair runs at a time; worker writes can proceed between bounded pages. */
    private fun ensureHistoryIndex(progress:(Long)->Unit={},cancel:()->Boolean={false})=synchronized(historyIndexLock){
        var after=0L
        while(true){
            check(!cancel()){ "紀錄讀取已取消" }
            val completed=synchronized(lock) page@{
                val database=db
                val ready=database.rawQuery("SELECT value FROM robot_meta WHERE name='history_index'",null).use{it.moveToFirst()&&it.getInt(0)==1}
                if(ready)return@page true
                database.beginTransaction()
                try{
                    val last=RobotHistoryCategory.backfillPage(database,after,200)
                    val done=last==after
                    if(done){
                        database.execSQL("CREATE INDEX IF NOT EXISTS trial_rank ON trials(cleared,net_profit DESC,n DESC)")
                        database.execSQL("INSERT OR REPLACE INTO robot_meta(name,value) VALUES('history_index',1)")
                    }
                    after=last
                    database.setTransactionSuccessful()
                    done
                }finally{database.endTransaction()}
            }
            if(completed)return@synchronized
            progress(after)
        }
    }
    /** Background archive entry point. Progress and cancellation keep legacy upgrades observable. */
    fun prepareHistory(progress:(Long)->Unit={},cancel:()->Boolean={false})=ensureHistoryIndex(progress,cancel)
    fun historyCount(category:com.rex.twboardingscanner.domain.RadarType?=null):Long {
        ensureHistoryIndex()
        return synchronized(lock){
        val filter=if(category==null)"" else " AND (radars & ${RobotHistoryCategory.bit(category)})!=0"
        db.rawQuery("SELECT COUNT(*) FROM trials WHERE cleared=0 AND summary_valid=1$filter",null).use{it.moveToFirst();it.getLong(0)}
    }
    }
    /** Global profit order with a stable tie-breaker; filtering happens before paging and ranking. */
    fun rankedHistory(category:com.rex.twboardingscanner.domain.RadarType?=null,beforeProfit:Double?=null,beforeN:Long=Long.MAX_VALUE,limit:Int=20):List<JSONObject> {
        require(limit in 1..100&& (beforeProfit==null||beforeProfit.isFinite()))
        ensureHistoryIndex()
        return synchronized(lock){
        val categoryFilter=if(category==null)"" else " AND (t.radars & ${RobotHistoryCategory.bit(category)})!=0"
        val cursorFilter=if(beforeProfit==null)"" else " AND (t.net_profit<? OR (t.net_profit=? AND t.n<?))"
        val args=if(beforeProfit==null)arrayOf(limit.toString()) else arrayOf(beforeProfit.toString(),beforeProfit.toString(),beforeN.toString(),limit.toString())
        val rows=mutableListOf<JSONObject>()
        db.rawQuery("SELECT t.n,t.session,t.key,t.summary,s.json,t.radars,t.favorite FROM trials t LEFT JOIN sessions s ON s.id=t.session WHERE t.cleared=0 AND t.summary_valid=1$categoryFilter$cursorFilter ORDER BY t.net_profit DESC,t.n DESC LIMIT ?",args).use{cursor->while(cursor.moveToNext()){
            rows+=JSONObject().put("n",cursor.getLong(0)).put("session",cursor.getString(1)).put("key",cursor.getString(2)).put("summary",JSONObject(cursor.getString(3)))
                .put("batch",if(cursor.isNull(4))JSONObject.NULL else runCatching{JSONObject(cursor.getString(4))}.getOrNull()?:JSONObject.NULL).put("categories",cursor.getInt(5)).put("favorite",cursor.getInt(6)!=0)
        }}
        rows.firstOrNull()?.let{first->
            val profit=first.getJSONObject("summary").getDouble("profit").toString()
            val ahead=db.rawQuery("SELECT COUNT(*) FROM trials t WHERE t.cleared=0 AND t.summary_valid=1$categoryFilter AND (t.net_profit>? OR (t.net_profit=? AND t.n>?))",arrayOf(profit,profit,first.getLong("n").toString())).use{it.moveToFirst();it.getLong(0)}
            rows.forEachIndexed{i,row->row.put("rank",ahead+i+1)}
        }
        rows
    }
    }
    fun setFavorite(id:String,key:String,keep:Boolean):Boolean=synchronized(lock){
        val summary=db.rawQuery("SELECT summary FROM trials WHERE session=? AND key=? AND cleared=0",arrayOf(id,key)).use{
            if(it.moveToFirst())JSONObject(it.getString(0)) else null
        }?:return@synchronized false
        summary.put("favorite",keep)
        db.update("trials",ContentValues().apply{put("favorite",if(keep)1 else 0);put("summary",summary.toString())},"session=? AND key=? AND cleared=0",arrayOf(id,key))==1
    }
    /** Snapshot limits cleanup to records that already existed when the confirmation was shown. */
    fun unstarredSnapshot():Pair<Long,Long> = synchronized(lock){
        db.rawQuery("SELECT COALESCE(MAX(n),0),COUNT(*) FROM trials WHERE cleared=0 AND favorite=0",null).use{it.moveToFirst();it.getLong(0) to it.getLong(1)}
    }
    private fun tombstone(key:String,summary:JSONObject)=JSONObject().put("key",key).put("seq",summary.optLong("seq"))
        .put("deleted",true).put("profit",0.0).put("realized",0.0).put("unrealized",0.0).put("buys",0).put("closed",0).put("drawdown",0.0).apply{
            listOf("maxHoldingStocks","targetNetPct").forEach{if(summary.has(it))put(it,summary.get(it))}
        }
    private fun removeUnstarred(n:Long,key:String,summary:JSONObject):Boolean = db.update("trials",ContentValues().apply{
        put("cleared",2);put("summary",tombstone(key,summary).toString());put("report",ByteArray(0));put("net_profit",0.0)
    },"n=? AND cleared=0 AND favorite=0",arrayOf(n.toString()))==1
    private fun updateDeletedBatch(id:String,count:Long){
        val s=session(id)?:return
        s.put("deleted",s.optLong("deleted")+count)
        val current=s.optString("bestKey")
        val retained=current.isNotBlank()&&db.rawQuery("SELECT 1 FROM trials WHERE session=? AND key=? AND cleared=0",arrayOf(id,current)).use{it.moveToFirst()}
        if(retained){putSession(s);return}
        val best=db.rawQuery("SELECT key,summary FROM trials WHERE session=? AND cleared=0 AND summary_valid=1 ORDER BY net_profit DESC,n ASC LIMIT 1",arrayOf(id)).use{
            if(it.moveToFirst())it.getString(0) to BtTrialSummary.read(it.getString(1)) else null
        }
        s.put("bestKey",best?.first?:"").put("bestSeq",best?.second?.optLong("seq")?:0L);s.remove("bestProfit")
        best?.second?.let{s.put("bestProfit",it.getDouble("profit"))}
        putSession(s)
    }
    /** Delete report bytes but keep completed keys so resuming cannot retest them. */
    fun deleteTrial(id:String,key:String):Boolean {
        ensureHistoryIndex()
        return synchronized(lock){
            val row=db.rawQuery("SELECT n,summary FROM trials WHERE session=? AND key=? AND cleared=0 AND favorite=0",arrayOf(id,key)).use{
                if(it.moveToFirst())it.getLong(0) to JSONObject(it.getString(1)) else null
            }?:return@synchronized false
            db.beginTransaction()
            try{
                val removed=removeUnstarred(row.first,key,row.second)
                if(removed)updateDeletedBatch(id,1)
                db.setTransactionSuccessful();removed
            }finally{db.endTransaction()}
        }
    }
    /** Across all categories and pages. Recheck stars inside every bounded deletion transaction. */
    fun deleteUnstarred(through:Long,progress:(Long)->Unit={}):Long {
        require(through>=0)
        ensureHistoryIndex()
        var after=0L;var total=0L
        data class Row(val n:Long,val id:String,val key:String,val summary:JSONObject)
        while(true){
            val count=synchronized(lock){
                val rows=mutableListOf<Row>()
                db.rawQuery("SELECT n,session,key,summary FROM trials WHERE cleared=0 AND favorite=0 AND n>? AND n<=? ORDER BY n LIMIT 200",arrayOf(after.toString(),through.toString())).use{cursor->while(cursor.moveToNext()){
                    rows+=Row(cursor.getLong(0),cursor.getString(1),cursor.getString(2),runCatching{JSONObject(cursor.getString(3))}.getOrDefault(JSONObject()))
                }}
                if(rows.isEmpty())return total
                db.beginTransaction()
                try{
                    val removed=mutableMapOf<String,Long>()
                    rows.forEach{row->if(removeUnstarred(row.n,row.key,row.summary))removed[row.id]=(removed[row.id]?:0L)+1L}
                    removed.forEach{(id,n)->updateDeletedBatch(id,n)}
                    db.setTransactionSuccessful();after=rows.last().n
                    removed.values.sum()
                }finally{db.endTransaction()}
            }
            total+=count;progress(total)
        }
    }
    fun report(id:String,key:String):JSONObject?=synchronized(lock){
        db.rawQuery("SELECT report FROM trials WHERE session=? AND key=? AND cleared=0",arrayOf(id,key)).use{cursor->
            if(!cursor.moveToFirst())null else GZIPInputStream(ByteArrayInputStream(cursor.getBlob(0))).bufferedReader().use{JSONObject(it.readText())}
        }
    }
}
