package com.rex.twboardingscanner.domain

/** Video 2987: daily OHLCV proxies, not evidence of a particular investor's holdings.
 * Initial fixed thresholds are deliberately explicit in labels and presets.
 * Caller supplies point-in-time completed bars, as for existing A conditions.
 */
object APattern {
    const val VERSION="20261017-video1"
    const val SETUP="a_video_setup"
    const val BREAKOUT="a_video_breakout"
    const val CONTINUE="a_video_continue"
    const val RETEST="a_video_retest"
    const val REENTRY="a_video_reentry"
    const val TREND="a_video_trend"
    const val CROSS="a_video_cross"
    const val QUALITY="a_video_quality"
    val IDS=listOf(SETUP,BREAKOUT,CONTINUE,RETEST,REENTRY,TREND,CROSS,QUALITY)
    private val common=setOf("price","volume","heat","macd")
    val PREPARE=common+setOf(SETUP,CROSS)
    val CONFIRMED=common+setOf(BREAKOUT)
    val PULLBACK=common+setOf(REENTRY)
    fun rules()=listOf(
        ScanCondition(SETUP,"影片A｜量縮整理：近5日均量≤此前20日60%；近20日振幅≤15%",true){it.aPattern.setup},
        ScanCondition(BREAKOUT,"影片A｜放量突破：此前量縮平台，收盤突破20日最高價0.5%；量≥1.5倍且收高",true){it.aPattern.breakout},
        ScanCondition(CONTINUE,"影片A｜突破延續：突破後連2日守住原平台且量≥原均量1.2倍",true){it.aPattern.continuation},
        ScanCondition(RETEST,"影片A｜縮量回踩：突破後1～10日觸及原平台±2%；均量≤突破日70%；收盤守99%",true){it.aPattern.retest},
        ScanCondition(REENTRY,"影片A｜回踩再轉強：先縮量守支撐，再收盤突破前日高點且5日線向上",true){it.aPattern.reentry},
        ScanCondition(TREND,"影片A｜均線順排：5日>10日>20日，三線皆高於3日前",true){it.aPattern.trend},
        ScanCondition(CROSS,"影片A｜近5日5日線上穿10日線，或10日線上穿20日線",true){it.aPattern.cross},
        ScanCondition(QUALITY,"影片A｜收盤位置≥70%，上影線占全日振幅≤30%（零振幅待查核）",true){it.aPattern.quality}
    )
    class Evidence(input:List<DailyBar>) {
        private val bars=input.takeLast(60)
        private val valid=bars.isNotEmpty()&&bars.all{b->
            listOf(b.open,b.high,b.low,b.close).all{it.isFinite()&&it>0}&&b.volumeShares>0&&
            b.high>=maxOf(b.open,b.close)&&b.low<=minOf(b.open,b.close)&&b.high>=b.low
        }&&bars.zipWithNext().all{(a,b)->a.time<b.time}
        private fun meanVolume(b:List<DailyBar>)=b.map{it.volumeShares.toDouble()}.average()
        private fun setupAt(end:Int):Boolean? {
            if(!valid||end<24)return null
            val recent=bars.subList(end-4,end+1)
            val prior=bars.subList(end-24,end-4)
            val platform=bars.subList(end-19,end+1)
            return meanVolume(recent)<=meanVolume(prior)*.6+1e-9&&
                platform.maxOf{it.high}/platform.minOf{it.low}-1<=.15+1e-9
        }
        private fun qualityAt(i:Int):Boolean? {
            if(!valid||i !in bars.indices)return null
            val b=bars[i];val range=b.high-b.low
            if(range<=0)return null
            return (b.close-b.low)/range>=.7-1e-9&&
                (b.high-maxOf(b.open,b.close))/range<=.3+1e-9
        }
        data class Event(val index:Int,val time:Long,val level:Double,val volume:Double,val baseline:Double)
        private fun eventAt(i:Int):Event? {
            if(i<25||setupAt(i-1)!=true||qualityAt(i)!=true)return null
            val prior=bars.subList(i-20,i);val level=prior.maxOf{it.high}
            val base=meanVolume(prior);val b=bars[i]
            return if(b.close>level*1.005&&b.volumeShares>=base*1.5)
                Event(i,b.time,level,b.volumeShares.toDouble(),base) else null
        }
        val setup:Boolean? by lazy{setupAt(bars.lastIndex)}
        val quality:Boolean? by lazy{qualityAt(bars.lastIndex)}
        val breakout:Boolean? by lazy{if(!valid||bars.size<26)null else eventAt(bars.lastIndex)!=null}
        // Latest qualifying historical breakout is immutable. Never re-anchor to a rolling high,
        // or fall back to an older event just because the latest event has failed.
        private val event:Event? by lazy{
            if(!valid)null else (bars.lastIndex-1 downTo maxOf(25,bars.lastIndex-10)).firstNotNullOfOrNull{eventAt(it)}
        }
        private fun held(e:Event,end:Int)=bars.subList(e.index+1,end+1).all{it.close>=e.level*.99}
        private fun pullback(e:Event,end:Int):Boolean {
            if(end<=e.index||end-e.index>10)return false
            val after=bars.subList(e.index+1,end+1)
            return held(e,end)&&after.any{it.low>=e.level*.98&&it.low<=e.level*1.02}&&
                meanVolume(after)<=e.volume*.7+1e-9
        }
        val continuation:Boolean? by lazy{
            if(!valid||bars.size<28)null else eventAt(bars.lastIndex-2)?.let{e->
                bars.takeLast(2).all{it.close>=e.level&&it.volumeShares>=e.baseline*1.2}
            }?:false
        }
        val retest:Boolean? by lazy{if(!valid||bars.size<27)null else event?.let{pullback(it,bars.lastIndex)}?:false}
        private fun ma(n:Int,end:Int):Double?=if(end<n-1)null else bars.subList(end-n+1,end+1).map{it.close}.average()
        val reentry:Boolean? by lazy{
            if(!valid||bars.size<28)null else event?.let{e->
                pullback(e,bars.lastIndex-1)&&held(e,bars.lastIndex)&&
                    bars.last().close>bars[bars.lastIndex-1].high&&
                    ma(5,bars.lastIndex)!!>ma(5,bars.lastIndex-1)!!
            }?:false
        }
        val trend:Boolean? by lazy{
            if(!valid||bars.size<23)null else {
                val i=bars.lastIndex;val a=ma(5,i)!!;val b=ma(10,i)!!;val c=ma(20,i)!!
                a>b&&b>c&&a>ma(5,i-3)!!&&b>ma(10,i-3)!!&&c>ma(20,i-3)!!
            }
        }
        val cross:Boolean? by lazy{
            if(!valid||bars.size<25)null else (bars.lastIndex-4..bars.lastIndex).any{i->
                (ma(5,i-1)!!<=ma(10,i-1)!!&&ma(5,i)!!>ma(10,i)!!)||
                (ma(10,i-1)!!<=ma(20,i-1)!!&&ma(10,i)!!>ma(20,i)!!)
            }
        }
        fun format():String {
            if(!valid||bars.size<26)return "影片A：有效日K不足或OHLCV異常，待查核；參數為待回測初始值"
            val e=eventAt(bars.lastIndex)?:event
            val anchor=e?.let{"突破日 ${RuleMetrics.tradingDate(it.time)}｜固定平台 ${it.level}｜突破量比 ${"%.2f".format(java.util.Locale.US,it.volume/it.baseline)}"}?:"近10日無符合的量縮平台突破"
            return "影片A｜$anchor\n量縮整理=$setup；突破=$breakout；延續=$continuation；縮量回踩=$retest；再轉強=$reentry；均線順排=$trend；近期交叉=$cross\n跌回固定平台99%下方即取消回踩資格；2%失效價僅供觀察，未變更自動賣出規則。籌碼鎖定無可靠資料，不推定主力持倉。"
        }
    }
    fun inspect(bars:List<DailyBar>)=Evidence(bars)
}
