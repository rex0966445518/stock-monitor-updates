package com.rex.twboardingscanner.ui

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Small chart with a truthful zero baseline; no invented bars when data is missing. */
class MiniBarChartView(context:Context):View(context) {
    data class Entry(val label:String,val value:Double,val display:String)
    var entries:List<Entry> = emptyList()
        set(value) { field=value;contentDescription=value.joinToString("；"){"${it.label} ${it.display}"};invalidate() }
    var emptyMessage="資料不足，待查核"
    var accent=NeonUi.cyan
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val d=resources.displayMetrics.density
        paint.typeface=Typeface.DEFAULT;paint.textAlign=Paint.Align.CENTER
        paint.textSize=11*d*resources.configuration.fontScale.coerceAtMost(1.3f)
        if(entries.isEmpty()) {
            paint.color=NeonUi.muted;canvas.drawText(emptyMessage,width/2f,height/2f,paint);return
        }
        val top=26*d;val bottom=height-40*d
        val high=max(0.0,entries.maxOf{it.value});val low=min(0.0,entries.minOf{it.value})
        val range=(high-low).takeIf{it>0}?:1.0
        fun y(v:Double)=bottom-((v-low)/range*(bottom-top)).toFloat()
        val zero=y(0.0)
        paint.color=Color.rgb(34,60,81);paint.strokeWidth=d
        canvas.drawLine(8*d,zero,width-8*d,zero,paint)
        val col=width.toFloat()/entries.size
        entries.forEachIndexed { i,e ->
            val x=col*(i+0.5f);val tip=y(e.value);val color=if(e.value<0) NeonUi.amber else accent
            val barWidth=min(34*d,col*0.48f)
            paint.shader=LinearGradient(0f,top,0f,bottom,intArrayOf(color,Color.argb(65,Color.red(color),Color.green(color),Color.blue(color))),null,Shader.TileMode.CLAMP)
            canvas.drawRoundRect(RectF(x-barWidth/2,min(zero,tip),x+barWidth/2,max(zero,tip).coerceAtLeast(min(zero,tip)+d)),4*d,4*d,paint)
            paint.shader=null;paint.color=color;paint.typeface=Typeface.DEFAULT_BOLD
            val desired=11*d*resources.configuration.fontScale.coerceAtMost(1.3f)
            paint.textSize=desired
            if(paint.measureText(e.display)>col-6*d)paint.textSize=desired*(col-6*d)/paint.measureText(e.display)
            canvas.drawText(e.display,x,if(e.value>=0)tip-7*d else tip+14*d,paint)
            paint.typeface=Typeface.DEFAULT;paint.textSize=10*d;paint.color=NeonUi.muted
            canvas.drawText(e.label,x,height-4*d,paint)
        }
    }
}
