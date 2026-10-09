package com.rex.twboardingscanner.backtest

import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.paper.PaperEngine
import java.time.LocalDate
import kotlin.math.max

data class BtSettings(val start:LocalDate,val end:LocalDate,val capital:Double=3000000.0,val optimize:Boolean=true,val codes:String="")
data class BtSeries(val code:String,val name:String,val market:Market,val bars:List<DailyBar>,val dividends:Map<LocalDate,Double> = emptyMap())
data class BtSignal(val code:String,val radar:String,val lots:Long,val above20:Boolean,val rsi:Double)
data class BtParams(val profile:Int=0,val stop:Int=3,val target:Int=6){
    val title:String get()=listOf("標準 ABC","量能加強","趨勢加強")[profile]+" · 損 $stop% / 利 $target%"
    fun accepts(s:BtSignal)=s.lots>=listOf(500,1000,2000)[profile] && (profile==0||s.above20) && (profile<2||s.rsi>55)
}
data class BtTrade(val date:LocalDate,val signalDate:LocalDate,val code:String,val name:String,val radar:String,val side:String,val price:Double,val fee:Double,val tax:Double,val pnl:Double,val reason:String,val shares:Int=1000)
data class BtHolding(val code:String,val name:String,val radar:String,val entry:Double,val cost:Double,val entryDate:LocalDate,val signalDate:LocalDate,val params:BtParams,var mark:Double,var markDate:LocalDate,var age:Int=0)
data class BtDay(val date:LocalDate,val equity:Double)
data class BtTrial(val params:BtParams,val profit:Double,val drawdown:Double,val closed:Int,val score:Double)
data class BtFold(val start:LocalDate,val trainStart:LocalDate,val trainEnd:LocalDate,val params:BtParams,val trials:Int,val note:String,val evaluations:List<BtTrial> = emptyList())
data class BtRun(val capital:Double,var cash:Double=capital,var dividendAccrued:Double=0.0,val trades:MutableList<BtTrade> = mutableListOf(),val holdings:MutableList<BtHolding> = mutableListOf(),val curve:MutableList<BtDay> = mutableListOf(),val folds:MutableList<BtFold> = mutableListOf()){
    val equity:Double get()=cash+dividendAccrued+holdings.sumOf{PaperEngine.netSell(it.mark)}
    val profit:Double get()=equity-capital
    val closed:List<BtTrade> get()=trades.filter{it.side=="SELL"}
    val winRate:Double? get()=closed.takeIf{it.isNotEmpty()}?.let{c->c.count{it.pnl>0}*100.0/c.size}
    val realized:Double get()=closed.sumOf{it.pnl}
    val drawdown:Double get(){var peak=capital;var dd=0.0;curve.forEach{peak=max(peak,it.equity);dd=max(dd,(peak-it.equity)/peak*100)};return dd}
}
data class BtPrepared(val series:List<BtSeries>,val signals:Map<LocalDate,List<BtSignal>>,val days:List<LocalDate>)
data class BtResult(val settings:BtSettings,val run:BtRun,val baseline:BtRun,val requested:Int,val loaded:Int,val excluded:List<String>,val note:String)

object BacktestEngine {
    private val calculator=TechnicalCalculator()
    private fun date(b:DailyBar)=RuleMetrics.tradingDate(b.time)
    fun prepare(series:List<BtSeries>,settings:BtSettings,progress:(String)->Unit={},cancel:()->Boolean={false}):BtPrepared {
        val signals=mutableMapOf<LocalDate,MutableList<BtSignal>>()
        series.forEachIndexed{index,s->
            check(!cancel()){ "已取消回測" }
            val bars=s.bars.sortedBy{it.time}.distinctBy{date(it)}
            bars.indices.forEach{j->
                val day=date(bars[j]);if(j<59||day<settings.start.minusDays(15)||day>settings.end)return@forEach
                val known=bars.subList(max(0,j-319),j+1);val bar=known.last()
                // Synthetic metadata uses only this historical bar; never today's fundamentals or price.
                val stock=MarketStock(s.code,s.name,s.market,StockSector.UNKNOWN,bar.open,bar.high,bar.low,bar.close,0.0,(bar.volumeShares/1000).toInt(),null,null)
                val snap=calculator.build(stock,known).copy(timestamp=day.atTime(14,0).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli())
                val metrics=RuleMetrics(snap)
                val radar=RadarType.entries.firstOrNull{type->ScanConditions.forRadar(type).filter{!it.extra}.all{it.test(metrics)==true}}
                if(radar!=null)signals.getOrPut(day){mutableListOf()}.add(BtSignal(s.code,radar.name.take(1),bar.volumeShares/1000,snap.ma20?.let{bar.close>it}?:false,snap.rsi?:0.0))
            }
            if(index%10==0||index==series.lastIndex)progress("歷史 ABC 掃描 ${index+1} / ${series.size} 檔")
        }
        val days=series.flatMap{it.bars.map(::date)}.distinct().sorted()
        return BtPrepared(series,signals,days)
    }
    fun run(prepared:BtPrepared,settings:BtSettings,progress:(String)->Unit={},cancel:()->Boolean={false}):Pair<BtRun,BtRun> {
        require(settings.capital.isFinite()&&settings.capital>0)
        val dates=prepared.days.filter{it>=settings.start&&it<=settings.end}
        require(dates.isNotEmpty()){ "所選區間沒有有效交易日" }
        val indexed=prepared.series.associate{it.code to it.bars.associateBy(::date)}
        val meta=prepared.series.associateBy{it.code}
        val previous=prepared.days.zipWithNext().associate{(a,b)->b to a}
        val optimize=settings.optimize && dates.size>=80
        val validation=if(optimize)dates.drop(60) else dates
        val result=BtRun(settings.capital);val baseline=BtRun(settings.capital)
        var params=BtParams()
        validation.forEachIndexed{index,day->
            check(!cancel()){ "已取消回測" }
            if(optimize&&index%20==0){
                val train=dates.filter{it<day}.takeLast(60)
                val choices=(0..2).flatMap{p->listOf(2 to 4,3 to 6,4 to 8).map{BtParams(p,it.first,it.second)}}
                val evaluations=mutableListOf<BtTrial>()
                var best=Double.NEGATIVE_INFINITY;var chosen=BtParams();var enough=false
                choices.forEach{candidate->
                    check(!cancel()){ "已取消回測" }
                    val trial=BtRun(settings.capital)
                    train.forEach{advance(trial,it,candidate,prepared.signals[previous[it]].orEmpty(),previous[it],indexed,meta)}
                    val score=trial.profit/settings.capital*100-trial.drawdown*.5
                    evaluations.add(BtTrial(candidate,trial.profit,trial.drawdown,trial.closed.size,score))
                    if(trial.closed.size>=10&&score>best){best=score;chosen=candidate;enough=true}
                }
                params=if(enough)chosen else BtParams()
                result.folds.add(BtFold(day,train.first(),train.last(),params,choices.size,if(enough)"以訓練淨報酬 − 0.5 × 最大回撤評分；至少 10 筆平倉" else "訓練平倉不足 10 筆，沿用標準 ABC",evaluations))
            }
            advance(result,day,params,prepared.signals[previous[day]].orEmpty(),previous[day],indexed,meta)
            advance(baseline,day,BtParams(),prepared.signals[previous[day]].orEmpty(),previous[day],indexed,meta)
            progress("${if(optimize)"樣本外驗證" else "日線回測"} ${index+1}/${validation.size} · $day")
        }
        return result to baseline
    }
    internal fun advance(b:BtRun,day:LocalDate,params:BtParams,signals:List<BtSignal>,previous:LocalDate?,bars:Map<String,Map<LocalDate,DailyBar>>,meta:Map<String,BtSeries>){
        val exited=mutableSetOf<String>()
        // Dividend earned by prior-session holdings. Accrual isn't spendable cash (pay date unavailable).
        b.holdings.forEach{p->b.dividendAccrued+=(meta[p.code]?.dividends?.get(day)?:0.0)*1000}
        b.holdings.toList().forEach{p->
            val bar=bars[p.code]?.get(day)?:return@forEach
            val yesterday=bars[p.code]?.get(previous)?:return@forEach
            val reason=when{
                yesterday.close<=p.entry*(1-p.params.stop/100.0)->"前日收盤停損，次日開盤執行"
                yesterday.close>=p.entry*(1+p.params.target/100.0)->"前日收盤停利，次日開盤執行"
                p.age>=5->"持有 5 個有效交易日，開盤出場"
                else->null
            }?:return@forEach
            // Only yesterday and today's opening price are known at the execution time.
            if(bar.open<=yesterday.close*.905)return@forEach
            val px=PaperEngine.execution(bar.open,false);val amount=px*1000;val fee=PaperEngine.fee(amount);val tax=PaperEngine.tax(amount)
            val net=amount-fee-tax;b.cash+=net;b.holdings.remove(p);exited+=p.code
            b.trades.add(BtTrade(day,p.signalDate,p.code,p.name,p.radar,"SELL",px,fee,tax,net-p.cost,reason))
        }
        if(previous!=null)signals.filter{params.accepts(it)}.sortedBy{it.code}.distinctBy{it.code}.forEach{s->
            if(b.holdings.size>=5||s.code in exited||b.holdings.any{it.code==s.code})return@forEach
            val bar=bars[s.code]?.get(day)?:return@forEach
            val prior=bars[s.code]?.get(previous)?:return@forEach
            if(bar.open<50||bar.open>=prior.close*1.095)return@forEach
            val px=PaperEngine.execution(bar.open,true);val fee=PaperEngine.fee(px*1000);val cost=px*1000+fee
            if(cost>b.cash)return@forEach
            val name=meta[s.code]?.name?:s.code;b.cash-=cost
            b.holdings.add(BtHolding(s.code,name,s.radar,px,cost,day,previous,params,px,day))
            b.trades.add(BtTrade(day,previous,s.code,name,s.radar,"BUY",px,fee,0.0,0.0,"${params.title} · 前日 ABC 訊號"))
        }
        b.holdings.forEach{p->bars[p.code]?.get(day)?.let{p.mark=it.close;p.markDate=day;p.age++}}
        b.curve.add(BtDay(day,b.equity))
    }
}
