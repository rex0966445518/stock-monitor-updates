package com.rex.twboardingscanner.backtest

import android.content.Context
import android.content.ContentValues
import org.json.JSONObject
import java.io.*
import java.math.BigInteger
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.*

/** Portable, bounded checkpoint. Import is transactional and never replaces an existing batch. */
object BtRobotCheckpoint {
    const val ENGINE="bt-v4-20261009-1"
    private const val LIMIT=1024L*1024*1024
    fun compatible(s:JSONObject)=s.optString("engineVersion")==ENGINE ||
        (!s.has("engineVersion")&&s.optString("appVersion") in setOf("0.4.22","0.4.24"))
    fun digest(file:File):String {
        val hash=MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;hash.update(b,0,n)}}
        return hash.digest().joinToString(""){"%02x".format(it)}
    }
    fun cloneData(store:BtRobotStore,from:String,to:String){
        val original=store.session(from)?:return
        if(original.getJSONObject("settings").optInt("marketGuardVersion",0)!=store.session(to)?.getJSONObject("settings")?.optInt("marketGuardVersion",0))return
        if(!store.dataset(from).exists())return
        require(digest(store.dataset(from))==original.getString("datasetSha256")){"原行情快照校驗失敗"}
        store.dataset(to).parentFile?.mkdirs();store.dataset(from).copyTo(store.dataset(to),true)
        if(store.conditionFile(from).exists())store.conditionFile(from).copyTo(store.conditionFile(to),true)
        store.locked{db->val s=store.session(to)!!
            listOf("datasetSha256","loaded","requested").forEach{if(original.has(it))s.put(it,original.get(it))}
            db.update("sessions",ContentValues().apply{put("json",s.toString())},"id=?",arrayOf(to))
        }
    }
    fun export(c:Context,id:String,target:File){
        val store=BtRobotStore(c);val dir=File(c.cacheDir,"robot-export-${UUID.randomUUID()}").apply{mkdirs()}
        try{
            store.locked{db->
                val s=store.session(id)?:error("找不到測試批次")
                s.put("state","PAUSED").put("token","")
                File(dir,"session.json").writeText(s.toString(),Charsets.UTF_8)
                DataOutputStream(BufferedOutputStream(File(dir,"trials.bin").outputStream())).use{out->
                    out.writeUTF("BTTRIALS1");out.writeLong(s.getLong("tested"))
                    File(dir,"results.csv").bufferedWriter(Charsets.UTF_8).use{csv->
                        csv.write("\uFEFF序號,組合代碼,總損益,已實現,未實現,買入張數,賣出張數,最大回撤百分比,已清除,A條件,B條件,C條件\n")
                        var count=0L
                        db.rawQuery("SELECT key,summary,report,cleared FROM trials WHERE session=? ORDER BY n",arrayOf(id)).use{rows->while(rows.moveToNext()){
                            val key=rows.getString(0);val summary=rows.getString(1);val report=rows.getBlob(2);val cleared=rows.getInt(3)
                            out.writeUTF(key);val bytes=summary.toByteArray(Charsets.UTF_8);out.writeInt(bytes.size);out.write(bytes);out.writeInt(cleared);out.writeInt(report.size);out.write(report);count++
                            val r=JSONObject(summary);val rule=BtRobotSpace.rules(key)
                            val fields=listOf(r.getLong("seq"),key,r.getDouble("profit"),r.getDouble("realized"),r.getDouble("unrealized"),r.getInt("buys"),r.getInt("closed"),r.getDouble("drawdown"),cleared)+rule.values.map{it.sorted().joinToString("|")}
                            csv.write(fields.joinToString(","){"\"${it.toString().replace("\"","\"\"")}\""}+"\n")
                        }}
                        check(count==s.getLong("tested")){"測試紀錄數不符"}
                    }
                }
                if(store.dataset(id).exists())store.dataset(id).copyTo(File(dir,"history.bin.gz"))
                if(store.conditionFile(id).exists())store.conditionFile(id).copyTo(File(dir,"conditions.bin.gz"))
            }
            val snapshot=JSONObject(File(dir,"session.json").readText(Charsets.UTF_8))
            val archiveVersion=if(BtSettingsCodec.decode(snapshot.getJSONObject("settings")).marketGuardVersion>0)5 else if(BtSettingsCodec.decode(snapshot.getJSONObject("settings")).exitRules.any{it.active})4 else if(BtSettingsCodec.decode(snapshot.getJSONObject("settings")).stockPolicy.active())3 else if(snapshot.optInt("searchVersion",1)>=2||BtRobotSpace.required(snapshot.optString("forbidden","0")).signum()!=0)2 else 1
            val files=dir.listFiles()!!.sortedBy{it.name};val hashes=JSONObject()
            files.forEach{hashes.put(it.name,JSONObject().put("size",it.length()).put("sha256",digest(it)))}
            val manifest=JSONObject().put("format","AI-離職神器-robot").put("version",archiveVersion).put("engine",ENGINE).put("files",hashes)
            File(dir,"manifest.json").writeText(manifest.toString(),Charsets.UTF_8)
            target.parentFile?.mkdirs()
            ZipOutputStream(BufferedOutputStream(target.outputStream())).use{zip->(files+File(dir,"manifest.json")).forEach{f->zip.putNextEntry(ZipEntry(f.name));f.inputStream().use{it.copyTo(zip)};zip.closeEntry()}}
        }finally{dir.deleteRecursively()}
    }
    private fun readBounded(input:InputStream,limit:Int):ByteArray {
        val out=ByteArrayOutputStream();val b=ByteArray(8192)
        while(true){val n=input.read(b);if(n<0)break;require(out.size().toLong()+n<=limit){"資料超過大小上限"};out.write(b,0,n)};return out.toByteArray()
    }
    fun restore(c:Context,input:InputStream):String {
        val store=BtRobotStore(c);val dir=File(c.cacheDir,"robot-import-${UUID.randomUUID()}").apply{mkdirs()}
        val id=UUID.randomUUID().toString();var saved=false
        try{
            val allowed=setOf("manifest.json","session.json","trials.bin","results.csv","history.bin.gz","conditions.bin.gz")
            val seen=mutableSetOf<String>();var total=0L
            ZipInputStream(BufferedInputStream(input)).use{zip->while(true){val entry=zip.nextEntry?:break
                require(entry.name in allowed&&seen.add(entry.name)&&!entry.isDirectory){"備份含重複或不支援的檔案"}
                File(dir,entry.name).outputStream().use{out->val buffer=ByteArray(65536);while(true){val n=zip.read(buffer);if(n<0)break;total+=n;require(total<=LIMIT&&dir.usableSpace>16L*1024*1024){"備份過大或空間不足"};out.write(buffer,0,n)}}
                zip.closeEntry()
            }}
            require(seen.containsAll(setOf("manifest.json","session.json","trials.bin","results.csv"))){"備份不完整"}
            fun json(name:String)=JSONObject(File(dir,name).inputStream().use{String(readBounded(it,1024*1024),Charsets.UTF_8)})
            val manifest=json("manifest.json");require(manifest.getString("format")=="AI-離職神器-robot"&&manifest.getInt("version") in 1..5&&manifest.getString("engine")==ENGINE){"備份版本不相容"}
            val hashes=manifest.getJSONObject("files");require(hashes.keys().asSequence().toSet()==seen-"manifest.json")
            (seen-"manifest.json").forEach{name->val f=File(dir,name);val h=hashes.getJSONObject(name);require(f.length()==h.getLong("size")&&digest(f)==h.getString("sha256")){"備份校驗失敗：$name"}}
            val s=json("session.json");require(compatible(s)){"回測引擎版本不相容"}
            val settings=BtSettingsCodec.decode(s.getJSONObject("settings"));BtRobotSpace.validate(settings)
            require(settings.marketGuardVersion==0||manifest.getInt("version")>=5){"含大盤保護的備份需要版本 5"}
            require(!settings.stockPolicy.active()||manifest.getInt("version")>=3){"含禁股／限價的備份需要版本 3"}
            require(!settings.exitRules.any{it.active}||manifest.getInt("version")>=4){"含下車條件的備份需要版本 4"}
            val required=s.optString("required","0");val forbidden=s.optString("forbidden","0");val combinations=BtRobotSpace.total(required,forbidden)
            val searchVersion=s.optInt("searchVersion",1)
            require(searchVersion in 1..2&&searchVersion<=manifest.getInt("version")&&(BtRobotSpace.required(forbidden).signum()==0||searchVersion==2)){"排除條件與搜尋版本不符"}
            val tested=s.getLong("tested");require(tested in 0..5000000&&BigInteger.valueOf(tested)<=combinations)
            require(BigInteger(s.getString("cursor")) in BigInteger.ZERO..combinations&&BigInteger(s.getString("seed")).signum()>=0)
            val history=File(dir,"history.bin.gz");val conditions=File(dir,"conditions.bin.gz")
            if(history.exists()){
                require(digest(history)==s.getString("datasetSha256")){"固定行情校驗失敗"}
                val loaded=BtRobotDataset.read(history)
                require(settings.marketGuardVersion==0||loaded.market.isNotEmpty()){"此備份缺少加權指數快照"}
                if(conditions.exists())BtConditionIndex.read(conditions,loaded.series,BtConditionIndex.fingerprint(s.getString("datasetSha256"),settings))
            }else require(tested==0L&&!conditions.exists()){"缺少固定行情，不能續跑"}
            store.dataset(id).parentFile?.mkdirs()
            if(history.exists())history.copyTo(store.dataset(id))
            if(conditions.exists())conditions.copyTo(store.conditionFile(id))
            store.locked{db->
                db.beginTransaction()
                try{
                    var cleared=0L;var best:JSONObject?=null
                    DataInputStream(BufferedInputStream(File(dir,"trials.bin").inputStream())).use{data->
                        require(data.readUTF()=="BTTRIALS1"&&data.readLong()==tested)
                        repeat(tested.toInt()){i->
                            val key=data.readUTF();require(BtRobotSpace.key(BtRobotSpace.rules(key))==key&&BtRobotSpace.includes(key,required,forbidden))
                            val length=data.readInt();require(length in 1..65536);val summary=ByteArray(length);data.readFully(summary);val r=JSONObject(String(summary,Charsets.UTF_8))
                            require(r.getString("key")==key&&r.getLong("seq")==i+1L&&r.getDouble("profit").isFinite())
                            val deleted=data.readInt();require(deleted in 0..1)
                            val size=data.readInt();require(size in 0..32*1024*1024);val bytes=ByteArray(size);data.readFully(bytes)
                            if(deleted==1){require(size==0&&kotlin.math.abs(r.getDouble("profit"))<0.005);cleared++}else{
                                val report=GZIPInputStream(ByteArrayInputStream(bytes)).use{JSONObject(String(readBounded(it,64*1024*1024),Charsets.UTF_8))}
                                val actual=BtSettingsCodec.decode(report.getJSONObject("settings"))
                                require(actual==settings.copy(rules=BtRobotSpace.rules(key))&&report.getJSONObject("run").getDouble("profit")==r.getDouble("profit")){"組合報告與摘要不符"}
                                if(best==null||r.getDouble("profit")>best!!.getDouble("profit"))best=r
                            }
                            db.insertOrThrow("trials",null,ContentValues().apply{put("session",id);put("key",key);put("summary",r.toString());put("report",bytes);put("cleared",deleted)})
                        };require(data.read()==-1)
                    }
                    val original=s.getString("id");s.put("importedFrom",original).put("id",id).put("created",System.currentTimeMillis()).put("state","PAUSED").put("token","").put("cleared",cleared).put("engineVersion",ENGINE)
                        .put("message","已匯入斷點；按繼續接續未測組合").put("bestKey",best?.getString("key")?:"").put("bestSeq",best?.getLong("seq")?:0).put("ack",tested)
                    s.remove("bestProfit");best?.let{s.put("bestProfit",it.getDouble("profit"))}
                    db.insertOrThrow("sessions",null,ContentValues().apply{put("id",id);put("created",s.getLong("created"));put("json",s.toString())})
                    db.setTransactionSuccessful()
                }finally{db.endTransaction()}
            }
            saved=true
            store.pause(store.active());store.activate(id);return id
        }finally{dir.deleteRecursively();if(!saved){store.dataset(id).delete();store.conditionFile(id).delete()}}
    }
}
