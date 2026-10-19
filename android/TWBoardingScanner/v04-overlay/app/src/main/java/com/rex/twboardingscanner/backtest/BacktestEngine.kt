package com.rex.twboardingscanner.backtest

import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.paper.PaperEngine
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.*

fun defaultBtRules()=RadarType.entries.associateWith{type->ScanConditions.forRadar(type).filter{it.defaultEnabled}.map{it.id}.toSet()}
fun defaultActiveRadars()=setOf(RadarType.A_EARLY_BREAKOUT,RadarType.B_DEEP_REVERSAL,RadarType.C_LONG_RED_VOLUME)
fun btPercent(value:Double)=java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
data class BtSettings(val start:LocalDate,val end:LocalDate,val capital:Double=3000000.0,val codes:String="",val rules:Map<RadarType,Set<String>> = defaultBtRules(),val sectors:Set<StockSector> = StockSector.entries.toSet(),val strategyVersion:Int=4,
    val maxHoldingStocks:Int?=if(strategyVersion>=4)25 else null,val targetNetPct:Double=3.0,
    val stockPolicy:StockPolicy=StockPolicy(),val stockScope:StockScope=StockScope.BACKTEST,val exitRules:List<ExitRule> = emptyList(),val marketGuardVersion:Int=0,val holdProfitUntilMa5Break:Boolean=false,
    val enabledRadars:Set<RadarType> = defaultActiveRadars(),
    val dConfig:DPatternConfig=DPatternConfig.DEFAULT,
    val eConfig:EPatternConfig=EPatternConfig.DEFAULT,
    val fConfig:FPatternConfig=FPatternConfig.DEFAULT,
    val exclusions:Map<RadarType,Set<String>> = ExclusionConditions.empty(),
    val bConfig:BPatternConfig=BPatternConfig.DEFAULT,val robotBaseline:Boolean=false,
    val gConfig:GPatternConfig=GPatternConfig.DEFAULT){
    fun validateTrading(){
        require(!robotBaseline||(enabledRadars.isNotEmpty()&&rules.values.all{it.isEmpty()}&&exclusions.values.all{it.isEmpty()})){"空白基準不得含選股條件"}
        require(marketGuardVersion in 0..MarketCrashGuard.VERSION){"大盤保護版本不相容"}
        stockPolicy.validate()
        ExitRules.validate(exitRules)
        require(strategyVersion in 2..4)
        if(strategyVersion<4)require(!holdProfitUntilMa5Break){"舊交易模型不支援 RSI／5MA 續抱"}
        if(strategyVersion>=4)require(maxHoldingStocks!=null&&maxHoldingStocks>0){"最高持倉請填 1 以上的整數"}
        else require(maxHoldingStocks==null&&targetNetPct==3.0){"舊策略必須保留原持倉及獲利規則"}
        require(targetNetPct.isFinite()&&targetNetPct in 0.01..1000.0){"獲利賣出請填 0.01～1000%"}
        require(enabledRadars.all{it in RadarType.entries}){"啟用區域不存在"}
        dConfig.validated();eConfig.validated();fConfig.validated();bConfig.validated();gConfig.validated();ExclusionConditions.validate(exclusions)
    }
}
data class BtSeries(val code:String,val name:String,val market:Market,val bars:List<DailyBar>,val dividends:Map<LocalDate,Double> = emptyMap())
data class BtSignal(val code:String,val radar:String,val dataDate:LocalDate?=null,val dEvidence:String="")
data class BtTrade(val date:LocalDate,val signalDate:LocalDate,val code:String,val name:String,val radar:String,val side:String,val price:Double,val fee:Double,val tax:Double,val pnl:Double,val reason:String,val shares:Int=1000,val time:String="",val timeKind:String="",val lotId:String="",val target:Double=0.0,val dataDate:LocalDate=signalDate,val dayClose:Double?=null,val previousClose:Double?=null)
data class BtHolding(val code:String,val name:String,val radar:String,val entry:Double,var cost:Double,val entryDate:LocalDate,val signalDate:LocalDate,var mark:Double,var markDate:LocalDate,val lotId:String,val target:Double,val dataDate:LocalDate,var trendHoldActivated:Boolean=false,
    var shares:Int=1000,var g3Reduced:Int=0,var g4Reduced:Int=0)
data class BtDay(val date:LocalDate,val equity:Double,val selected:Int=0,val buys:Int=0,val sells:Int=0,val skipped:Int=0,
    val realized:Double?=null,val holdingCost:Double?=null,val holdingValue:Double?=null,val holdingLots:Int?=null,
    val cash:Double?=null,val dividendAccrued:Double?=null,val dayProfit:Double?=null,val staleLots:Int?=null,val rebateAccrued:Double?=null,val rebateChange:Double?=null,val marketGuard:MarketGuardDecision?=null)
data class BtRebateMonth(val month:String,var buyAmount:Double=0.0,var sellAmount:Double=0.0,var buyTrades:Int=0,var sellTrades:Int=0){
    val turnover:Double get()=buyAmount+sellAmount
    val rate:Double get()=if(Math.round(turnover*100)>5000000000L)0.001 else 0.0005
    val amount:Double get()=Math.round(turnover*rate*100)/100.0
}
data class BtLimited(val date:LocalDate,val code:String,val name:String,val price:Double,val radar:String)
data class BtSkipped(val date:LocalDate,val code:String,val reason:String)
data class BtRun(val capital:Double,var cash:Double=capital,var dividendAccrued:Double=0.0,val trades:MutableList<BtTrade> = mutableListOf(),val holdings:MutableList<BtHolding> = mutableListOf(),val curve:MutableList<BtDay> = mutableListOf(),val skipped:MutableList<BtSkipped> = mutableListOf(),val strategyVersion:Int=3,val rebateMonths:MutableMap<String,BtRebateMonth> = sortedMapOf(),val limited:MutableList<BtLimited> = mutableListOf(),val skippedDetailLimit:Int=Int.MAX_VALUE,var omittedSkipped:Int=0){
    val skippedCount:Int get()=skipped.size+omittedSkipped
    fun addSkipped(value:BtSkipped){if(skipped.size<skippedDetailLimit)skipped.add(value) else omittedSkipped++}
    val rebateAccrued:Double get()=rebateMonths.values.sumOf{it.amount}
    val equity:Double get()=cash+dividendAccrued+rebateAccrued+holdings.sumOf{PaperEngine.netSell(it.mark,it.shares)}
    val profit:Double get()=equity-capital
    val closed:List<BtTrade> get()=trades.filter{it.side=="SELL"}
    val buys:Int get()=trades.count{it.side=="BUY"}
    val winRate:Double? get()=closed.takeIf{it.isNotEmpty()}?.let{c->c.count{it.pnl>0}*100.0/c.size}
    val realized:Double get()=closed.sumOf{it.pnl}
    val unrealized:Double get()=holdings.sumOf{PaperEngine.netSell(it.mark,it.shares)-it.cost}
    val drawdown:Double get(){var peak=capital;var dd=0.0;curve.forEach{peak=max(peak,it.equity);dd=max(dd,(peak-it.equity)/peak*100)};return dd}
}
data class BtPrepared(val series:List<BtSeries>,val signals:Map<LocalDate,List<BtSignal>>,val days:List<LocalDate>,val pendingChecks:Int=0,val marketGuards:Map<LocalDate,MarketGuardDecision> = emptyMap())
data class BtExecutionIndex(val bars:Map<String,Map<LocalDate,DailyBar>>,val meta:Map<String,BtSeries>,val previous:Map<String,Map<LocalDate,Double>>)
data class BtResult(val settings:BtSettings,val run:BtRun,val requested:Int,val loaded:Int,val excluded:List<String>,val note:String,val pendingChecks:Int=0)

object BacktestEngine {
    private val calculator=TechnicalCalculator()
    private fun date(b:DailyBar)=RuleMetrics.tradingDate(b.time)
    fun prepare(series:List<BtSeries>,settings:BtSettings,progress:(String)->Unit={},cancel:()->Boolean={false},
        flowFor:(String,LocalDate)->TradeSideVolume?={_,_->null},
        benchmark:Map<LocalDate,Double>?=null):BtPrepared {
        val signals=mutableMapOf<LocalDate,MutableList<BtSignal>>();var pending=0
        val options=RadarType.entries.filter{it in settings.enabledRadars&&(settings.robotBaseline||settings.rules[it].orEmpty().isNotEmpty())}
            .associateWith{type->ScanConditions.forRadar(type).filter{it.id in settings.rules.getValue(type)}}
        val exclusions=options.keys.associateWith{type->ExclusionConditions.forRadar(type).filter{it.id in settings.exclusions.getValue(type)}}
        val active=BacktestProgress.activeLabels(settings)
        progress("依本次 $active 條件掃描 0 / ${series.size} 檔")
        series.forEachIndexed{index,s->
            check(!cancel()){ "已取消回測" }
            val bars=s.bars.sortedBy{it.time}.distinctBy{date(it)}
            bars.indices.forEach{j->
                if(j%20==0)check(!cancel()){ "已取消回測" }
                val day=date(bars[j]);if(day<settings.start||day>settings.end)return@forEach
                val matches=mutableListOf<Pair<RadarType,LocalDate>>()
                var dDetails=""
                val dailyMetrics=mutableMapOf<Int,RuleMetrics>()
                options.forEach radarLoop@{(type,conditions)->
                    val ids=settings.rules.getValue(type)
                    if(type==RadarType.G_MA5_WAVE&&!ids.containsAll(GPattern.REQUIRED_IDS))return@radarLoop
                    // Match live scanner timing: A/B exclude today's candle; C includes it.
                    val end=if(type==RadarType.C_LONG_RED_VOLUME||type==RadarType.D_MAIN_FORCE)j+1 else j
                    if(end<60)return@radarLoop
                    val metrics=dailyMetrics.getOrPut(end){
                        val known=bars.subList(max(0,end-320),end);val bar=known.last()
                        val stock=MarketStock(s.code,s.name,s.market,StockSector.UNKNOWN,bar.open,bar.high,bar.low,bar.close,0.0,(bar.volumeShares/1000).toInt(),null,null,
                            tradeSideVolume=flowFor(s.code,day))
                        val snap=calculator.buildForRules(stock,known).copy(
                            timestamp=day.atTime(13,30).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),
                            dConfig=settings.dConfig,eConfig=settings.eConfig,fConfig=settings.fConfig,bConfig=settings.bConfig,gConfig=settings.gConfig,marketIndexCloses=benchmark)
                        RuleMetrics(snap)
                    }
                    val checks=conditions.map{it.test(metrics)}
                    val vetoes=exclusions.getValue(type).map{it.test(metrics)}
                    pending+=checks.count{it==null}+vetoes.count{it==null}
                    if(checks.size==ids.size&&checks.all{it==true}&&vetoes.all{it==false}){
                        matches.add(type to date(metrics.last!!))
                        if(type==RadarType.A_EARLY_BREAKOUT&&ids.any{it in APattern.IDS})dDetails+=metrics.aPattern.format()+"\n"
                        if(type==RadarType.D_MAIN_FORCE)dDetails+=metrics.dPattern.format()+"\n"
                        if(type==RadarType.F_VOLUME_CONTRACTION)dDetails+=metrics.fPattern.format()+"\n"
                         if(type==RadarType.G_MA5_WAVE)dDetails+=metrics.gPattern.format()+"\n"
                        if(type==RadarType.E_DIF_TOUCH)dDetails+=metrics.ePattern?.format().orEmpty()+"\n"
                    }
                }
                if(matches.isNotEmpty())signals.getOrPut(day){mutableListOf()}.add(BtSignal(s.code,matches.joinToString("/"){it.first.name.take(1)},matches.maxOf{it.second},dDetails.trimEnd()))
            }
            if(index%10==0||index==series.lastIndex)progress("依本次 $active 條件掃描 ${index+1} / ${series.size} 檔")
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
        series.associate{it.code to if(it.bars is DiskDailyBars)DiskBarDateMap(it.bars,LocalDate.MIN,LocalDate.MAX.minusDays(1)){i->it.bars[i]} else it.bars.associateBy(::date)},series.associateBy{it.code},
        series.associate{s->s.code to if(s.bars is DiskDailyBars)DiskBarDateMap(s.bars,LocalDate.MIN,LocalDate.MAX.minusDays(1),previous=true){i->s.bars[i-1].close} else s.bars.sortedBy{it.time}.distinctBy(::date).zipWithNext().associate{(prior,current)->date(current) to prior.close}})
    /** Keep the full source in meta for exit indicators; execution lookups need only the tested dates. */
    fun executionIndex(series:List<BtSeries>,start:LocalDate,end:LocalDate):BtExecutionIndex {
        val bars=series.associate { s->s.code to if(s.bars is DiskDailyBars)DiskBarDateMap(s.bars,start,end){i->s.bars[i]} else s.bars.filter{date(it) in start..end}.associateBy(::date) }
        val previous=series.associate { s->
            if(s.bars is DiskDailyBars)return@associate s.code to DiskBarDateMap(s.bars,start,end,previous=true){i->s.bars[i-1].close}
            val values=mutableMapOf<LocalDate,Double>();var prior:DailyBar?=null
            s.bars.sortedBy{it.time}.distinctBy(::date).forEach { bar->
                val day=date(bar)
                if(day in start..end)prior?.let{values[day]=it.close}
                prior=bar
            }
            s.code to values
        }
        return BtExecutionIndex(bars,series.associateBy{it.code},previous)
    }
    fun run(prepared:BtPrepared,settings:BtSettings,progress:(String)->Unit={},cancel:()->Boolean={false},execution:BtExecutionIndex?=null,skippedDetailLimit:Int=Int.MAX_VALUE):BtRun {
        require(settings.capital.isFinite()&&settings.capital>0)
        settings.validateTrading()
        val dates=prepared.days.filter{it>=settings.start&&it<=settings.end};require(dates.isNotEmpty()){ "所選區間沒有有效交易日" }
        if(settings.marketGuardVersion>0)require(dates.all{it in prepared.marketGuards}){"大盤歷史資料尚未備妥，不能進行受保護回測"}
        val ex=execution?:executionIndex(prepared.series)
        val indexed=ex.bars;val meta=ex.meta;val previous=ex.previous
        require(skippedDetailLimit>=0)
        val result=BtRun(settings.capital,strategyVersion=settings.strategyVersion,skippedDetailLimit=skippedDetailLimit)
        progress("尾盤買入／淨利 ${btPercent(settings.targetNetPct)}% 賣出 0/${dates.size}")
        dates.forEachIndexed{index,day->check(!cancel()){ "已取消回測" };advance(result,day,prepared.signals[day].orEmpty(),indexed,meta,previous,settings,prepared.marketGuards[day]);progress("尾盤買入／淨利 ${btPercent(settings.targetNetPct)}% 賣出 ${index+1}/${dates.size} · $day")}
        return result
    }
    internal fun advance(b:BtRun,day:LocalDate,signals:List<BtSignal>,bars:Map<String,Map<LocalDate,DailyBar>>,meta:Map<String,BtSeries>,previous:Map<String,Map<LocalDate,Double>>,settings:BtSettings,marketGuard:MarketGuardDecision?=null){
        val guard=if(settings.marketGuardVersion>0)marketGuard?:MarketCrashGuard.unknown("缺少大盤判斷，暫停買入") else null
        val targetFraction=settings.targetNetPct/100.0
        val previousEquity=b.equity;val previousRebate=b.rebateAccrued
        val tradesBefore=b.trades.size;val skippedBefore=b.skippedCount
        val usedShares=mutableMapOf<String,Long>()
        // Only holdings brought into this day earn the ex-dividend accrual.
        b.holdings.filter{it.entryDate<day}.forEach{p->b.dividendAccrued+=(meta[p.code]?.dividends?.get(day)?:0.0)*p.shares}
        // Old lots are processed before today's closing buys. Today's high cannot sell today's new lot.
        b.holdings.toList().forEach{p->
            if(day<=p.entryDate)return@forEach
            val bar=bars[p.code]?.get(day)?:return@forEach
            if(bar.volumeShares-(usedShares[p.code]?:0)<p.shares)return@forEach
            val completeBars=if(settings.holdProfitUntilMa5Break)
                meta[p.code]?.bars.orEmpty().filter{date(it)<day} else emptyList()
            val dailyTrend=if(settings.holdProfitUntilMa5Break)ProfitTrendHold.fromCompleted(completeBars) else null
            if(settings.exitRules.any{it.active}) {
                val known=meta[p.code]?.bars.orEmpty().filter{date(it)<day}.sortedBy{it.time}.distinctBy(::date)
                val signal=known.lastOrNull()
                if(signal!=null&&date(signal)>=p.entryDate) {
                    val pnlPct=(PaperEngine.netSell(signal.close,p.shares)/p.cost-1)*100
                    val matched=ExitRules.match(settings.exitRules,p.entryDate,date(signal),pnlPct,known)
                    if(matched!=null&&bar.open.isFinite()&&bar.open>0) {
                        val px=PaperEngine.execution(bar.open,false);val amount=px*p.shares
                        val fee=PaperEngine.fee(amount);val tax=PaperEngine.tax(amount);val net=amount-fee-tax
                        val oldShares=p.shares
                        b.cash+=net;b.holdings.remove(p);usedShares[p.code]=(usedShares[p.code]?:0)+oldShares
                        val reason="下車條件 ${matched+1}：${settings.exitRules[matched].description()}；訊號 ${date(signal)}，淨損益 ${btPercent(pnlPct)}%"
                        b.trades.add(BtTrade(day,p.signalDate,p.code,p.name,p.radar,"SELL",px,fee,tax,net-p.cost,reason,time="09:00",timeKind="前一完整日線判斷；下一可交易日開盤價模擬",shares=oldShares,lotId=p.lotId,target=0.0,dataDate=date(signal),dayClose=bar.close,previousClose=previous[p.code]?.get(day)))
                        return@forEach
                    }
                }
            }
            if("G" in p.radar.split("/")){
                // G exits are based on prior completed daily bars and today's open.
                // Partial 500-share sells are idealized odd-lot backtest fills.
                val completed=meta[p.code]?.bars.orEmpty().filter{date(it)<day}.sortedBy{it.time}.distinctBy(::date)
                val signal=GExitEngine.decide(completed,settings.gConfig,p.entry,p.shares,p.g3Reduced,p.g4Reduced)
                if(signal!=null&&bar.open.isFinite()&&bar.open>0){
                    val action=signal.action
                    val remainingVolume=bar.volumeShares-(usedShares[p.code]?:0L)
                    if(action in setOf(GAction.STOP,GAction.G5_EXIT,GAction.G4_REDUCE,GAction.G3_REDUCE)){
                        val pct=if(action==GAction.G3_REDUCE)settings.gConfig.g3SellPct else settings.gConfig.g4SellPct
                        val quantity=if(action in setOf(GAction.STOP,GAction.G5_EXIT))p.shares
                            else kotlin.math.floor(p.shares*pct/100.0).toInt().coerceIn(1,p.shares)
                        if(remainingVolume>=quantity){
                            val px=PaperEngine.execution(bar.open,false)
                            val amount=px*quantity;val fee=PaperEngine.fee(amount);val tax=PaperEngine.tax(amount);val net=amount-fee-tax
                            val basis=p.cost*quantity/p.shares
                            b.cash+=net;usedShares[p.code]=(usedShares[p.code]?:0L)+quantity
                            val closed=quantity==p.shares
                            p.shares-=quantity;p.cost-=basis
                            if(action==GAction.G3_REDUCE)p.g3Reduced+=quantity
                            if(action==GAction.G4_REDUCE)p.g4Reduced+=quantity
                            if(closed)b.holdings.remove(p)
                            b.trades.add(BtTrade(day,p.signalDate,p.code,p.name,p.radar,"SELL",px,fee,tax,net-basis,
                                signal.detail+"（${quantity}股；訊號 ${signal.signalDate}；隔日開盤模擬）",
                                shares=quantity,time="09:00",timeKind="完整日K判斷；次日開盤模擬，零股價格未單獨取得",
                                lotId=p.lotId,dataDate=signal.signalDate,dayClose=bar.close,previousClose=previous[p.code]?.get(day)))
                        }
                    }else{
                        val quantity=if(action==GAction.G3_REBUY)p.g3Reduced else p.g4Reduced
                        if(quantity>0 && remainingVolume>=quantity && (guard==null||guard.canBuy)){
                            val px=PaperEngine.execution(bar.open,true)
                            val amount=px*quantity;val fee=PaperEngine.fee(amount);val cost=amount+fee
                            if(cost<=b.cash){
                                b.cash-=cost;usedShares[p.code]=(usedShares[p.code]?:0L)+quantity
                                p.shares+=quantity;p.cost+=cost
                                if(action==GAction.G3_REBUY)p.g3Reduced=0 else p.g4Reduced=0
                                b.trades.add(BtTrade(day,p.signalDate,p.code,p.name,p.radar,"BUY",px,fee,0.0,0.0,
                                    signal.detail+"（回補 ${quantity}股；訊號 ${signal.signalDate}）",
                                    shares=quantity,time="09:00",timeKind="完整日K判斷；次日開盤模擬，零股價格未單獨取得",
                                    lotId=p.lotId,dataDate=signal.signalDate,dayClose=bar.close,previousClose=previous[p.code]?.get(day)))
                            }
                        }
                    }
                }
                // Never apply non-G 3% profit/5-day exit to a G-managed holding.
                return@forEach
            }
            // An armed lot ignores the normal target and five-day break-even exits.
            // A close below MA5 must be confirmed YESTERDAY; simulate the NEXT open.
            // Explicit loss exits above retain their priority.
            if(settings.holdProfitUntilMa5Break&&p.trendHoldActivated){
                if(dailyTrend?.closedBelowMa5==true&&bar.open.isFinite()&&bar.open>0){
                    val px=PaperEngine.execution(bar.open,false)
                    val amount=px*1000;val fee=PaperEngine.fee(amount);val tax=PaperEngine.tax(amount)
                    val net=amount-fee-tax
                    b.cash+=net;b.holdings.remove(p);usedShares[p.code]=(usedShares[p.code]?:0)+1000
                    b.trades.add(BtTrade(day,p.signalDate,p.code,p.name,p.radar,"SELL",px,fee,tax,net-p.cost,
                        "獲利達標後續抱；${dailyTrend.date} 日K收盤 ${btPercent(dailyTrend.close)} 跌破5日線 ${btPercent(dailyTrend.ma5)}，下一交易日開盤賣出",
                        time="09:00",timeKind="前一完整日K跌破5日線；下一交易日開盤價模擬",
                        lotId=p.lotId,target=0.0,dataDate=dailyTrend.date,dayClose=bar.close,previousClose=previous[p.code]?.get(day)))
                }
                return@forEach
            }
            // The daily high proves that the profit target could trade intraday,
            // but the decision to defer uses ONLY the previous completed day.
            if(settings.holdProfitUntilMa5Break&&dailyTrend?.canHoldOnTarget==true
                &&bar.high+1e-8>=triggerPrice(p.target)){
                p.trendHoldActivated=true
                return@forEach
            }
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
            if(guard!=null&&!guard.canBuy){b.addSkipped(BtSkipped(day,code,"${guard.title}：${guard.reason}"));return@forEach}
            val bar=bars[code]?.get(day)
            val decision=settings.stockPolicy.decision(code,bar?.close?:Double.NaN,settings.stockScope)
            if(decision==StockDecision.BANNED){b.addSkipped(BtSkipped(day,code,"禁股名單，不買入"));return@forEach}
            if(bar!=null&&decision==StockDecision.LIMITED){
                b.limited.add(BtLimited(day,code,meta[code]?.name?:code,bar.close,matched.flatMap{it.radar.split('/')}.distinct().sorted().joinToString("/")))
                b.addSkipped(BtSkipped(day,code,"超過限價 ${settings.stockPolicy.ceiling} 元，列入當日限價名單"));return@forEach
            }
            val reason=when{
                settings.maxHoldingStocks!=null&&b.holdings.none{it.code==code}&&b.holdings.map{it.code}.distinct().size>=settings.maxHoldingStocks->"已達最高持倉 ${settings.maxHoldingStocks} 檔，未買入新股"
                bar==null->"缺少當日收盤價"
                bar.volumeShares-(usedShares[code]?:0)<1000->"日成交量不足模擬一張"
                else->null
            }
            if(reason!=null){b.addSkipped(BtSkipped(day,code,reason));return@forEach}
            val px=PaperEngine.execution(bar!!.close,true);val fee=PaperEngine.fee(px*1000);val cost=px*1000+fee
            if(cost>b.cash){b.addSkipped(BtSkipped(day,code,"可用資金不足一張含手續費"));return@forEach}
            val radar=matched.flatMap{it.radar.split('/')}.distinct().sorted().joinToString("/")
            val dataDate=matched.mapNotNull{it.dataDate}.maxOrNull()?:day
            val name=meta[code]?.name?:code;val target=targetPrice(cost,targetFraction);b.cash-=cost;usedShares[code]=(usedShares[code]?:0)+1000
            b.holdings.add(BtHolding(code,name,radar,px,cost,day,day,bar.close,day,lotId,target,dataDate))
            b.trades.add(BtTrade(day,day,code,name,radar,"BUY",px,fee,0.0,0.0,
                "當日入選，收盤各買一張"+matched.mapNotNull{it.dEvidence.takeIf(String::isNotBlank)}
                    .distinct().joinToString("\n",prefix="\n"),time="13:30",timeKind="收盤價模擬時間，非逐筆成交",lotId=lotId,target=target,dataDate=dataDate,dayClose=bar.close,previousClose=previous[code]?.get(day)))
        }
        b.holdings.forEach{p->bars[p.code]?.get(day)?.let{p.mark=it.close;p.markDate=day}}
        val today=b.trades.drop(tradesBefore)
        if(b.strategyVersion>=3)today.forEach{trade->
            val month=YearMonth.from(trade.date).toString()
            val bucket=b.rebateMonths.getOrPut(month){BtRebateMonth(month)}
            if(trade.side=="BUY"){bucket.buyAmount+=trade.price*trade.shares;bucket.buyTrades++}
            else{bucket.sellAmount+=trade.price*trade.shares;bucket.sellTrades++}
        }
        val admitted=signals.map{it.code}.distinct().count{code->settings.stockPolicy.decision(code,bars[code]?.get(day)?.close?:Double.NaN,settings.stockScope)==StockDecision.ALLOW}
        b.curve.add(BtDay(day,b.equity,admitted,today.count{it.side=="BUY"},today.count{it.side=="SELL"},b.skippedCount-skippedBefore,
            realized=today.filter{it.side=="SELL"}.sumOf{it.pnl},holdingCost=b.holdings.sumOf{it.cost},
            holdingValue=b.holdings.sumOf{PaperEngine.netSell(it.mark,it.shares)},holdingLots=b.holdings.size,cash=b.cash,
            dividendAccrued=b.dividendAccrued,dayProfit=b.equity-previousEquity,staleLots=b.holdings.count{it.markDate<day},rebateAccrued=b.rebateAccrued,rebateChange=b.rebateAccrued-previousRebate,marketGuard=guard))
    }
}
