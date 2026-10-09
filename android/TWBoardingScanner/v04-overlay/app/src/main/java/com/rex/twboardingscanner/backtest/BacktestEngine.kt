package com.rex.twboardingscanner.backtest

import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.paper.PaperEngine
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.*

fun defaultBtRules()=RadarType.entries.associateWith{type->ScanConditions.forRadar(type).filter{it.defaultEnabled}.map{it.id}.toSet()}
fun btPercent(value:Double)=java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
data class BtSettings(val start:LocalDate,val end:LocalDate,val capital:Double=3000000.0,val codes:String="",val rules:Map<RadarType,Set<String>> = defaultBtRules(),val sectors:Set<StockSector> = StockSector.entries.toSet(),val strategyVersion:Int=4,
    val maxHoldingStocks:Int?=if(strategyVersion>=4)25 else null,val targetNetPct:Double=3.0){
    fun validateTrading(){
        require(strategyVersion in 2..4)
        if(strategyVersion>=4)require(maxHoldingStocks!=null&&maxHoldingStocks>0){"最高持倉請填 1 以上的整數"}
        else require(maxHoldingStocks==null&&targetNetPct==3.0){"舊策略必須保留原持倉及獲利規則"}
        require(targetNetPct.isFinite()&&targetNetPct in 0.01..1000.0){"獲利賣出請填 0.01～1000%"}
    }
}
data class BtSeries(val code:String,val name:String,val market:Market,val bars:List<DailyBar>,val dividends:Map<LocalDate,Double> = emptyMap())
data class BtSignal(val code:String,val radar:String,val dataDate:LocalDate?=null)
data class BtTrade(val date:LocalDate,val signalDate:LocalDate,val code:String,val name:String,val radar:String,val side:String,val price:Double,val fee:Double,val tax:Double,val pnl:Double,val reason:String,val shares:Int=1000,val time:String="",val timeKind:String="",val lotId:String="",val target:Double=0.0,val dataDate:LocalDate=signalDate,val dayClose:Double?=null,val previousClose:Double?=null)
data class BtHolding(val code:String,val name:String,val radar:String,val entry:Double,val cost:Double,val entryDate:LocalDate,val signalDate:LocalDate,var mark:Double,var markDate:LocalDate,val lotId:String,val target:Double,val dataDate:LocalDate)
data class BtDay(val date:LocalDate,val equity:Double,val selected:Int=0,val buys:Int=0,val sells:Int=0,val skipped:Int=0,
    val realized:Double?=null,val holdingCost:Double?=null,val holdingValue:Double?=null,val holdingLots:Int?=null,
    val cash:Double?=null,val dividendAccrued:Double?=null,val dayProfit:Double?=null,val staleLots:Int?=null,val rebateAccrued:Double?=null,val rebateChange:Double?=null)
data class BtRebateMonth(val month:String,var buyAmount:Double=0.0,var sellAmount:Double=0.0,var buyTrades:Int=0,var sellTrades:Int=0){
    val turnover:Double get()=buyAmount+sellAmount
    val rate:Double get()=if(Math.round(turnover*100)>5000000000L)0.001 else 0.0005
    val amount:Double get()=Math.round(turnover*rate*100)/100.0
}
data class BtSkipped(val date:LocalDate,val code:String,val reason:String)
data class BtRun(val capital:Double,var cash:Double=capital,var dividendAccrued:Double=0.0,val trades:MutableList<BtTrade> = mutableListOf(),val holdings:MutableList<BtHolding> = mutableListOf(),val curve:MutableList<BtDay> = mutableListOf(),val skipped:MutableList<BtSkipped> = mutableListOf(),val strategyVersion:Int=3,val rebateMonths:MutableMap<String,BtRebateMonth> = sortedMapOf()){
    val rebateAccrued:Double get()=rebateMonths.values.sumOf{it.amount}
    val equity:Double get()=cash+dividendAccrued+rebateAccrued+holdings.sumOf{PaperEngine.netSell(it.mark)}
    val profit:Double get()=equity-capital
    val closed:List<BtTrade> get()=trades.filter{it.side=="SELL"}
    val buys:Int get()=trades.count{it.side=="BUY"}
    val winRate:Double? get()=closed.takeIf{it.isNotEmpty()}?.let{c->c.count{it.pnl>0}*100.0/c.size}
    val realized:Double get()=closed.sumOf{it.pnl}
    val unrealized:Double get()=holdings.sumOf{PaperEngine.netSell(it.mark)-it.cost}
    val drawdown:Double get(){var peak=capital;var dd=0.0;curve.forEach{peak=max(peak,it.equity);dd=max(dd,(peak-it.equity)/peak*100)};return dd}
}
data class BtPrepared(val series:List<BtSeries>,val signals:Map<LocalDate,List<BtSignal>>,val days:List<LocalDate>,val pendingChecks:Int=0)
data class BtExecutionIndex(val bars:Map<String,Map<LocalDate,DailyBar>>,val meta:Map<String,BtSeries>,val previous:Map<String,Map<LocalDate,Double>>)
data class BtResult(val settings:BtSettings,val run:BtRun,val requested:Int,val loaded:Int,val excluded:List<String>,val note:String,val pendingChecks:Int=0)

object BacktestEngine {
    private val calculator=TechnicalCalculator()
    private fun date(b:DailyBar)=RuleMetrics.tradingDate(b.time)
    fun prepare(series:List<BtSeries>,settings:BtSettings,progress:(String)->Unit={},cancel:()->Boolean={false}):BtPrepared {
        val signals=mutableMapOf<LocalDate,MutableList<BtSignal>>();var pending=0
        series.forEachIndexed{index,s->
            check(!cancel()){ "已取消回測" }
            val bars=s.bars.sortedBy{it.time}.distinctBy{date(it)}
            bars.indices.forEach{j->
                if(j%20==0)check(!cancel()){ "已取消回測" }
                val day=date(bars[j]);if(day<settings.start||day>settings.end)return@forEach
                val matches=mutableListOf<Pair<RadarType,LocalDate>>()
                RadarType.entries.forEach radarLoop@{type->
                    val ids=settings.rules[type].orEmpty();if(ids.isEmpty())return@radarLoop
                    // Match live scanner timing: A/B exclude today's candle; C includes it.
                    val end=if(type==RadarType.C_LONG_RED_VOLUME)j+1 else j
                    if(end<60)return@radarLoop
                    val known=bars.subList(max(0,end-320),end);val bar=known.last()
                    val stock=MarketStock(s.code,s.name,s.market,StockSector.UNKNOWN,bar.open,bar.high,bar.low,bar.close,0.0,(bar.volumeShares/1000).toInt(),null,null)
                    val snap=calculator.build(stock,known).copy(timestamp=day.atTime(13,30).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli())
                    val metrics=RuleMetrics(snap)
                    val checks=ScanConditions.forRadar(type).filter{it.id in ids}.map{it.test(metrics)}
                    pending+=checks.count{it==null}
                    if(checks.size==ids.size&&checks.all{it==true})matches.add(type to date(bar))
                }
                if(matches.isNotEmpty())signals.getOrPut(day){mutableListOf()}.add(BtSignal(s.code,matches.joinToString("/"){it.first.name.take(1)},matches.maxOf{it.second}))
            }
            if(index%10==0||index==series.lastIndex)progress("依目前 ABC 勾選條件掃描 ${index+1} / ${series.size} 檔")
        }
        return BtPrepared(series,signals,series.flatMap{it.bars.map(::date)}.distinct().sorted(),pending)
    }
    private fun tick(p:Double)=when{p<10->.01;p<50->.05;p<100->.1;p<500->.5;p<1000->1.0;else->5.0}
    private fun ceilPrice(p:Double)=round(ceil(p/tick(p)-1e-9)*tick(p)*100)/100
    /** Smallest permitted fill earning the requested net fraction on total buy cost. */
    fun targetPrice(cost:Double,netPct:Double=0.03):Double {
        require(cost.isFinite()&&cost>0)
        require(netPct.isFinite()&&netPct>=0&&netPct<=10)
        var price=ceilPrice(cost*(1+netPct)/1000/(1-PaperEngine.FEE_RATE-PaperEngine.TAX_RATE))
        while(PaperEngine.netSell(price)+1e-8<cost*(1+netPct))price=ceilPrice(price+tick(price))
        return price
    }
    fun triggerPrice(target:Double):Double {
        var quote=ceilPrice(target/(1-PaperEngine.SLIPPAGE))
        while(PaperEngine.execution(quote,false)+1e-8<target)quote=ceilPrice(quote+tick(quote))
        return quote
    }
    fun executionIndex(series:List<BtSeries>)=BtExecutionIndex(
        series.associate{it.code to it.bars.associateBy(::date)},series.associateBy{it.code},
        series.associate{s->s.code to s.bars.sortedBy{it.time}.distinctBy(::date).zipWithNext().associate{(prior,current)->date(current) to prior.close}})
    fun run(prepared:BtPrepared,settings:BtSettings,progress:(String)->Unit={},cancel:()->Boolean={false},execution:BtExecutionIndex?=null):BtRun {
        require(settings.capital.isFinite()&&settings.capital>0)
        settings.validateTrading()
        val dates=prepared.days.filter{it>=settings.start&&it<=settings.end};require(dates.isNotEmpty()){ "所選區間沒有有效交易日" }
        val ex=execution?:executionIndex(prepared.series)
        val indexed=ex.bars;val meta=ex.meta;val previous=ex.previous
        val result=BtRun(settings.capital,strategyVersion=settings.strategyVersion)
        dates.forEachIndexed{index,day->check(!cancel()){ "已取消回測" };advance(result,day,prepared.signals[day].orEmpty(),indexed,meta,previous,settings);progress("尾盤買入／淨利 ${btPercent(settings.targetNetPct)}% 賣出 ${index+1}/${dates.size} · $day")}
        return result
    }
    internal fun advance(b:BtRun,day:LocalDate,signals:List<BtSignal>,bars:Map<String,Map<LocalDate,DailyBar>>,meta:Map<String,BtSeries>,previous:Map<String,Map<LocalDate,Double>>,settings:BtSettings){
        val targetFraction=settings.targetNetPct/100.0
        val previousEquity=b.equity;val previousRebate=b.rebateAccrued
        val tradesBefore=b.trades.size;val skippedBefore=b.skipped.size
        val usedShares=mutableMapOf<String,Long>()
        // Only holdings brought into this day earn the ex-dividend accrual.
        b.holdings.filter{it.entryDate<day}.forEach{p->b.dividendAccrued+=(meta[p.code]?.dividends?.get(day)?:0.0)*1000}
        // Old lots are processed before today's closing buys. Today's high cannot sell today's new lot.
        b.holdings.toList().forEach{p->
            if(day<=p.entryDate)return@forEach
            val bar=bars[p.code]?.get(day)?:return@forEach
            if(bar.volumeShares-(usedShares[p.code]?:0)<1000)return@forEach
            // From day 6 onward, the standing exit is break-even. Do not inspect the
            // day's future high to choose a better target fill over an earlier break-even fill.
            val aged=b.strategyVersion>=3&&ChronoUnit.DAYS.between(p.entryDate,day)>5
            val activeTarget=if(aged)targetPrice(p.cost,0.0)else p.target
            val trigger=triggerPrice(activeTarget)
            if(bar.high+1e-8<trigger)return@forEach
            val atOpen=bar.open+1e-8>=trigger
            val px=if(atOpen)PaperEngine.execution(bar.open,false) else activeTarget
            val amount=px*1000;val fee=PaperEngine.fee(amount);val tax=PaperEngine.tax(amount);val net=amount-fee-tax
            if(net+1e-8<p.cost*(if(aged)1.0 else 1+targetFraction))return@forEach
            val reason=if(net+1e-8>=p.cost*(1+targetFraction))"扣除買賣費稅後獲利至少 ${btPercent(settings.targetNetPct)}%" else "持有超過 5 個日曆日，扣費稅後保本出場"
            b.cash+=net;b.holdings.remove(p);usedShares[p.code]=(usedShares[p.code]?:0)+1000
            b.trades.add(BtTrade(day,p.signalDate,p.code,p.name,p.radar,"SELL",px,fee,tax,net-p.cost,reason,time=if(atOpen)"09:00" else "09:00–13:30",timeKind=if(atOpen)"開盤價模擬，非逐筆時間" else "盤中觸價，確切時間未知",lotId=p.lotId,target=activeTarget,dataDate=p.dataDate,dayClose=bar.close,previousClose=previous[p.code]?.get(day)))
        }
        val boughtLots=b.trades.asSequence().filter{it.side=="BUY"}.map{it.lotId}.toHashSet()
        signals.groupBy{it.code}.toSortedMap().forEach{(code,matched)->
            val lotId="$day-$code"
            if(lotId in boughtLots)return@forEach
            val bar=bars[code]?.get(day)
            val reason=when{
                settings.maxHoldingStocks!=null&&b.holdings.none{it.code==code}&&b.holdings.map{it.code}.distinct().size>=settings.maxHoldingStocks->"已達最高持倉 ${settings.maxHoldingStocks} 檔，未買入新股"
                bar==null->"缺少當日收盤價"
                bar.volumeShares-(usedShares[code]?:0)<1000->"日成交量不足模擬一張"
                else->null
            }
            if(reason!=null){b.skipped.add(BtSkipped(day,code,reason));return@forEach}
            val px=PaperEngine.execution(bar!!.close,true);val fee=PaperEngine.fee(px*1000);val cost=px*1000+fee
            if(cost>b.cash){b.skipped.add(BtSkipped(day,code,"可用資金不足一張含手續費"));return@forEach}
            val radar=matched.flatMap{it.radar.split('/')}.distinct().sorted().joinToString("/")
            val dataDate=matched.mapNotNull{it.dataDate}.maxOrNull()?:day
            val name=meta[code]?.name?:code;val target=targetPrice(cost,targetFraction);b.cash-=cost;usedShares[code]=(usedShares[code]?:0)+1000
            b.holdings.add(BtHolding(code,name,radar,px,cost,day,day,bar.close,day,lotId,target,dataDate))
            b.trades.add(BtTrade(day,day,code,name,radar,"BUY",px,fee,0.0,0.0,"當日入選，收盤各買一張",time="13:30",timeKind="收盤價模擬時間，非逐筆成交",lotId=lotId,target=target,dataDate=dataDate,dayClose=bar.close,previousClose=previous[code]?.get(day)))
        }
        b.holdings.forEach{p->bars[p.code]?.get(day)?.let{p.mark=it.close;p.markDate=day}}
        val today=b.trades.drop(tradesBefore)
        if(b.strategyVersion>=3)today.forEach{trade->
            val month=YearMonth.from(trade.date).toString()
            val bucket=b.rebateMonths.getOrPut(month){BtRebateMonth(month)}
            if(trade.side=="BUY"){bucket.buyAmount+=trade.price*trade.shares;bucket.buyTrades++}
            else{bucket.sellAmount+=trade.price*trade.shares;bucket.sellTrades++}
        }
        b.curve.add(BtDay(day,b.equity,signals.map{it.code}.distinct().size,today.count{it.side=="BUY"},today.count{it.side=="SELL"},b.skipped.size-skippedBefore,
            realized=today.filter{it.side=="SELL"}.sumOf{it.pnl},holdingCost=b.holdings.sumOf{it.cost},
            holdingValue=b.holdings.sumOf{PaperEngine.netSell(it.mark)},holdingLots=b.holdings.size,cash=b.cash,
            dividendAccrued=b.dividendAccrued,dayProfit=b.equity-previousEquity,staleLots=b.holdings.count{it.markDate<day},rebateAccrued=b.rebateAccrued,rebateChange=b.rebateAccrued-previousRebate))
    }
}
