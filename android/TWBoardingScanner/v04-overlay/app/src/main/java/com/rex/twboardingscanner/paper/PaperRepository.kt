package com.rex.twboardingscanner.paper

import android.content.Context
import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.backtest.BtSettings
import com.rex.twboardingscanner.backtest.BtSettingsCodec
import org.json.JSONArray
import org.json.JSONObject

class PaperRepository(context:Context) {
    private val app=context.applicationContext
    private val prefs=context.applicationContext.getSharedPreferences("paper_trading_v1",Context.MODE_PRIVATE)
    companion object { private val lock=Any() }
    fun read():PaperBook=synchronized(lock){ prefs.getString("book",null)?.let{decode(it)} ?: PaperBook() }
    private fun save(b:PaperBook){check(prefs.edit().putString("book",encode(b)).commit()){ "無法儲存模擬帳本，請檢查儲存空間" }}
    /** Saving revised controls never rewrites historical trades, equity or existing lots. */
    fun saveStrategy(settings:BtSettings):PaperBook=synchronized(lock){
        val policy=com.rex.twboardingscanner.data.StockPolicyStore(app).read()
        val strategy=settings.copy(stockPolicy=policy,stockScope=StockScope.SCANNER,marketGuardVersion=MarketCrashGuard.VERSION)
        PaperStrategyConfig.validate(strategy)
        val b=read()
        if(b.trades.isNotEmpty())require(kotlin.math.abs(b.capital-strategy.capital)<0.0001){
            "已有交易紀錄，不能直接更改原始本金，以免損益失真；其他條件仍可修改"
        }
        if(b.trades.isEmpty()){
            b.capital=strategy.capital;b.cash=strategy.capital
        }
        b.strategy=strategy
        b.strategySavedAt=System.currentTimeMillis()
        b.enabled=false
        b.candidates.clear();b.candidateAt=0
        b.status="自訂條件已保存並暫停，請回主頁重新掃描，再手動啟動策略；既有持倉與交易帳本保留"
        save(b);b
    }
    /** Only sizing changes: retain running state, candidates, cash and every historical fill. */
    fun setHoldingLimit(limit:Int):PaperBook=synchronized(lock) {
        require(limit in 1..24){"持倉上限請填 1～24 檔"}
        val b=read()
        b.holdingLimit=limit
        b.strategy=b.strategy?.copy(maxHoldingStocks=limit)
        save(b);b
    }
    fun setEnabled(enabled:Boolean,capital:Double?=null)=synchronized(lock) {
        val b=read()
        if(b.strategy!=null){
            require(capital==null||kotlin.math.abs(b.capital-capital)<0.0001){"本金請至策略參數頁調整"}
            if(enabled)PaperStrategyConfig.validate(b.strategy!!)
        }else if(capital!=null && b.trades.isEmpty()) {
            require(capital.isFinite()&&capital in 50000.0..100000000.0)
            b.capital=capital;b.cash=capital
        }
        b.enabled=enabled;b.status=if(enabled)"已啟動 · 等待交易時段及有效報價" else "已暫停 · 持股與紀錄保留"
        save(b)
    }
    fun publish(results:List<SignalResult>,now:Long=System.currentTimeMillis(),scanId:String="")=synchronized(lock) {
        val b=read()
        // MainActivity may republish its PREVIOUS scan when returning from settings.
        // Do not let an old snapshot repopulate the pool after a strategy change.
        if(b.strategy!=null&&now<=b.strategySavedAt){
            b.candidates.clear();b.candidateAt=0
            save(b);return@synchronized
        }
        b.candidates.clear()
        val policy=com.rex.twboardingscanner.data.StockPolicyStore(app).read()
        val candidates=b.strategy?.let{PaperStrategyConfig.candidates(results,it,policy,now)}
            ?:results.filter{policy.decision(it.code,it.snapshot.sourceStock?.close?:it.snapshot.bars.lastOrNull()?.close?:Double.NaN,StockScope.SCANNER)==StockDecision.ALLOW &&
                it.light!=SignalLight.NONE && it.checks.any{c->c.selected} && it.checks.filter{c->c.selected}.all{c->c.state==CheckState.PASS}}
                .sortedByDescending{it.score}.distinctBy{it.code}.mapNotNull {r->
                    val market=r.snapshot.sourceStock?.market ?:return@mapNotNull null
                    if(!r.code.matches(Regex("[1-9][0-9]{3}")))return@mapNotNull null
                    PaperCandidate(r.code,r.name,if(market==Market.TWSE)"tse" else "otc",r.radarType.name.take(1),r.score,now)
                }
        b.candidates.addAll(candidates.map{it.copy(scanId=scanId)})
        b.candidateAt=now;save(b)
    }
    fun tick():PaperBook {
        val b=read();var now=System.currentTimeMillis()
        if(!b.enabled||!PaperEngine.session(now)){return synchronized(lock){val current=read();PaperEngine.step(current,emptyMap(),now);save(current);current}}
        val guard=runCatching{com.rex.twboardingscanner.data.MarketIndexData(app).live()}.getOrElse{MarketCrashGuard.unknown("大盤檢查失敗，暫停買入")}
        val eligible=b.candidates.filter{now-it.observedAt in 1..7*86400000L}.sortedWith(compareByDescending<PaperCandidate>{it.score}.thenBy{it.code}).take(60)
        val symbols=(b.positions.map{it.code to it.exchange}+eligible.map{it.code to it.exchange}).distinct()
        val exitRules=b.strategy?.exitRules?:com.rex.twboardingscanner.data.ExitRuleStore(app).read(StockScope.SCANNER)
        val histories=if(exitRules.any{it.active}||b.strategy?.holdProfitUntilMa5Break==true||b.positions.any{"G" in it.radar.split("/")})b.positions.associate{p->p.code to runCatching{
            com.rex.twboardingscanner.data.MarketDataProvider(app).loadChartHistory(p.code,if(p.exchange=="tse")Market.TWSE else Market.TPEX)
        }.getOrDefault(emptyList())}else emptyMap()
        val quotes=runCatching{if(symbols.isEmpty())emptyMap() else PaperQuoteProvider().load(symbols)}.getOrDefault(emptyMap())
        // Use time AFTER the network request: a delayed request must not cross the 13:20 cutoff.
        return synchronized(lock) {
            val current=read();now=System.currentTimeMillis();PaperEngine.step(current,quotes,now,com.rex.twboardingscanner.data.StockPolicyStore(app).read(),current.strategy?.exitRules?:com.rex.twboardingscanner.data.ExitRuleStore(app).read(StockScope.SCANNER),histories,guard)
            if(guard.canBuy&&symbols.isEmpty()&&current.enabled&&PaperEngine.session(now))current.status="尚無有效候選股 · 請先完成 ABCDEFG 全市場掃描"
            save(current);current
        }
    }
    internal fun encode(b:PaperBook):String {
        fun arr(list:List<JSONObject>)=JSONArray(list)
        return JSONObject().put("version",if(b.strategy==null)1 else 2).put("strategy",b.strategy?.let{BtSettingsCodec.encode(it)}?:JSONObject.NULL).put("strategySavedAt",b.strategySavedAt).put("holdingLimit",b.holdingLimit).put("capital",b.capital).put("cash",b.cash).put("enabled",b.enabled).put("lastRun",b.lastRun).put("status",b.status).put("candidateAt",b.candidateAt)
            .put("candidates",arr(b.candidates.map{JSONObject().put("code",it.code).put("name",it.name).put("exchange",it.exchange).put("radar",it.radar).put("score",it.score).put("observedAt",it.observedAt).put("scanId",it.scanId)}))
            .put("positions",arr(b.positions.map{JSONObject().put("code",it.code).put("name",it.name).put("exchange",it.exchange).put("radar",it.radar).put("entry",it.entry).put("cost",it.cost).put("boughtAt",it.boughtAt).put("peak",it.peak).put("mark",it.mark).put("markAt",it.markAt).put("trendHoldActivated",it.trendHoldActivated).put("scanId",it.scanId).put("shares",it.shares).put("g3Reduced",it.g3Reduced).put("g4Reduced",it.g4Reduced).put("gActionDate",it.gActionDate)}))
            .put("trades",arr(b.trades.map{JSONObject().put("code",it.code).put("name",it.name).put("radar",it.radar).put("side",it.side).put("at",it.at).put("quoteAt",it.quoteAt).put("price",it.price).put("fee",it.fee).put("tax",it.tax).put("realized",it.realized).put("reason",it.reason).put("shares",it.shares).put("scanId",it.scanId)}))
            .put("days",arr(b.days.map{JSONObject().put("date",it.date).put("equity",it.equity).put("at",it.at)})).toString()
    }
    internal fun decode(raw:String):PaperBook {
        val o=JSONObject(raw);require(o.getInt("version") in 1..2){"模擬帳本版本不支援"}
        val b=PaperBook(o.getDouble("capital"),o.getDouble("cash"),o.getBoolean("enabled"),o.getLong("lastRun"),o.getString("status"),o.getLong("candidateAt"))
        b.holdingLimit=o.optInt("holdingLimit",5).also{require(it in 1..24){"持倉上限資料異常"}}
        if(o.getInt("version")>=2){
            b.strategySavedAt=o.optLong("strategySavedAt",0L)
            b.strategy=o.optJSONObject("strategy")?.let{BtSettingsCodec.decode(it).also{value->PaperStrategyConfig.validate(value)}}
            require(b.strategy!=null&&kotlin.math.abs(b.strategy!!.capital-b.capital)<0.0001){"模擬策略本金與帳本不一致"}
        }
        fun each(key:String,block:(JSONObject)->Unit){val a=o.getJSONArray(key);for(i in 0 until a.length())block(a.getJSONObject(i))}
        each("candidates"){b.candidates.add(PaperCandidate(it.getString("code"),it.getString("name"),it.getString("exchange"),it.getString("radar"),it.getInt("score"),it.getLong("observedAt"),it.optString("scanId","")))}
        each("positions"){b.positions.add(PaperPosition(it.getString("code"),it.getString("name"),it.getString("exchange"),it.getString("radar"),it.getDouble("entry"),it.getDouble("cost"),it.getLong("boughtAt"),it.getDouble("peak"),it.getDouble("mark"),it.getLong("markAt"),it.optBoolean("trendHoldActivated",false),it.optString("scanId",""),it.optInt("shares",1000),it.optInt("g3Reduced",0),it.optInt("g4Reduced",0),it.optString("gActionDate","")))}
        each("trades"){b.trades.add(PaperTrade(it.getString("code"),it.getString("name"),it.getString("radar"),it.getString("side"),it.getLong("at"),it.getLong("quoteAt"),it.getDouble("price"),it.getDouble("fee"),it.getDouble("tax"),it.getDouble("realized"),it.getString("reason"),it.getInt("shares"),it.optString("scanId","")))}
        each("days"){b.days.add(PaperDay(it.getString("date"),it.getDouble("equity"),it.getLong("at")))}
        require(b.capital.isFinite()&&b.capital>0&&b.cash.isFinite()&&b.cash>=0 && b.trades.all{it.shares in 1..10000000}&&b.positions.all{it.shares in 1..10000000 && it.g3Reduced>=0 && it.g4Reduced>=0}){"模擬帳本異常，請保留資料以供檢查"}
        return b
    }
}
