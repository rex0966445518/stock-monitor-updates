package com.rex.twboardingscanner.paper

import android.content.Context
import com.rex.twboardingscanner.domain.*
import org.json.JSONArray
import org.json.JSONObject

class PaperRepository(context:Context) {
    private val app=context.applicationContext
    private val prefs=context.applicationContext.getSharedPreferences("paper_trading_v1",Context.MODE_PRIVATE)
    companion object { private val lock=Any() }
    fun read():PaperBook=synchronized(lock){ prefs.getString("book",null)?.let{decode(it)} ?: PaperBook() }
    private fun save(b:PaperBook){check(prefs.edit().putString("book",encode(b)).commit()){ "無法儲存模擬帳本，請檢查儲存空間" }}
    fun setEnabled(enabled:Boolean,capital:Double?=null)=synchronized(lock) {
        val b=read()
        if(capital!=null && b.trades.isEmpty()) {require(capital.isFinite()&&capital in 50000.0..100000000.0);b.capital=capital;b.cash=capital}
        b.enabled=enabled;b.status=if(enabled)"已啟動 · 等待交易時段及有效報價" else "已暫停 · 持股與紀錄保留"
        save(b)
    }
    fun publish(results:List<SignalResult>,now:Long=System.currentTimeMillis())=synchronized(lock) {
        val b=read()
        b.candidates.clear()
        b.candidates.addAll(results.filter{com.rex.twboardingscanner.data.StockPolicyStore(app).read().decision(it.code,it.snapshot.sourceStock?.close?:it.snapshot.bars.lastOrNull()?.close?:Double.NaN,StockScope.SCANNER)==StockDecision.ALLOW && it.light!=SignalLight.NONE && it.checks.any{c->c.selected} && it.checks.filter{c->c.selected}.all{c->c.state==CheckState.PASS}}
            .sortedByDescending{it.score}.distinctBy{it.code}.mapNotNull {r->
                val market=r.snapshot.sourceStock?.market ?:return@mapNotNull null
                if(!r.code.matches(Regex("[1-9][0-9]{3}")))return@mapNotNull null
                PaperCandidate(r.code,r.name,if(market==Market.TWSE)"tse" else "otc",r.radarType.name.take(1),r.score,now)
            })
        b.candidateAt=now;save(b)
    }
    fun tick():PaperBook {
        val b=read();var now=System.currentTimeMillis()
        if(!b.enabled||!PaperEngine.session(now)){return synchronized(lock){val current=read();PaperEngine.step(current,emptyMap(),now);save(current);current}}
        val eligible=b.candidates.filter{now-it.observedAt in 1..7*86400000L}.sortedWith(compareByDescending<PaperCandidate>{it.score}.thenBy{it.code}).take(60)
        val symbols=(b.positions.map{it.code to it.exchange}+eligible.map{it.code to it.exchange}).distinct()
        val quotes=runCatching{if(symbols.isEmpty())emptyMap() else PaperQuoteProvider().load(symbols)}.getOrDefault(emptyMap())
        // Use time AFTER the network request: a delayed request must not cross the 13:20 cutoff.
        return synchronized(lock) {
            val current=read();now=System.currentTimeMillis();PaperEngine.step(current,quotes,now,com.rex.twboardingscanner.data.StockPolicyStore(app).read())
            if(symbols.isEmpty()&&current.enabled&&PaperEngine.session(now))current.status="尚無有效候選股 · 請先完成 ABC 全市場掃描"
            save(current);current
        }
    }
    internal fun encode(b:PaperBook):String {
        fun arr(list:List<JSONObject>)=JSONArray(list)
        return JSONObject().put("version",1).put("capital",b.capital).put("cash",b.cash).put("enabled",b.enabled).put("lastRun",b.lastRun).put("status",b.status).put("candidateAt",b.candidateAt)
            .put("candidates",arr(b.candidates.map{JSONObject().put("code",it.code).put("name",it.name).put("exchange",it.exchange).put("radar",it.radar).put("score",it.score).put("observedAt",it.observedAt)}))
            .put("positions",arr(b.positions.map{JSONObject().put("code",it.code).put("name",it.name).put("exchange",it.exchange).put("radar",it.radar).put("entry",it.entry).put("cost",it.cost).put("boughtAt",it.boughtAt).put("peak",it.peak).put("mark",it.mark).put("markAt",it.markAt)}))
            .put("trades",arr(b.trades.map{JSONObject().put("code",it.code).put("name",it.name).put("radar",it.radar).put("side",it.side).put("at",it.at).put("quoteAt",it.quoteAt).put("price",it.price).put("fee",it.fee).put("tax",it.tax).put("realized",it.realized).put("reason",it.reason).put("shares",it.shares)}))
            .put("days",arr(b.days.map{JSONObject().put("date",it.date).put("equity",it.equity).put("at",it.at)})).toString()
    }
    internal fun decode(raw:String):PaperBook {
        val o=JSONObject(raw);require(o.getInt("version")==1){"模擬帳本版本不支援"}
        val b=PaperBook(o.getDouble("capital"),o.getDouble("cash"),o.getBoolean("enabled"),o.getLong("lastRun"),o.getString("status"),o.getLong("candidateAt"))
        fun each(key:String,block:(JSONObject)->Unit){val a=o.getJSONArray(key);for(i in 0 until a.length())block(a.getJSONObject(i))}
        each("candidates"){b.candidates.add(PaperCandidate(it.getString("code"),it.getString("name"),it.getString("exchange"),it.getString("radar"),it.getInt("score"),it.getLong("observedAt")))}
        each("positions"){b.positions.add(PaperPosition(it.getString("code"),it.getString("name"),it.getString("exchange"),it.getString("radar"),it.getDouble("entry"),it.getDouble("cost"),it.getLong("boughtAt"),it.getDouble("peak"),it.getDouble("mark"),it.getLong("markAt")))}
        each("trades"){b.trades.add(PaperTrade(it.getString("code"),it.getString("name"),it.getString("radar"),it.getString("side"),it.getLong("at"),it.getLong("quoteAt"),it.getDouble("price"),it.getDouble("fee"),it.getDouble("tax"),it.getDouble("realized"),it.getString("reason"),it.getInt("shares")))}
        each("days"){b.days.add(PaperDay(it.getString("date"),it.getDouble("equity"),it.getLong("at")))}
        require(b.capital.isFinite()&&b.capital>0&&b.cash.isFinite()&&b.cash>=0 && b.trades.all{it.shares==1000}){"模擬帳本異常，請保留資料以供檢查"}
        return b
    }
}
