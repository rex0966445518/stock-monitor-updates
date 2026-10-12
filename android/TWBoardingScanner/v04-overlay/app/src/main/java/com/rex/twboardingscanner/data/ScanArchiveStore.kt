package com.rex.twboardingscanner.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.rex.twboardingscanner.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.ceil

/**
 * An immutable folder PER COMPLETED SCAN, not a daily set union.
 * Keeping the full chosen rules and stored result cards means a later settings edit
 * cannot change prior selection/ranking, even when it is another run on the same day.
 */
data class ScanArchiveFolder(
    val id:String,val date:String,val created:Long,val name:String,val a:Int,val b:Int,val c:Int,
    val uniqueStocks:Int,val estimatedCash:Double,val changes:String,val signature:String,val d:Int=0,val e:Int=0
)
data class ScanRank(val score:Int,val explanation:String)

/** Explainable technical rank (0..100), NOT an LLM forecast or expected return. */
object ScanRanker {
    fun evaluate(result:SignalResult):ScanRank {
        val metrics=RuleMetrics(result.snapshot)
        val points=linkedMapOf<String,Int>()
        points["通過基本篩選"]=20
        if(metrics.macdUp==true)points["MACD 轉強"]=15
        if(metrics.ma5Up==true)points["5 日均線向上"]=12
        if(metrics.ma5RisingTouchesMa10==true)points["5／10 日均線靠近"]=10
        if(metrics.converging==true)points["均線收斂"]=10
        if(result.snapshot.rsi?.let{it in 50.0..70.0}==true)points["RSI 50–70"]=10
        else if(result.snapshot.rsi?.let{it in 45.0..75.0}==true)points["RSI 接近多頭區"]=5
        val volume=metrics.last?.volumeShares?.toDouble()
        val avg=metrics.bars.dropLast(1).takeLast(20).takeIf{it.size==20}
            ?.map{it.volumeShares.toDouble()}?.average()
        if(volume!=null&&avg!=null&&avg>0){
            if(volume>=avg*1.5)points["量能放大 ≥ 1.5 倍"]=10
            else if(volume>=avg)points["成交量高於均量"]=5
        }
        if(metrics.notHot==true)points["尚未過熱"]=8
        if(metrics.aboveMa5==true)points["站上 5 日線"]=5
        val score=points.values.sum().coerceIn(0,100)
        return ScanRank(score,points.entries.joinToString(" · "){"${it.key} +${it.value}"})
    }
}

class ScanArchiveStore(context:Context):SQLiteOpenHelper(context.applicationContext,
    "scanner-folders-v1.db",null,3){
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("""CREATE TABLE folders(
            id TEXT PRIMARY KEY,date TEXT NOT NULL,created INTEGER NOT NULL,name TEXT NOT NULL,
            signature TEXT NOT NULL,settings TEXT NOT NULL,details BLOB NOT NULL,
            a INTEGER NOT NULL,b INTEGER NOT NULL,c INTEGER NOT NULL,d INTEGER NOT NULL DEFAULT 0,e INTEGER NOT NULL DEFAULT 0,
            unique_count INTEGER NOT NULL,
            cash REAL NOT NULL,changes TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX folders_date_idx ON folders(date,created DESC)")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){
        if(oldVersion<3)db.execSQL("ALTER TABLE folders ADD COLUMN e INTEGER NOT NULL DEFAULT 0")
        if(oldVersion<2)db.execSQL("ALTER TABLE folders ADD COLUMN d INTEGER NOT NULL DEFAULT 0")
    }
    companion object{
        private val lock=Any()
        const val RANK_NOTE="AI 綜合技術評分是透明規則模型，不是語言模型預測，也不是保證獲利；已通過 ABCDE 篩選的股票，再依 MACD、均線、RSI、量能與過熱程度評分。"
        const val CASH_NOTE="資金約估：每個不同股號買 1,000 股，使用掃描當時可得參考價、估計 0.1% 買入滑價及至少 20 元手續費；非即時委託報價，不含未來價格變動。"
        fun snapshot(date:String,time:Long,rules:Map<RadarType,Set<String>>,sectors:Set<StockSector>,
                     policy:StockPolicy,flowRevision:String,requested:Int,analyzed:Int,failed:Int,
                     enabledRadars:Set<RadarType> = com.rex.twboardingscanner.backtest.defaultActiveRadars(),
                     dConfig:DPatternConfig=DPatternConfig.DEFAULT,eConfig:EPatternConfig=EPatternConfig.DEFAULT):JSONObject {
            require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(date))
            return JSONObject().put("schema",1).put("date",date).put("created",time)
                .put("rulesVersion",ScanConditions.VERSION)
                .put("enabledRadars",JSONArray(enabledRadars.map{it.name}.sorted()))
                .put("dConfig",dConfig.json()).put("eConfig",eConfig.json())
                .put("rules",JSONObject().apply{RadarType.entries.forEach{put(it.name,JSONArray(rules[it].orEmpty().sorted()))}})
                .put("sectors",JSONArray(sectors.map{it.name}.sorted()))
                .put("policy",policy.json()).put("flowRevision",flowRevision)
                .put("requested",requested).put("analyzed",analyzed).put("failed",failed)
        }
        fun signature(settings:JSONObject):String{
            val stable=JSONObject().put("rules",settings.getJSONObject("rules"))
                .put("enabledRadars",settings.optJSONArray("enabledRadars")?:JSONArray())
                .put("dConfig",settings.optJSONObject("dConfig")?:DPatternConfig.DEFAULT.json()).put("eConfig",settings.optJSONObject("eConfig")?:EPatternConfig.DEFAULT.json())
                .put("sectors",settings.getJSONArray("sectors")).put("policy",settings.getJSONObject("policy"))
                .put("flowRevision",settings.getString("flowRevision"))
            return MessageDigest.getInstance("SHA-256").digest(stable.toString().toByteArray(Charsets.UTF_8))
                .take(5).joinToString(""){"%02x".format(it)}
        }
        fun differences(previous:JSONObject?,current:JSONObject):String {
            if(previous==null)return "首次條件快照 · ABC／產業與名單已保存"
            val lines=mutableListOf<String>()
            val older=previous.getJSONObject("rules");val newer=current.getJSONObject("rules")
            fun strings(a:JSONArray):Set<String> = (0 until a.length()).map{a.getString(it)}.toSet()
            val pEnabled=previous.optJSONArray("enabledRadars")
                ?.let(::strings)?:com.rex.twboardingscanner.backtest.defaultActiveRadars().map{it.name}.toSet()
            val cEnabled=current.optJSONArray("enabledRadars")
                ?.let(::strings)?:com.rex.twboardingscanner.backtest.defaultActiveRadars().map{it.name}.toSet()
            if(pEnabled!=cEnabled)lines+="ABCDE 開關 ＋${(cEnabled-pEnabled).joinToString("、")}／−${(pEnabled-cEnabled).joinToString("、")}"
            val oldD=DPatternConfig.read(previous.optJSONObject("dConfig"))
            val newD=DPatternConfig.read(current.optJSONObject("dConfig"))
            if(EPatternConfig.read(previous.optJSONObject("eConfig"))!=EPatternConfig.read(current.optJSONObject("eConfig")))lines+="E 線距／交叉門檻調整（詳見快照）"
            if(oldD!=newD)lines+="D 主力參數調整（詳見快照）"
            RadarType.entries.forEach{type->
                val before=strings(older.optJSONArray(type.name)?:JSONArray())
                val after=strings(newer.optJSONArray(type.name)?:JSONArray())
                val added=after-before;val removed=before-after
                val names=ScanConditions.forRadar(type).associate{it.id to it.label.substringBefore("（")}
                if(added.isNotEmpty())lines+="${type.name.take(1)} ＋ ${added.sorted().joinToString("、"){names[it]?:it}}"
                if(removed.isNotEmpty())lines+="${type.name.take(1)} − ${removed.sorted().joinToString("、"){names[it]?:it}}"
            }
            val beforeS=strings(previous.getJSONArray("sectors"));val afterS=strings(current.getJSONArray("sectors"))
            if(beforeS!=afterS)lines+="產業 ＋${(afterS-beforeS).size}／−${(beforeS-afterS).size}"
            val prev=com.rex.twboardingscanner.domain.StockPolicy.read(previous.getJSONObject("policy"))
            val next=com.rex.twboardingscanner.domain.StockPolicy.read(current.getJSONObject("policy"))
            if(prev.banned!=next.banned)lines+="禁買名單 ＋${(next.banned-prev.banned).size}／−${(prev.banned-next.banned).size}"
            if(prev.ceiling!=next.ceiling)lines+="限價 ${prev.ceiling?:"不限"} → ${next.ceiling?:"不限"}"
            if(prev.overrides!=next.overrides)lines+="限價放行名單有變更"
            if(previous.optString("flowRevision")!=current.optString("flowRevision"))lines+="主動買／賣成交量資料版本變更"
            return lines.joinToString("\n").ifBlank{"與上一個資料夾的篩選條件相同（重新掃描）"}
        }
        private fun price(r:SignalResult)=r.snapshot.sourceStock?.close?.takeIf{it.isFinite()&&it>0}
            ?:r.snapshot.price.takeIf{it.isFinite()&&it>0}
        private fun report(r:SignalResult,rank:ScanRank):String =
            "AI 技術訊號排名 ${rank.score}/100（規則模型）\n${rank.explanation}\n"+
            (r.dEvidence?.let{it+"\n"}?:"")+
            r.checks.filter{it.selected}.joinToString("\n"){ "${it.label}："+when(it.state){
                CheckState.PASS->"通過";CheckState.FAIL->"不通過";CheckState.PENDING->"待查核"
            }}
        private fun encode(r:SignalResult,rank:ScanRank):JSONObject {
            val s=r.snapshot;val volumeAverage=metricsAvg(s.bars)
            val rows=JSONArray()
            s.bars.takeLast(60).forEach{rows.put(JSONArray().put(it.time).put(it.open).put(it.high)
                .put(it.low).put(it.close).put(it.volumeShares))}
            return JSONObject().put("code",r.code).put("name",r.name)
                .put("sector",r.sector.label).put("radar",r.radarType.name)
                .put("light",r.light.name).put("score",rank.score)
                .put("baseScore",r.score).put("reasons",r.reasons.joinToString("；"))
                .put("price",price(r)?:0.0).put("changePct",s.changePct).put("ts",s.timestamp)
                .put("scanDate",RuleMetrics.tradingDate(s.timestamp).toString())
                .put("rsi",s.rsi?:JSONObject.NULL).put("ma20",s.ma20?:JSONObject.NULL)
                .put("volumeRatio",volumeAverage?.takeIf{it>0}?.let{s.volumeLots/it}?:JSONObject.NULL)
                .put("eps",s.epsTtm?:JSONObject.NULL)
                .put("macdPass",RuleMetrics(s).macdUp==true)
                .put("ma20Pass",s.ma20?.let{s.price>it}==true)
                .put("rsiPass",s.rsi?.let{it>50}==true)
                .put("volumePass",RuleMetrics(s).volumeAtLeast(1.5)==true)
                .put("epsPass",RuleMetrics(s).earnings?:JSONObject.NULL)
                .put("report",report(r,rank)).put("bars",rows)
        }
        private fun metricsAvg(bars:List<DailyBar>):Double?=
            bars.dropLast(1).takeLast(20).takeIf{it.size==20}
                ?.map{it.volumeShares/1000.0}?.average()
        private fun zipped(rows:JSONArray):ByteArray{
            val data=rows.toString().toByteArray(Charsets.UTF_8)
            val buf=ByteArrayOutputStream()
            GZIPOutputStream(buf).use{it.write(data)}
            return buf.toByteArray()
        }
        private fun unzip(bytes:ByteArray):JSONArray =
            GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8).use{
                JSONArray(it.readText())
            }
        private fun asHistory(o:JSONObject):HistoryRow {
            fun nullable(key:String)=if(o.isNull(key))null else o.getDouble(key)
            fun bool(key:String)=o.optBoolean(key,false)
            val b=o.getJSONArray("bars")
            val bars=(0 until b.length()).map{i->b.getJSONArray(i).let{q->
                DailyBar(q.getLong(0),q.getDouble(1),q.getDouble(2),q.getDouble(3),q.getDouble(4),q.getLong(5))
            }}
            return HistoryRow(o.getString("code"),o.getString("name"),o.getString("sector"),
                o.getString("radar"),o.getString("light"),o.getInt("score"),o.getString("reasons"),
                o.getDouble("price"),o.getDouble("changePct"),o.getLong("ts"),o.getString("scanDate"),
                nullable("rsi"),nullable("ma20"),nullable("volumeRatio"),nullable("eps"),
                bool("macdPass"),bool("ma20Pass"),bool("rsiPass"),bool("volumePass"),
                if(o.isNull("epsPass"))null else bool("epsPass"),o.getString("report"),bars)
        }
    }
    @Synchronized fun list(date:String?=null):List<ScanArchiveFolder> {
        val items=mutableListOf<ScanArchiveFolder>()
        readableDatabase.query("folders",arrayOf("id","date","created","name","a","b","c",
            "unique_count","cash","changes","signature","d","e"),
            if(date==null)null else "date=?",if(date==null)null else arrayOf(date),
            null,null,"created DESC").use{q->
            while(q.moveToNext())items+=ScanArchiveFolder(q.getString(0),q.getString(1),q.getLong(2),
                q.getString(3),q.getInt(4),q.getInt(5),q.getInt(6),q.getInt(7),q.getDouble(8),
                q.getString(9),q.getString(10),q.getInt(11),q.getInt(12))
        }
        return items
    }
    @Synchronized fun settings(id:String):JSONObject?{
        readableDatabase.query("folders",arrayOf("settings"),"id=?",arrayOf(id),
            null,null,null).use{c->
            return if(c.moveToFirst())JSONObject(c.getString(0)) else null
        }
    }
    @Synchronized fun rows(id:String):List<HistoryRow>{
        readableDatabase.query("folders",arrayOf("details"),"id=?",arrayOf(id),null,null,null).use{c->
            if(!c.moveToFirst())return emptyList()
            val a=unzip(c.getBlob(0))
            return (0 until a.length()).map{asHistory(a.getJSONObject(it))}
                .sortedWith(compareByDescending<HistoryRow>{it.score}.thenBy{it.code}.thenBy{it.radar})
        }
    }
    @Synchronized fun addCompleteScan(date:String,time:Long,settings:JSONObject,signals:List<SignalResult>):ScanArchiveFolder {
        require(date==settings.getString("date")){"條件日期與掃描日期不符"}
        require(time==settings.getLong("created")){"掃描條件快照必須以當次掃描建立"}
        val enabled=settings.optJSONArray("enabledRadars")
            ?.let{array->(0 until array.length()).map{array.getString(it)}.toSet()}
            ?:com.rex.twboardingscanner.backtest.defaultActiveRadars().map{it.name}.toSet()
        val allowed=signals.asSequence().filter{it.light!=SignalLight.NONE}
            .filter{it.radarType.name in enabled}
            .filter{it.sector.name in (0 until settings.getJSONArray("sectors").length()).map{i->settings.getJSONArray("sectors").getString(i)}.toSet()}
            .filter{r->
                val policy=com.rex.twboardingscanner.domain.StockPolicy.read(settings.getJSONObject("policy"))
                policy.decision(r.code,price(r)?:Double.NaN,StockScope.SCANNER)==StockDecision.ALLOW
            }.distinctBy{it.code to it.radarType}.toList()
        val byScore=allowed.map{it to ScanRanker.evaluate(it)}
            .sortedWith(compareByDescending<Pair<SignalResult,ScanRank>>{it.second.score}
                .thenBy{it.first.code}.thenBy{it.first.radarType.name})
        val counts=RadarType.entries.associateWith{type->byScore.count{it.first.radarType==type}}
        val byStock=byScore.groupBy{it.first.code}
        val funding=byStock.values.sumOf{rows->
            val p=rows.mapNotNull{price(it.first)}.maxOrNull()?:0.0
            if(p<=0)0.0 else (p*1.001*1000+maxOf(20.0,ceil(p*1.001*1000*.001425)))
        }
        val previous=list().firstOrNull()?.let{settings(it.id)}
        val diff=differences(previous,settings)
        val id=UUID.randomUUID().toString()
        val docs=JSONArray().apply{byScore.forEach{(r,rank)->put(encode(r,rank))}}
        val folder=ScanArchiveFolder(id,date,time,"掃描條件 · ${signature(settings)}",
            counts.getValue(RadarType.A_EARLY_BREAKOUT),counts.getValue(RadarType.B_DEEP_REVERSAL),
            counts.getValue(RadarType.C_LONG_RED_VOLUME),byStock.size,funding,diff,signature(settings),
            counts.getValue(RadarType.D_MAIN_FORCE),counts.getValue(RadarType.E_DIF_TOUCH))
        val row=ContentValues().apply{
            put("id",id);put("date",date);put("created",time);put("name",folder.name)
            put("settings",settings.toString());put("signature",folder.signature)
            put("details",zipped(docs));put("a",folder.a);put("b",folder.b);put("c",folder.c);put("d",folder.d);put("e",folder.e)
            put("unique_count",folder.uniqueStocks);put("cash",folder.estimatedCash)
            put("changes",folder.changes)
        }
        val db=writableDatabase
        db.beginTransaction()
        try{
            check(db.insertOrThrow("folders",null,row)>0){"無法儲存掃描資料夾"}
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
        return folder
    }
}
