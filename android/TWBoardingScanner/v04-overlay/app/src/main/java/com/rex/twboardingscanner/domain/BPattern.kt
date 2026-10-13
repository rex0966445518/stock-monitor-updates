package com.rex.twboardingscanner.domain

import org.json.JSONObject

data class BPatternConfig(val depthDays:Int=252,val depthPct:Double=30.0,
    val stopDays:Int=3,val supportDays:Int=20,val momentumDays:Int=2,
    val eventDays:Int=3,val rsiLookback:Int=10,val rsiThreshold:Double=35.0,
    val breakoutDays:Int=10,val volumeDays:Int=20,val volumeMultiple:Double=1.5){
    fun validated():BPatternConfig{
        require(depthDays in 20..504&&depthPct.isFinite()&&depthPct in 5.0..90.0){"B 深跌範圍須20～504日，跌幅5～90%"}
        require(stopDays in 1..10&&supportDays in 5..120){"B 不破底日數1～10，支撐觀察5～120日"}
        require(momentumDays in 1..5&&eventDays in 1..10&&rsiLookback in 2..60){"B 動能1～5日；事件1～10日；RSI觀察2～60日"}
        require(rsiThreshold.isFinite()&&rsiThreshold in 30.0..50.0){"B RSI回升門檻30～50"}
        require(breakoutDays in 3..60&&volumeDays in 5..120&&volumeMultiple.isFinite()&&volumeMultiple in 1.0..5.0){"B 突破3～60日；均量5～120日；量比1～5"}
        return this
    }
    fun json()=JSONObject().put("depthDays",depthDays).put("depthPct",depthPct).put("stopDays",stopDays).put("supportDays",supportDays)
        .put("momentumDays",momentumDays).put("eventDays",eventDays).put("rsiLookback",rsiLookback).put("rsiThreshold",rsiThreshold)
        .put("breakoutDays",breakoutDays).put("volumeDays",volumeDays).put("volumeMultiple",volumeMultiple)
    fun describe()="B 深跌 $depthDays 日／$depthPct%；$stopDays 日不破此前 $supportDays 日低點；MACD連升 $momentumDays 日；近 $eventDays 日反轉事件；RSI(14)近 $rsiLookback 日曾低於30、回升突破$rsiThreshold；突破此前 $breakoutDays 日高點、量達此前 $volumeDays 日均量 $volumeMultiple 倍"
    companion object{
        val DEFAULT=BPatternConfig()
        fun read(o:JSONObject?):BPatternConfig=if(o==null)DEFAULT else BPatternConfig(o.getInt("depthDays"),o.getDouble("depthPct"),
            o.getInt("stopDays"),o.getInt("supportDays"),o.getInt("momentumDays"),o.getInt("eventDays"),o.getInt("rsiLookback"),
            o.getDouble("rsiThreshold"),o.getInt("breakoutDays"),o.getInt("volumeDays"),o.getDouble("volumeMultiple")).validated()
    }
}
object BPattern{
    const val DEPTH="b_depth"
    const val STOP="b_stop"
    const val MOMENTUM="b_momentum"
    const val RSI="b_rsi_recover"
    const val BREAKOUT="b_breakout_volume"
    const val EVENT="b_reversal_event"
    val IDS=listOf(DEPTH,STOP,MOMENTUM,RSI,BREAKOUT,EVENT)
    val EARLY=setOf("price","volume","heat","ma5up","ma5",DEPTH,STOP,MOMENTUM,EVENT)
    val CONFIRMED=setOf("price","volume","heat","ma5up","ma5",DEPTH,STOP,MOMENTUM,BREAKOUT)
    fun rules()=listOf(
        ScanCondition(DEPTH,"深跌幅度：觀察日數／距最高收盤價跌幅可填"){it.bPattern.depth},
        ScanCondition(STOP,"止跌：最近數日最低價皆未跌破此前支撐低點（日數可填）"){it.bPattern.stop},
        ScanCondition(MOMENTUM,"MACD起轉：DIF與柱狀體連續升高（日數可填）"){it.bPattern.momentum},
        ScanCondition(RSI,"RSI(14)脫離超賣：曾低於30，近期向上突破回升門檻（數值可填）",true){it.bPattern.rsiRecover},
        ScanCondition(BREAKOUT,"放量突破：收盤突破此前高點，成交量達此前均量指定倍數（可填）",true){it.bPattern.breakout},
        ScanCondition(EVENT,"反轉觸發任一成立：RSI脫離超賣／近期MACD金叉／放量突破"){it.bPattern.event}
    )
    class Evidence(private val bars:List<DailyBar>,val config:BPatternConfig){
        init{config.validated()}
        private val closes=bars.map{it.close}
        private val valid=bars.isNotEmpty()&&bars.zipWithNext().all{(a,b)->a.time<b.time}&&bars.all{
            it.close.isFinite()&&it.close>0&&it.low.isFinite()&&it.low>0&&it.high.isFinite()&&it.high>=it.close&&it.low<=it.close&&it.high>=it.low&&it.volumeShares>=0}
        private val macds=mutableMapOf<Int,TechnicalCalculator.MacdState?>()
        private fun macdAt(skip:Int)=macds.getOrPut(skip){TechnicalCalculator().macd(closes.dropLast(skip))}
        val depth:Boolean? by lazy{
            if(!valid||bars.size<config.depthDays)null else closes.last()<=closes.takeLast(config.depthDays).maxOrNull()!!*(1-config.depthPct/100)
        }
        val stop:Boolean? by lazy{
            if(!valid||bars.size<config.stopDays+config.supportDays)null else{
                val support=bars.dropLast(config.stopDays).takeLast(config.supportDays).minOf{it.low}
                bars.takeLast(config.stopDays).all{it.low>=support}
            }
        }
        val momentum:Boolean? by lazy{
            if(!valid||bars.size<30+config.momentumDays)null else{
                val series=(config.momentumDays downTo 0).map{macdAt(it)!!}
                series.zipWithNext().all{(a,b)->b.dif>a.dif&&b.hist>a.hist}
            }
        }
        val rsiRecover:Boolean? by lazy{
            if(!valid||bars.size<15+config.rsiLookback+config.eventDays)null else{
                val values=RsiGoldenCross.values(closes,14)
                (0 until config.eventDays).any{skip->
                    val i=values.lastIndex-skip
                    values[i-1]<=config.rsiThreshold&&values[i]>config.rsiThreshold&&
                        values.subList(i-config.rsiLookback,i).any{it<30}
                }&&values.last()>config.rsiThreshold
            }
        }
        val macdCross:Boolean? by lazy{
            if(!valid||bars.size<30+config.eventDays)null else{
                val series=(config.eventDays downTo 0).map{macdAt(it)!!}
                series.zipWithNext().any{(a,b)->a.dif<=a.signal&&b.dif>b.signal}&&series.last().dif>series.last().signal
            }
        }
        val breakout:Boolean? by lazy{
            if(!valid||bars.size<=maxOf(config.breakoutDays,config.volumeDays))null else{
                val prior=bars.dropLast(1)
                val average=prior.takeLast(config.volumeDays).map{it.volumeShares.toDouble()}.average()
                if(average<=0)null else bars.last().close>prior.takeLast(config.breakoutDays).maxOf{it.high}&&
                    bars.last().volumeShares>=average*config.volumeMultiple
            }
        }
        val event:Boolean? by lazy{
            val results=listOf(rsiRecover,macdCross,breakout)
            if(results.any{it==true})true else if(results.any{it==null})null else false
        }
    }
    fun inspect(bars:List<DailyBar>,config:BPatternConfig)=Evidence(bars,config)
}
