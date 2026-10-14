package com.rex.twboardingscanner.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.*

/** Native, resolution-independent illustration. Only progress is data-driven; motion is decorative. */
internal data class RobotScanFrame(val message:String,val done:Long?,val total:Long?,val eta:String){
    val percent:Int? get()=if(done!=null&&total!=null&&total>0)(done.toDouble()/total*100).toInt().coerceIn(0,100) else null
    companion object {
        fun read(status:String):RobotScanFrame {
            val lines=status.lines().filter{it.isNotBlank()}
            val message=lines.firstOrNull{!it.startsWith("本次執行：")&&!it.contains("預估剩餘") }?:"正在準備掃描…"
            val ratio=Regex("(\\d+)\\s*[/／]\\s*(\\d+)").find(message)
            val total=ratio?.groupValues?.get(2)?.toLongOrNull()?.takeIf{it>0}
            val done=ratio?.groupValues?.get(1)?.toLongOrNull()?.takeIf{total!=null&&it<=total}
            val eta=lines.lastOrNull{it.contains("預估剩餘")||it.contains("本階段完成")}?:"預估剩餘時間：估算中…"
            return RobotScanFrame(message,done,if(done==null)null else total,eta)
        }
    }
}

/** Robot, floating documents, scanning beam and striped progress are drawn in one lightweight View. */
class RobotScanView(c:Context):View(c){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val cyan=Color.rgb(73,237,250)
    private val white=Color.rgb(231,246,255)
    private val muted=Color.rgb(150,178,198)
    private val navy=Color.rgb(13,28,42)
    private var frame=RobotScanFrame.read("")
    private var phase=0f
    private var hostVisible=true
    internal var active=false;private set
    internal val percentage:Int? get()=frame.percent
    internal val animationRunning:Boolean get()=animator?.isRunning==true
    private var animator:ValueAnimator?=null
    init{tag="robot-scan-animation";visibility=GONE;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES}
    fun update(running:Boolean,message:String){
        active=running;frame=RobotScanFrame.read(message)
        visibility=if(running)VISIBLE else GONE
        contentDescription="機器人正在掃描文件。${frame.message}。"+(frame.percent?.let{"本階段進度 $it%。"}?:"進度估算中。")+frame.eta
        syncAnimation();invalidate()
    }
    fun foreground(visible:Boolean){hostVisible=visible;syncAnimation()}
    private fun syncAnimation(){
        if(active&&hostVisible&&isAttachedToWindow&&isShown&&windowVisibility==VISIBLE){
            if(animator==null)animator=ValueAnimator.ofFloat(0f,1f).apply{
                duration=4200;repeatCount=ValueAnimator.INFINITE;interpolator=LinearInterpolator()
                addUpdateListener{phase=it.animatedValue as Float;invalidate()}
            }
            if(animator?.isStarted!=true)animator?.start()
        }else animator?.cancel()
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();syncAnimation()}
    override fun onDetachedFromWindow(){animator?.cancel();super.onDetachedFromWindow()}
    override fun onWindowVisibilityChanged(visibility:Int){super.onWindowVisibilityChanged(visibility);syncAnimation()}
    override fun onVisibilityChanged(changedView:View,visibility:Int){super.onVisibilityChanged(changedView,visibility);syncAnimation()}
    override fun onLayout(changed:Boolean,left:Int,top:Int,right:Int,bottom:Int){
        super.onLayout(changed,left,top,right,bottom)
        // Attach/visibility callbacks may run before ancestors report this new panel as shown.
        syncAnimation()
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        val w=MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        setMeasuredDimension(w,resolveSize((w*1.12f).toInt(),heightMeasureSpec))
    }
    private fun fill(color:Int){paint.reset();paint.isAntiAlias=true;paint.color=color;paint.style=Paint.Style.FILL}
    private fun alpha(color:Int,a:Int)=Color.argb(a.coerceIn(0,255),Color.red(color),Color.green(color),Color.blue(color))
    private fun round(c:Canvas,x:Float,y:Float,w:Float,h:Float,r:Float,color:Int){fill(color);c.drawRoundRect(x,y,x+w,y+h,r,r,paint)}
    private fun gradient(c:Canvas,x:Float,y:Float,w:Float,h:Float,r:Float,top:Int,bottom:Int){
        fill(top);paint.shader=LinearGradient(x,y,x+w,y+h,top,bottom,Shader.TileMode.CLAMP)
        c.drawRoundRect(x,y,x+w,y+h,r,r,paint);paint.shader=null
    }
    private fun line(c:Canvas,x:Float,y:Float,x2:Float,y2:Float,color:Int,width:Float=1f){fill(color);paint.strokeWidth=width;paint.strokeCap=Paint.Cap.ROUND;c.drawLine(x,y,x2,y2,paint)}
    private fun text(c:Canvas,s:String,x:Float,y:Float,size:Float,color:Int=muted,bold:Boolean=false,maxWidth:Float=330f){
        fill(color);paint.textSize=size;paint.typeface=Typeface.create("sans-serif",if(bold)Typeface.BOLD else Typeface.NORMAL)
        val count=paint.breakText(s,true,maxWidth,null);c.drawText(if(count<s.length)s.take((count-1).coerceAtLeast(0))+"…" else s,x,y,paint)
    }
    private fun glow(c:Canvas,x:Float,y:Float,r:Float,color:Int){
        fill(color);paint.shader=RadialGradient(x,y,r,intArrayOf(alpha(color,110),alpha(color,0)),null,Shader.TileMode.CLAMP);c.drawCircle(x,y,r,paint);paint.shader=null
    }
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        val saved=canvas.save();canvas.scale(width/360f,height/403.2f)
        val c=canvas
        gradient(c,0f,0f,360f,403.2f,18f,Color.rgb(14,26,38),Color.rgb(7,14,24))
        fill(alpha(cyan,75));paint.style=Paint.Style.STROKE;paint.strokeWidth=1f;c.drawRoundRect(1f,1f,359f,402f,18f,18f,paint)
        round(c,16f,19f,5f,19f,1f,cyan)
        text(c,"正在掃描文件"+".".repeat((phase*4).toInt().coerceAtMost(3)),29f,36f,20f,white,true,245f)
        text(c,"AI 正在閱讀與解析，請稍候…",29f,56f,11f,muted)
        round(c,280f,20f,62f,22f,7f,alpha(cyan,17));text(c,"AI 分析中",287f,35f,10f,cyan,false,54f)
        // Orbital scan station and particles.
        glow(c,199f,199f,150f,cyan)
        fill(alpha(cyan,55));paint.style=Paint.Style.STROKE;paint.strokeWidth=1.3f
        c.drawOval(25f,275f,335f,309f,paint);c.drawOval(58f,282f,305f,300f,paint)
        for(i in 0..3){
            paint.color=alpha(cyan,65-i*10);paint.strokeWidth=if(i==0)2.2f else 1f
            val radius=78f+i*12f
            c.drawArc(181f-radius,188f-radius,181f+radius,188f+radius,phase*360+i*80f,110f,false,paint)
        }
        for(i in 0..22){
            val x=25f+((i*67f)%310);val y=80f+((i*43f+phase*105f)%213)
            val a=(40+120*(sin(phase*2*PI+i).toFloat()+1)/2).toInt()
            round(c,x,y,if(i%3==0)3f else 1.5f,if(i%3==0)3f else 1.5f,0f,alpha(cyan,a))
        }
        // Rear market document.
        c.save();c.rotate(8f,287f,217f)
        gradient(c,258f,146f,67f,128f,8f,Color.rgb(24,63,88),navy)
        text(c,"市場數據",264f,163f,9f,cyan,true,56f)
        for(i in 0..4)line(c,266f,176f+i*15,315f,176f+i*15,alpha(cyan,26))
        val graph=Path().apply{moveTo(266f,218f);lineTo(275f,208f);lineTo(283f,215f);lineTo(291f,194f);lineTo(301f,201f);lineTo(315f,178f)}
        fill(cyan);paint.style=Paint.Style.STROKE;paint.strokeWidth=1.4f;c.drawPath(graph,paint)
        for(i in 0..3)round(c,267f,231f+i*7,39f-i*6,2.5f,1f,alpha(cyan,70))
        c.restore()
        // Foreground floating report.
        val drift=sin(phase*2*PI).toFloat()*3f
        c.save();c.translate(0f,drift);c.rotate(7f,224f,208f)
        gradient(c,177f,118f,83f,162f,8f,Color.rgb(154,225,242),Color.rgb(30,104,136))
        fill(alpha(cyan,180));paint.style=Paint.Style.STROKE;paint.strokeWidth=1.4f;c.drawRoundRect(178f,119f,259f,279f,8f,8f,paint)
        text(c,"歷史資料分析",185f,142f,9f,Color.rgb(15,57,80),true,71f)
        for(i in 0..8)round(c,187f,154f+i*8,if(i%3==0)54f else 44f,3f,1f,alpha(navy,70))
        for(i in 0..4)round(c,190f+i*10,255f-i*5,6f,9f+i*5,1f,alpha(navy,120))
        val scanY=149f+phase*110f
        glow(c,217f,scanY,48f,cyan)
        line(c,174f,scanY,264f,scanY,alpha(cyan,40),10f);line(c,174f,scanY,264f,scanY,cyan,2f)
        // Scan corners.
        for(x in listOf(174f,265f)){val sign=if(x<200)1 else -1;line(c,x,112f,x+sign*9f,112f,cyan,2f);line(c,x,112f,x,121f,cyan,2f)}
        c.restore()
        // Cute floating robot, with reflective shell and blinking cyan eyes.
        c.save();c.translate(0f,-drift)
        glow(c,104f,281f,39f,cyan)
        line(c,105f,269f,105f,287f,alpha(cyan,75),23f)
        gradient(c,68f,221f,71f,50f,22f,Color.rgb(217,235,250),Color.rgb(45,91,121))
        round(c,86f,232f,37f,23f,8f,navy);text(c,"AI",97f,250f,15f,cyan,true,24f)
        gradient(c,54f,225f,23f,40f,12f,white,Color.rgb(60,107,137))
        gradient(c,125f,228f,34f,22f,10f,white,Color.rgb(48,100,134))
        c.save();c.rotate(-15f,150f,245f);gradient(c,143f,227f,30f,43f,5f,Color.rgb(43,176,205),navy)
        for(i in 0..3)line(c,150f,236f+i*7,165f,236f+i*7,alpha(cyan,130));c.restore()
        gradient(c,58f,131f,94f,94f,40f,Color.rgb(236,248,255),Color.rgb(70,111,148))
        gradient(c,49f,153f,28f,44f,14f,Color.rgb(102,171,206),navy)
        fill(cyan);paint.style=Paint.Style.STROKE;paint.strokeWidth=2.5f;c.drawOval(54f,161f,68f,189f,paint)
        gradient(c,77f,155f,70f,49f,22f,Color.rgb(8,37,62),Color.rgb(2,10,22))
        fill(alpha(cyan,90));paint.style=Paint.Style.STROKE;paint.strokeWidth=.8f;c.drawRoundRect(77f,155f,147f,204f,22f,22f,paint)
        val blinking=phase>.82f&&phase<.87f
        for(x in listOf(98f,127f)){
            glow(c,x,180f,12f,cyan);fill(cyan);paint.style=Paint.Style.STROKE;paint.strokeWidth=3f;paint.strokeCap=Paint.Cap.ROUND
            if(blinking)c.drawLine(x-5,181f,x+5,181f,paint) else c.drawArc(x-5,174f,x+5,185f,185f,170f,false,paint)
        }
        line(c,107f,191f,117f,192f,alpha(cyan,130),1f)
        gradient(c,83f,135f,37f,12f,6f,Color.rgb(21,82,119),navy);round(c,95f,136f,9f,3f,1f,cyan)
        line(c,133f,148f,145f,156f,alpha(white,150),2f)
        c.restore()
        // Honest per-stage progress; unknown totals show an animated segment.
        round(c,15f,316f,330f,71f,12f,Color.rgb(12,28,41))
        text(c,"本階段進度",26f,336f,11f,white)
        val percent=frame.percent
        text(c,percent?.let{"$it%"}?:"估算中",98f,337f,19f,cyan,true,95f)
        text(c,if(frame.total!=null)"${frame.done} / ${frame.total} 項" else "正在準備資料",205f,336f,10f,muted,false,125f)
        round(c,26f,346f,307f,15f,7f,Color.rgb(18,58,73))
        val bar=c.save();c.clipPath(Path().apply{addRoundRect(RectF(28f,348f,331f,359f),5f,5f,Path.Direction.CW)})
        val barWidth=percent?.let{303f*it/100}?:86f
        val barX=if(percent!=null)28f else 28f+(303f-86f)*(sin(phase*2*PI).toFloat()+1)/2
        gradient(c,barX,348f,barWidth,11f,5f,Color.rgb(26,153,204),cyan)
        c.clipRect(barX,348f,barX+barWidth,359f)
        for(i in -3..32)line(c,barX+i*12+phase*12,347f,barX+i*12+phase*12-8,361f,alpha(white,60),4f)
        c.restoreToCount(bar)
        text(c,frame.eta,26f,378f,10f,muted,false,305f)
        text(c,frame.message,16f,398f,9f,muted,false,327f)
        c.restoreToCount(saved)
    }
}

/** In-page controls stay available while the illustration loops. */
class RobotScanPanel(c:Context,stop:()->Unit):android.widget.LinearLayout(c){
    internal val illustration=RobotScanView(c)
    init{
        orientation=VERTICAL;visibility=GONE;tag="robot-scan-panel"
        addView(illustration,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT))
        addView(NeonUi.button(c,"停止掃描",NeonUi.cyan,stop).apply{tag="robot-scan-stop"})
    }
    fun update(running:Boolean,message:String){
        visibility=if(running)VISIBLE else GONE;illustration.update(running,message)
    }
    fun foreground(visible:Boolean)=illustration.foreground(visible)
}
