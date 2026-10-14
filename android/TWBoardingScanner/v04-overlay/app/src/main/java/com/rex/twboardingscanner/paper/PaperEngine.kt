package com.rex.twboardingscanner.paper

import com.rex.twboardingscanner.backtest.BtSettings
import com.rex.twboardingscanner.backtest.btPercent
import java.time.*
import kotlin.math.*

val TAIPEI: ZoneId = ZoneId.of("Asia/Taipei")
data class PaperCandidate(val code:String,val name:String,val exchange:String,val radar:String,val score:Int,val observedAt:Long,val scanId:String="")
data class PaperQuote(val code:String,val at:Long,val last:Double,val bid:Double,val ask:Double,val bidLots:Double,val askLots:Double,val volume:Long,val upper:Double,val lower:Double)
data class PaperPosition(val code:String,val name:String,val exchange:String,val radar:String,val entry:Double,val cost:Double,val boughtAt:Long,var peak:Double,var mark:Double,var markAt:Long,var trendHoldActivated:Boolean=false,val scanId:String="")
data class PaperTrade(val code:String,val name:String,val radar:String,val side:String,val at:Long,val quoteAt:Long,val price:Double,val fee:Double,val tax:Double,val realized:Double,val reason:String,val shares:Int=1000,val scanId:String="")
data class ScanProfit(val realized:Double,val unrealized:Double,val buys:Int,val markAt:Long?) { val total:Double get()=realized+unrealized }
data class PaperDay(val date:String,var equity:Double,var at:Long)
data class PaperBook(var capital:Double=3000000.0,var cash:Double=3000000.0,var enabled:Boolean=false,var lastRun:Long=0,var status:String="尚未啟動",var candidateAt:Long=0,
    val candidates:MutableList<PaperCandidate> = mutableListOf(),val positions:MutableList<PaperPosition> = mutableListOf(),val trades:MutableList<PaperTrade> = mutableListOf(),val days:MutableList<PaperDay> = mutableListOf(),
    var strategy:BtSettings?=null,var strategySavedAt:Long=0L,var holdingLimit:Int=5) {
    val maxHoldings:Int get()=strategy?.maxHoldingStocks?:holdingLimit
}

/** Paper strategy v1. Deterministic and auditable, never submits a broker order. */
object PaperEngine {
    const val FEE_RATE=0.001425
    const val TAX_RATE=0.003 // conservative stock sell tax, no day-trading discount
    const val SLIPPAGE=0.001
    fun local(at:Long)=Instant.ofEpochMilli(at).atZone(TAIPEI)
    fun session(at:Long):Boolean {val n=local(at);return n.dayOfWeek.value<=5 && n.toLocalTime()>=LocalTime.of(9,0) && n.toLocalTime()<LocalTime.of(13,20)}
    fun fresh(q:PaperQuote,now:Long)=q.last.isFinite() && q.last>0 && now-q.at in 0..90_000 && local(q.at).toLocalDate()==local(now).toLocalDate() && session(q.at)
    fun fee(value:Double)=max(20.0,ceil(value*FEE_RATE))
    fun tax(value:Double)=ceil(value*TAX_RATE)
    fun netSell(price:Double)=price*1000-fee(price*1000)-tax(price*1000)
    fun equity(b:PaperBook)=b.cash+b.positions.sumOf { netSell(it.mark) }
    fun realized(b:PaperBook)=b.trades.filter {it.side=="SELL"}.sumOf {it.realized}
    fun unrealized(b:PaperBook)=b.positions.sumOf {netSell(it.mark)-it.cost}
    fun scanProfit(b:PaperBook,id:String):ScanProfit {
        require(id.isNotBlank())
        val trades=b.trades.filter{it.scanId==id}
        val positions=b.positions.filter{it.scanId==id}
        return ScanProfit(trades.filter{it.side=="SELL"}.sumOf{it.realized},
            positions.sumOf{netSell(it.mark)-it.cost},trades.count{it.side=="BUY"},
            positions.minOfOrNull{it.markAt})
    }
    private fun tick(p:Double)=when {p<10->.01;p<50->.05;p<100->.1;p<500->.5;p<1000->1.0;else->5.0}
    fun execution(price:Double,buy:Boolean):Double {
        val slipped=price*(if(buy)1+SLIPPAGE else 1-SLIPPAGE);val step=tick(slipped)
        return round((if(buy)ceil(slipped/step-1e-8) else floor(slipped/step+1e-8))*step*100)/100
    }
    fun step(b:PaperBook,quotes:Map<String,PaperQuote>,now:Long,policy:com.rex.twboardingscanner.domain.StockPolicy=com.rex.twboardingscanner.domain.StockPolicy(),exitRules:List<com.rex.twboardingscanner.domain.ExitRule> = emptyList(),histories:Map<String,List<com.rex.twboardingscanner.domain.DailyBar>> = emptyMap(),marketGuard:com.rex.twboardingscanner.domain.MarketGuardDecision?=null) {
        b.lastRun=now
        if(!b.enabled){b.status="已暫停 · 不新增或賣出";return}
        if(!session(now)){b.status=if(b.positions.isEmpty())"休市／時段外 · 等待下次開盤" else "時段外停止交易 · ${b.positions.size} 檔保留至下次交易時段";return}
        val usable=quotes.filterValues{fresh(it,now)}
        if(usable.isEmpty()){b.status="等待當日有效報價 · 休市、延遲或斷線不成交";return}
        val time=local(now).toLocalTime();val flatten=time>=LocalTime.of(13,15)
        val strategy=b.strategy
        val tradeDay=local(now).toLocalDate()
        val mayEnter=strategy==null||tradeDay in strategy.start..strategy.end
        val maxHoldings=b.maxHoldings
        val activeExitRules=strategy?.exitRules?:exitRules
        var sells=0;var buys=0;var skipped=0
        b.positions.toList().forEach {p->
            val q=usable[p.code] ?:return@forEach
            p.mark=q.last;p.markAt=q.at;p.peak=max(p.peak,q.last)
            // Never buy and sell against the same tick, including after a retry/restart.
            if(q.at<=p.boughtAt)return@forEach
            val known=histories[p.code].orEmpty().filter{com.rex.twboardingscanner.domain.RuleMetrics.tradingDate(it.time)<local(now).toLocalDate()}.sortedBy{it.time}
            val signal=known.lastOrNull()
            val exitIndex=signal?.let{com.rex.twboardingscanner.domain.ExitRules.match(activeExitRules,local(p.boughtAt).toLocalDate(),com.rex.twboardingscanner.domain.RuleMetrics.tradingDate(it.time),(netSell(it.close)/p.cost-1)*100,known)}
            val trendEnabled=strategy?.holdProfitUntilMa5Break==true
            val dailyTrend=if(trendEnabled)com.rex.twboardingscanner.domain.ProfitTrendHold.fromCompleted(known) else null
            val aged=java.time.temporal.ChronoUnit.DAYS.between(local(p.boughtAt).toLocalDate(),tradeDay)>5
            val liveSellPrice=if(q.bid>0&&q.bidLots>=1)execution(q.bid,false) else Double.NaN
            val liveNet=if(liveSellPrice.isFinite()&&liveSellPrice>0)netSell(liveSellPrice) else Double.NaN
            val netThreshold=if(aged)1.0 else 1.0+(strategy?.targetNetPct?:6.0)/100.0
            val reached=strategy!=null&&tradeDay>local(p.boughtAt).toLocalDate()&&
                liveNet.isFinite()&&liveNet+1e-8>=p.cost*netThreshold
            val fullTargetReached=strategy!=null&&tradeDay>local(p.boughtAt).toLocalDate()&&
                liveNet.isFinite()&&liveNet+1e-8>=p.cost*(1.0+strategy.targetNetPct/100.0)
            if(trendEnabled&&exitIndex==null&&!p.trendHoldActivated&&fullTargetReached&&dailyTrend?.canHoldOnTarget==true)
                p.trendHoldActivated=true
            val reason=when {
                exitIndex!=null->"下車條件 ${exitIndex+1}：${activeExitRules[exitIndex].description()}；訊號 ${com.rex.twboardingscanner.domain.RuleMetrics.tradingDate(signal!!.time)}"
                trendEnabled&&p.trendHoldActivated&&dailyTrend?.closedBelowMa5==true->
                    "淨利達標後續抱；${dailyTrend.date} 日K收盤跌破5MA，取得有效報價後賣出"
                trendEnabled&&p.trendHoldActivated->null
                strategy!=null&&reached&&aged->"留倉超過 5 個日曆日，扣除費稅後保本出場"
                strategy!=null&&reached->"扣除買賣費稅後淨利至少 ${btPercent(strategy.targetNetPct)}%"
                strategy==null&&flatten->"13:15 收束平倉"
                strategy==null&&q.last<=p.entry*.97->"觸及停損 −3%"
                strategy==null&&q.last>=p.entry*1.06->"觸及停利 +6%"
                strategy==null&&p.peak>=p.entry*1.03 && q.last<=p.peak*.98->"獲利達 3% 後回落 2%"
                else->null
            } ?:return@forEach
            val px=execution(q.bid,false)
            if(!px.isFinite()||q.bid<=0||q.bidLots<1||px<q.lower||px>q.upper){skipped++;return@forEach}
            val amount=px*1000;val f=fee(amount);val tax=tax(amount);val net=amount-f-tax
            b.cash+=net;b.positions.remove(p)
            b.trades.add(PaperTrade(p.code,p.name,p.radar,"SELL",now,q.at,px,f,tax,net-p.cost,reason,scanId=p.scanId));sells++
        }
        if(!flatten&&mayEnter&&(marketGuard==null||marketGuard.canBuy)) b.candidates.sortedWith(compareByDescending<PaperCandidate>{it.score}.thenBy{it.code}).distinctBy{it.code}.forEach {c->
            if(b.positions.map{it.code}.distinct().size>=maxHoldings)return@forEach
            if(strategy!=null){
                val allowed=PaperStrategyConfig.allowedCodes(strategy)
                if(allowed.isNotEmpty()&&c.code !in allowed)return@forEach
                if(strategy.rules.entries.none{it.key.name.take(1)==c.radar&&it.value.isNotEmpty()})return@forEach
            }
            val age=now-c.observedAt
            if(age !in 1..7*86400000L || b.positions.any{it.code==c.code})return@forEach
            val day=local(now).toLocalDate()
            if(b.trades.any{it.code==c.code && it.side=="BUY" && local(it.at).toLocalDate()==day})return@forEach
            // An exit and a new buy cannot consume the SAME old scan signal.
            // Require a fresh candidate produced after the last sale, not just a new day.
            if(b.trades.any{it.code==c.code&&it.side=="SELL"&&it.at>=c.observedAt})return@forEach
            val q=usable[c.code] ?:return@forEach
            if(policy.decision(c.code,q.last,com.rex.twboardingscanner.domain.StockScope.SCANNER)!=com.rex.twboardingscanner.domain.StockDecision.ALLOW)return@forEach
            if(q.at<=c.observedAt)return@forEach // no hindsight fills at the signal price
            val px=execution(q.ask,true)
            if(q.last<50||q.volume<500||q.ask<=0||q.askLots<1||!px.isFinite()||px>q.upper||px<q.lower){skipped++;return@forEach}
            val f=fee(px*1000);val cost=px*1000+f
            if(cost>b.cash){skipped++;return@forEach}
            b.cash-=cost
            b.positions.add(PaperPosition(c.code,c.name,c.exchange,c.radar,px,cost,now,q.last,q.last,q.at,scanId=c.scanId))
            b.trades.add(PaperTrade(c.code,c.name,c.radar,"BUY",now,q.at,px,f,0.0,0.0,"${c.radar} 入選 · 條件通過率 ${c.score}% · 一股每日最多一次進場",scanId=c.scanId));buys++
        }
        val day=local(now).toLocalDate().toString();val eq=equity(b)
        b.days.firstOrNull{it.date==day}?.apply{equity=eq;at=now} ?: b.days.add(PaperDay(day,eq,now))
        b.status=when{
            marketGuard!=null&&!marketGuard.canBuy->"${marketGuard.title} · 本輪賣 $sells · ${marketGuard.reason}"
            !mayEnter->"目前日期不在自訂模擬交易期間，停止新買入；已有持股仍按出場條件管理"
            strategy==null&&flatten->"13:15 平倉階段 · 尚餘 ${b.positions.size} 檔"
            strategy!=null&&flatten->"尾盤停止新買入 · 自訂留倉 ${b.positions.size} 檔按淨利／認賠條件保留"
            else->"本輪買 $buys／賣 $sells · 略過 $skipped（資金／報價／流動性）"
        }
    }
}
