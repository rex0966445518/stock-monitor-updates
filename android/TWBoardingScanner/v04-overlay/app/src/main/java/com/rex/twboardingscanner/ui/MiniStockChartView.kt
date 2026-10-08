package com.rex.twboardingscanner.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.rex.twboardingscanner.domain.DailyBar
import kotlin.math.max

class MiniStockChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
): View(context, attrs, defStyleAttr) {
    private var bars: List<DailyBar> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#18334F"); strokeWidth = 1f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8FAAC3"); textSize = 22f }

    fun setBars(v: List<DailyBar>) { bars = v.takeLast(32); invalidate() }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.drawColor(Color.parseColor("#071827"))
        if (bars.size < 8) {
            c.drawText("歷史資料累積中", 16f, height / 2f, text)
            return
        }
        val w = width.toFloat(); val h = height.toFloat()
        val priceTop = 24f; val priceBottom = h * .56f
        val volTop = h * .59f; val volBottom = h * .76f
        val macdTop = h * .80f; val macdBottom = h - 10f
        c.drawText("日K", 8f, 20f, text)
        c.drawText("量", 8f, volTop - 3f, text)
        c.drawText("MACD", 8f, macdTop - 3f, text)
        for (i in 1..3) c.drawLine(0f, priceTop + (priceBottom-priceTop)*i/4, w, priceTop + (priceBottom-priceTop)*i/4, grid)
        c.drawLine(0f, volBottom, w, volBottom, grid); c.drawLine(0f, macdTop, w, macdTop, grid)

        val maxP = bars.maxOf { it.high }; val minP = bars.minOf { it.low }; val rangeP = max(maxP-minP, 0.01)
        val maxV = max(bars.maxOf { it.volumeShares }.toDouble(), 1.0)
        val step = w / bars.size
        bars.forEachIndexed { i,b ->
            val x = step * i + step/2
            val up = b.close >= b.open
            paint.color = if (up) Color.parseColor("#FF496C") else Color.parseColor("#2FD18A")
            paint.strokeWidth = 2f
            fun py(v:Double)=priceBottom-((v-minP)/rangeP*(priceBottom-priceTop)).toFloat()
            c.drawLine(x, py(b.high), x, py(b.low), paint)
            val y1=py(b.open); val y2=py(b.close)
            c.drawRect(x-step*.27f, minOf(y1,y2), x+step*.27f, maxOf(y1,y2)+1f, paint)
            val vh=((b.volumeShares/maxV)*(volBottom-volTop)).toFloat()
            c.drawRect(x-step*.27f, volBottom-vh, x+step*.27f, volBottom, paint)
        }

        val closes=bars.map{it.close}; val e12=ema(closes,12); val e26=ema(closes,26)
        val dif=closes.indices.map{e12[it]-e26[it]}; val sig=ema(dif,9); val hist=dif.indices.map{dif[it]-sig[it]}
        val maxM=maxOf(0.01, (dif+sig+hist).maxOf{ kotlin.math.abs(it) })
        fun my(v:Double)=((macdTop+macdBottom)/2 - v/maxM*(macdBottom-macdTop)*.44).toFloat()
        paint.strokeWidth=2.2f
        for(i in 1 until bars.size){
            val x1=step*(i-1)+step/2; val x2=step*i+step/2
            paint.color=Color.parseColor("#31D4E8"); c.drawLine(x1,my(dif[i-1]),x2,my(dif[i]),paint)
            paint.color=Color.parseColor("#F0A642"); c.drawLine(x1,my(sig[i-1]),x2,my(sig[i]),paint)
        }
        val zero=(macdTop+macdBottom)/2
        hist.forEachIndexed{i,v->
            paint.color=if(v>=0) Color.parseColor("#FF496C") else Color.parseColor("#2FD18A")
            val x=step*i+step/2; c.drawRect(x-step*.22f, minOf(zero,my(v)), x+step*.22f, maxOf(zero,my(v)),paint)
        }
    }

    private fun ema(v: List<Double>, n:Int):List<Double>{
        if(v.isEmpty()) return emptyList(); val k=2.0/(n+1); var e=v.first(); val o=mutableListOf<Double>()
        v.forEachIndexed{i,x-> e=if(i==0)x else x*k+e*(1-k); o+=e}; return o
    }
}
