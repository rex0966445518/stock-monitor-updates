package com.rex.twboardingscanner.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View
import com.rex.twboardingscanner.data.FiveMinuteBar
import com.rex.twboardingscanner.domain.RuleMetrics
import java.time.Instant
import java.time.LocalTime
import java.util.Locale
import kotlin.math.max

/** One trading day's REAL 5-minute OHLCV. Colored markers describe MODEL fills, not actual ticks. */
internal class BacktestFiveMinuteChartView(context:Context):View(context){
    private var bars=emptyList<FiveMinuteBar>()
    private var side="BUY"
    private var time=""
    private var fillPrice:Double?=null
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val labels=Paint(Paint.ANTI_ALIAS_FLAG).apply{
        color=Color.rgb(163,189,211)
        textSize=10f*resources.displayMetrics.scaledDensity
    }
    private val grid=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(35,60,81);strokeWidth=1f}
    private val up=Color.rgb(255,86,119)
    private val down=Color.rgb(51,206,147)
    private val accent=Color.rgb(251,197,89)
    private fun dp(x:Float)=x*resources.displayMetrics.density
    companion object{
        /** No exact marker for backtest sales described only as "09:00–13:30". */
        fun markerIndex(bars:List<FiveMinuteBar>,side:String,rawTime:String):Int?{
            val time=rawTime.substringBefore("（").substringBefore("(").trim()
            val index=when{
                side=="BUY"&&time=="13:30"->bars.indexOfLast {
                    Instant.ofEpochMilli(it.time).atZone(RuleMetrics.TAIPEI).toLocalTime()<=LocalTime.of(13,30)
                }
                side=="SELL"&&time=="09:00"->bars.indexOfFirst {
                    Instant.ofEpochMilli(it.time).atZone(RuleMetrics.TAIPEI).toLocalTime()>=LocalTime.of(9,0)
                }
                else->-1
            }
            return index.takeIf{it>=0}
        }
    }
    fun setData(input:List<FiveMinuteBar>,tradeSide:String,tradeTime:String,modelPrice:Double?){
        bars=input.sortedBy{it.time}
        side=tradeSide;time=tradeTime;fillPrice=modelPrice?.takeIf{it.isFinite()&&it>0}
        contentDescription="${bars.size} 根真實五分鐘K線；${if(side=="BUY")"買入" else "賣出"}模型價 ${fillPrice?.let{String.format(Locale.TAIWAN,"%.2f",it)}?:"未記錄"}；${if(markerIndex(bars,side,time)!=null)"黃色標記是模擬時段，不是逐筆成交" else "盤中成交時刻未知，不標記精確時點"}"
        invalidate()
    }
    override fun onDraw(c:Canvas){
        super.onDraw(c)
        c.drawColor(Color.rgb(7,23,38))
        if(bars.isEmpty())return
        val w=width.toFloat();val h=height.toFloat()
        val left=dp(8f);val right=(w-dp(46f)).coerceAtLeast(left+dp(10f))
        val top=dp(12f);val priceBottom=h*.68f;val volumeTop=h*.75f;val volumeBottom=h-dp(19f)
        if(priceBottom<=top||volumeBottom<=volumeTop)return
        val prices=bars.flatMap{listOf(it.low,it.high)}+listOfNotNull(fillPrice)
        var low=prices.minOrNull()?:return;var high=prices.maxOrNull()?:return
        val margin=max((high-low)*.07,.02);low-=margin;high+=margin
        fun py(value:Double)=priceBottom-((value-low)/(high-low)*(priceBottom-top)).toFloat()
        val maxVolume=max(1.0,bars.maxOf{it.volumeShares}.toDouble())
        val step=(right-left)/bars.size
        fun x(i:Int)=left+step*(i+.5f)
        repeat(4){i->
            val y=top+(priceBottom-top)*i/3f
            c.drawLine(left,y,right,y,grid)
            labels.textAlign=Paint.Align.LEFT
            c.drawText(String.format(Locale.TAIWAN,"%.2f",high-(high-low)*i/3),right+dp(4f),y+dp(4f),labels)
        }
        c.drawLine(left,volumeBottom,right,volumeBottom,grid)
        bars.forEachIndexed{i,b->
            val cx=x(i);val candleColor=if(b.close>=b.open)up else down
            paint.color=candleColor;paint.strokeWidth=dp(1f)
            c.drawLine(cx,py(b.high),cx,py(b.low),paint)
            val a=py(b.open);val z=py(b.close);val half=(step*.28f).coerceAtLeast(dp(.45f))
            c.drawRect(cx-half,minOf(a,z),cx+half,maxOf(a,z)+dp(.8f),paint)
            val volumeH=(b.volumeShares/maxVolume*(volumeBottom-volumeTop)).toFloat()
            c.drawRect(cx-half,volumeBottom-volumeH,cx+half,volumeBottom,paint)
        }
        // The dashed horizontal line is the simulated fill price, NOT an observed transaction.
        fillPrice?.let{v->
            val y=py(v)
            paint.color=accent;paint.strokeWidth=dp(1f)
            paint.pathEffect=DashPathEffect(floatArrayOf(dp(5f),dp(4f)),0f)
            c.drawLine(left,y,right,y,paint);paint.pathEffect=null
        }
        markerIndex(bars,side,time)?.let{idx->
            paint.color=accent;paint.strokeWidth=dp(1.5f)
            c.drawLine(x(idx),top,x(idx),volumeBottom,paint)
        }
        labels.textAlign=Paint.Align.LEFT
        c.drawText("09:00",left,h-dp(4f),labels)
        c.drawText("量",left,volumeTop-dp(3f),labels)
        labels.textAlign=Paint.Align.CENTER
        c.drawText("11:00",(left+right)/2,h-dp(4f),labels)
        labels.textAlign=Paint.Align.RIGHT
        c.drawText("13:30",right,h-dp(4f),labels)
        labels.textAlign=Paint.Align.LEFT
    }
}
