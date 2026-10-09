package com.rex.twboardingscanner.paper

import java.time.*
import kotlin.math.*

val TAIPEI: ZoneId = ZoneId.of("Asia/Taipei")
data class PaperCandidate(val code:String,val name:String,val exchange:String,val radar:String,val score:Int,val observedAt:Long)
data class PaperQuote(val code:String,val at:Long,val last:Double,val bid:Double,val ask:Double,val bidLots:Double,val askLots:Double,val volume:Long,val upper:Double,val lower:Double)
data class PaperPosition(val code:String,val name:String,val exchange:String,val radar:String,val entry:Double,val cost:Double,val boughtAt:Long,var peak:Double,var mark:Double,var markAt:Long)
data class PaperTrade(val code:String,val name:String,val radar:String,val side:String,val at:Long,val quoteAt:Long,val price:Double,val fee:Double,val tax:Double,val realized:Double,val reason:String,val shares:Int=1000)
data class PaperDay(val date:String,var equity:Double,var at:Long)
data class PaperBook(var capital:Double=3000000.0,var cash:Double=3000000.0,var enabled:Boolean=false,var lastRun:Long=0,var status:String="尚未啟動",var candidateAt:Long=0,
    val candidates:MutableList<PaperCandidate> = mutableListOf(),val positions:MutableList<PaperPosition> = mutableListOf(),val trades:MutableList<PaperTrade> = mutableListOf(),val days:MutableList<PaperDay> = mutableListOf())

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
    private fun tick(p:Double)=when {p<10->.01;p<50->.05;p<100->.1;p<500->.5;p<1000->1.0;else->5.0}
    fun execution(price:Double,buy:Boolean):Double {
        val slipped=price*(if(buy)1+SLIPPAGE else 1-SLIPPAGE);val step=tick(slipped)
        return round((if(buy)ceil(slipped/step-1e-8) else floor(slipped/step+1e-8))*step*100)/100
    }
    fun step(b:PaperBook,quotes:Map<String,PaperQuote>,now:Long) {
        b.lastRun=now
        if(!b.enabled){b.status="已暫停 · 不新增或賣出";return}
        if(!session(now)){b.status=if(b.positions.isEmpty())"休市／時段外 · 等待下次開盤" else "時段外停止交易 · ${b.positions.size} 檔保留至下次交易時段";return}
        val usable=quotes.filterValues{fresh(it,now)}
        if(usable.isEmpty()){b.status="等待當日有效報價 · 休市、延遲或斷線不成交";return}
        val time=local(now).toLocalTime();val flatten=time>=LocalTime.of(13,15)
        var sells=0;var buys=0;var skipped=0
        b.positions.toList().forEach {p->
            val q=usable[p.code] ?:return@forEach
            p.mark=q.last;p.markAt=q.at;p.peak=max(p.peak,q.last)
            // Never buy and sell against the same tick, including after a retry/restart.
            if(q.at<=p.boughtAt)return@forEach
            val reason=when {
                flatten->"13:15 收束平倉"
                q.last<=p.entry*.97->"觸及停損 −3%"
                q.last>=p.entry*1.06->"觸及停利 +6%"
                p.peak>=p.entry*1.03 && q.last<=p.peak*.98->"獲利達 3% 後回落 2%"
                else->null
            } ?:return@forEach
            val px=execution(q.bid,false)
            if(!px.isFinite()||q.bid<=0||q.bidLots<1||px<q.lower||px>q.upper){skipped++;return@forEach}
            val amount=px*1000;val f=fee(amount);val tax=tax(amount);val net=amount-f-tax
            b.cash+=net;b.positions.remove(p)
            b.trades.add(PaperTrade(p.code,p.name,p.radar,"SELL",now,q.at,px,f,tax,net-p.cost,reason));sells++
        }
        if(!flatten) b.candidates.sortedWith(compareByDescending<PaperCandidate>{it.score}.thenBy{it.code}).distinctBy{it.code}.forEach {c->
            if(b.positions.size>=5)return@forEach
            val age=now-c.observedAt
            if(age !in 1..7*86400000L || b.positions.any{it.code==c.code})return@forEach
            val day=local(now).toLocalDate()
            if(b.trades.any{it.code==c.code && it.side=="BUY" && local(it.at).toLocalDate()==day})return@forEach
            val q=usable[c.code] ?:return@forEach
            if(q.at<=c.observedAt)return@forEach // no hindsight fills at the signal price
            val px=execution(q.ask,true)
            if(q.last<50||q.volume<500||q.ask<=0||q.askLots<1||!px.isFinite()||px>q.upper||px<q.lower){skipped++;return@forEach}
            val f=fee(px*1000);val cost=px*1000+f
            if(cost>b.cash){skipped++;return@forEach}
            b.cash-=cost
            b.positions.add(PaperPosition(c.code,c.name,c.exchange,c.radar,px,cost,now,q.last,q.last,q.at))
            b.trades.add(PaperTrade(c.code,c.name,c.radar,"BUY",now,q.at,px,f,0.0,0.0,"${c.radar} 入選 · 條件通過率 ${c.score}% · 一股每日最多一次進場"));buys++
        }
        val day=local(now).toLocalDate().toString();val eq=equity(b)
        b.days.firstOrNull{it.date==day}?.apply{equity=eq;at=now} ?: b.days.add(PaperDay(day,eq,now))
        b.status=if(flatten)"13:15 平倉階段 · 尚餘 ${b.positions.size} 檔" else "本輪買 $buys／賣 $sells · 略過 $skipped（資金／報價／流動性）"
    }
}
