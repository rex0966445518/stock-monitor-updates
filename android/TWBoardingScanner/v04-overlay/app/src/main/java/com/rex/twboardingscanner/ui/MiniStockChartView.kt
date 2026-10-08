package com.rex.twboardingscanner.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.rex.twboardingscanner.domain.ChartPoint
import com.rex.twboardingscanner.domain.ChartSeries
import com.rex.twboardingscanner.domain.DailyBar
import com.rex.twboardingscanner.domain.RuleMetrics
import kotlin.math.abs
import kotlin.math.max

class MiniStockChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0): View(context, attrs, defStyleAttr) {
    private var series = emptyList<ChartPoint>()
    private var count = 32
    private var selected = -1
    var detailed = false
    var showAxes = false
    var onSelected: ((ChartPoint) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(26,52,76) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(162,191,214) }
    private val density = resources.displayMetrics.density
    private val red = Color.rgb(255,73,108)
    private val green = Color.rgb(47,209,138)
    private fun dp(v: Float) = v * density
    fun setBars(input: List<DailyBar>) {
        series = ChartSeries.prepare(input)
        selected = series.lastIndex
        series.lastOrNull()?.let { onSelected?.invoke(it) }
        invalidate()
    }
    fun setWindow(days: Int) { count = days.coerceAtLeast(10); selected = series.lastIndex; series.lastOrNull()?.let { onSelected?.invoke(it) }; invalidate() }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.drawColor(Color.rgb(7,24,39))
        textPaint.textSize = (if (detailed || showAxes) 10 else 8) * resources.displayMetrics.scaledDensity
        textPaint.textAlign = Paint.Align.LEFT
        val points = series.takeLast(count)
        if (points.isEmpty()) { c.drawText("尚無日線資料", dp(10f), height/2f, textPaint); return }
        val left = dp(5f); val right = width - if (detailed || showAxes) dp(53f) else dp(3f)
        val h = height.toFloat(); val plotW = (right-left).coerceAtLeast(1f)
        val title = dp(if (detailed || showAxes) 19f else 13f)
        val priceTop = title + dp(7f); val priceBottom = h*.47f
        val volTop = h*.51f+title; val volBottom = h*.69f
        val macdTop = h*.73f+title; val macdBottom = h - dp(if (detailed || showAxes) 25f else 6f)
        if (priceBottom <= priceTop || macdBottom <= macdTop) return
        c.drawText("日 K",left,title,textPaint)
        c.drawText("成交量（張）",left,volTop-dp(4f),textPaint)
        c.drawText("MACD (12,26,9)",left,macdTop-dp(4f),textPaint)
        val allPrice = points.flatMap { listOfNotNull(it.bar.high,it.bar.low,it.ma5,it.ma10,it.ma20) }
        var maxP = allPrice.maxOrNull()!!; var minP = allPrice.minOrNull()!!
        val padding = max((maxP-minP)*.05,.01); maxP += padding; minP -= padding
        val range = maxP-minP
        fun py(v: Double) = priceBottom-((v-minP)/range*(priceBottom-priceTop)).toFloat()
        val maxV = max(1.0,points.maxOf { it.bar.volumeShares }.toDouble())
        val maxM = max(.01,points.flatMap { listOfNotNull(it.dif,it.dea,it.histogram) }.maxOfOrNull { abs(it) } ?: .01)
        val zero = (macdTop+macdBottom)/2
        fun my(v: Double) = zero-(v/maxM*(macdBottom-macdTop)*.44).toFloat()
        for (i in 0..3) {
            val y = priceTop+(priceBottom-priceTop)*i/3
            c.drawLine(left,y,right,y,grid)
            if (detailed || showAxes) c.drawText(String.format("%.2f",maxP-range*i/3),right+dp(3f),y+dp(3f),textPaint)
        }
        c.drawLine(left,volBottom,right,volBottom,grid)
        c.drawLine(left,zero,right,zero,grid)
        if (detailed || showAxes) {
            c.drawText(String.format("%.0f",maxV/1000),right+dp(3f),volTop+dp(9f),textPaint)
            c.drawText(String.format("%.2f",maxM),right+dp(3f),macdTop+dp(9f),textPaint)
            c.drawText("0",right+dp(3f),zero+dp(3f),textPaint)
            c.drawText(String.format("%.2f",-maxM),right+dp(3f),macdBottom,textPaint)
        }
        val step = plotW/points.size
        fun x(i: Int) = left+step*(i+.5f)
        points.forEachIndexed { i,p ->
            val b=p.bar; val xx=x(i)
            paint.color=if(b.close>=b.open) red else green;paint.strokeWidth=dp(1f)
            c.drawLine(xx,py(b.high),xx,py(b.low),paint)
            val y1=py(b.open);val y2=py(b.close)
            c.drawRect(xx-step*.3f,minOf(y1,y2),xx+step*.3f,maxOf(y1,y2)+dp(.6f),paint)
            val vh=(b.volumeShares/maxV*(volBottom-volTop)).toFloat()
            c.drawRect(xx-step*.3f,volBottom-vh,xx+step*.3f,volBottom,paint)
            p.histogram?.let {
                paint.color=if(it>=0) red else green
                c.drawRect(xx-step*.27f,minOf(zero,my(it)),xx+step*.27f,maxOf(zero,my(it))+dp(.5f),paint)
            }
        }
        fun line(color: Int, value: (ChartPoint)->Double?, y: (Double)->Float) {
            paint.color=color;paint.strokeWidth=dp(if(detailed || showAxes) 1.3f else .8f)
            for(i in 1 until points.size) {
                val a=value(points[i-1]);val b=value(points[i])
                if(a!=null && b!=null)c.drawLine(x(i-1),y(a),x(i),y(b),paint)
            }
        }
        line(Color.rgb(250,199,84),{it.ma5},::py)
        line(Color.rgb(66,187,255),{it.ma10},::py)
        line(Color.rgb(197,130,255),{it.ma20},::py)
        line(Color.rgb(49,212,232),{it.dif},::my)
        line(Color.rgb(240,166,66),{it.dea},::my)
        if(points.none { it.dif!=null }) c.drawText("MACD 資料不足30日",left,zero,textPaint)
        if(detailed || showAxes) {
            val from=series.size-points.size
            if(selected in from until series.size) {
                val xx=x(selected-from);paint.color=Color.argb(180,215,237,255);paint.strokeWidth=dp(.8f)
                c.drawLine(xx,priceTop,xx,macdBottom,paint)
            }
            c.drawText(RuleMetrics.tradingDate(points.first().bar.time).toString(),left,h-dp(5f),textPaint)
            textPaint.textAlign=Paint.Align.RIGHT
            c.drawText(RuleMetrics.tradingDate(points.last().bar.time).toString(),right,h-dp(5f),textPaint)
            textPaint.textAlign=Paint.Align.LEFT
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!detailed || series.isEmpty()) return super.onTouchEvent(event)
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val visible=minOf(count,series.size)
                val plot=(width-dp(58f)).coerceAtLeast(1f)
                val index=(((event.x-dp(5f))/plot)*visible).toInt().coerceIn(0,visible-1)
                selected=series.size-visible+index
                onSelected?.invoke(series[selected]);invalidate();return true
            }
            MotionEvent.ACTION_UP -> { parent?.requestDisallowInterceptTouchEvent(false); performClick(); return true }
            MotionEvent.ACTION_CANCEL -> { parent?.requestDisallowInterceptTouchEvent(false); return true }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick();return true }
}
