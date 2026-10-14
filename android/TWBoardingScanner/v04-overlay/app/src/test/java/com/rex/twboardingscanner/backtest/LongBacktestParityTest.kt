package com.rex.twboardingscanner.backtest
import com.rex.twboardingscanner.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import kotlin.math.max
class LongBacktestParityTest {
    private val calculator=TechnicalCalculator()
    private fun date(b:DailyBar)=RuleMetrics.tradingDate(b.time)
    fun prepareReference(series:List<BtSeries>,settings:BtSettings,progress:(String)->Unit={},cancel:()->Boolean={false},
        flowFor:(String,LocalDate)->TradeSideVolume?={_,_->null},
        benchmark:Map<LocalDate,Double>?=null):BtPrepared {
        val signals=mutableMapOf<LocalDate,MutableList<BtSignal>>();var pending=0
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
                RadarType.entries.forEach radarLoop@{type->
                    if(type !in settings.enabledRadars)return@radarLoop
                    val ids=settings.rules[type].orEmpty();if(ids.isEmpty())return@radarLoop
                    // Match live scanner timing: A/B exclude today's candle; C includes it.
                    val end=if(type==RadarType.C_LONG_RED_VOLUME||type==RadarType.D_MAIN_FORCE)j+1 else j
                    if(end<60)return@radarLoop
                    val known=bars.subList(max(0,end-320),end);val bar=known.last()
                    val stock=MarketStock(s.code,s.name,s.market,StockSector.UNKNOWN,bar.open,bar.high,bar.low,bar.close,0.0,(bar.volumeShares/1000).toInt(),null,null,
                        tradeSideVolume=flowFor(s.code,day))
                    val snap=calculator.build(stock,known).copy(
                        timestamp=day.atTime(13,30).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),
                        dConfig=settings.dConfig,eConfig=settings.eConfig,fConfig=settings.fConfig,bConfig=settings.bConfig,marketIndexCloses=benchmark)
                    val metrics=RuleMetrics(snap)
                    val checks=ScanConditions.forRadar(type).filter{it.id in ids}.map{it.test(metrics)}
                    val vetoes=ExclusionConditions.forRadar(type).filter{it.id in settings.exclusions.getValue(type)}.map{it.test(metrics)}
                    pending+=checks.count{it==null}+vetoes.count{it==null}
                    if(checks.size==ids.size&&checks.all{it==true}&&vetoes.all{it==false}){
                        matches.add(type to date(bar))
                        if(type==RadarType.D_MAIN_FORCE)dDetails+=metrics.dPattern.format()+"\n"
                        if(type==RadarType.F_VOLUME_CONTRACTION)dDetails+=metrics.fPattern.format()+"\n"
                        if(type==RadarType.E_DIF_TOUCH)dDetails+=metrics.ePattern?.format().orEmpty()+"\n"
                    }
                }
                if(matches.isNotEmpty())signals.getOrPut(day){mutableListOf()}.add(BtSignal(s.code,matches.joinToString("/"){it.first.name.take(1)},matches.maxOf{it.second},dDetails.trimEnd()))
            }
            if(index%10==0||index==series.lastIndex)progress("依本次 $active 條件掃描 ${index+1} / ${series.size} 檔")
        }
        return BtPrepared(series,signals,series.flatMap{it.bars.map(::date)}.distinct().sorted(),pending)
    }


    private fun history():List<DailyBar> {
        val out=mutableListOf<DailyBar>();var day=LocalDate.of(2025,1,2)
        while(out.size<420){
            if(day.dayOfWeek.value<=5){
                val i=out.size;val close=100.0+12*kotlin.math.sin(i/13.0)+i*.03
                val open=close*(if(i%11==0).96 else 1.002)
                out+=DailyBar(day.atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),open,maxOf(open,close)+1,minOf(open,close)-1,close,if(i%11==0)4_000_000 else 1_000_000)
            }
            day=day.plusDays(1)
        }
        return out
    }
    @Test fun nineMonthCAndAllRadarSignalsPendingTradesAndProfitRemainIdentical() {
        val bars=history()
        val series=listOf(BtSeries("2330","測試",Market.TWSE,bars.reversed()+bars.first()))
        val first=date(bars[200]);val last=date(bars.last())
        assertTrue(java.time.temporal.ChronoUnit.MONTHS.between(first,last)>=9)
        for(enabled in listOf(setOf(RadarType.C_LONG_RED_VOLUME),RadarType.entries.toSet())){
            val rules=RadarType.entries.associateWith{setOf("price","volume")}
            val settings=BtSettings(first,last,rules=rules,enabledRadars=enabled)
            val before=System.nanoTime();val expected=prepareReference(series,settings);val oldNs=System.nanoTime()-before
            val optimizedAt=System.nanoTime();val actual=BacktestEngine.prepare(series,settings);val newNs=System.nanoTime()-optimizedAt
            assertEquals(expected,actual)
            val oldRun=BacktestEngine.run(expected,settings);val newRun=BacktestEngine.run(actual,settings)
            assertEquals(oldRun.trades,newRun.trades);assertEquals(oldRun.cash,newRun.cash,0.0)
            assertEquals(oldRun.holdings,newRun.holdings)
            println("LONG_BACKTEST active=$enabled oldMs=${oldNs/1_000_000} optimizedMs=${newNs/1_000_000}")
        }
    }
    @Test fun everySelectableRuleAndExclusionMatchesDisplaySnapshotAcrossLongWindows() {
        val bars=history()
        for(end in listOf(60,120,220,320,420)){
            val known=bars.subList(maxOf(0,end-320),end);val last=known.last()
            val stock=MarketStock("2330","測試",Market.TWSE,StockSector.SEMICONDUCTOR,last.open,last.high,last.low,last.close,0.0,1000,null,null)
            val full=RuleMetrics(calculator.build(stock,known));val fast=RuleMetrics(calculator.buildForRules(stock,known))
            for(type in RadarType.entries){
                ScanConditions.forRadar(type).forEach{rule->assertEquals("$end $type ${rule.id}",rule.test(full),rule.test(fast))}
                ExclusionConditions.forRadar(type).forEach{rule->assertEquals("$end $type exclude ${rule.id}",rule.test(full),rule.test(fast))}
            }
        }
    }
}
