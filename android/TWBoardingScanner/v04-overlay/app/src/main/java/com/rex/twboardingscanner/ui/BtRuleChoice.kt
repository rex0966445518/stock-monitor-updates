package com.rex.twboardingscanner.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.core.view.ViewCompat

/** Immediate single-tap feedback; a second tap on this same row locks the condition off. */
internal class BtRuleChoice(c:Context,private val title:String,initial:Choice,private val changed:(Choice)->Unit):AppCompatCheckBox(c){
    enum class Choice(val label:String){FREE("自由調整"),REQUIRED("必選"),EXCLUDED("排除")}
    var choice=initial;private set
    private var lastTap:Long?=null
    private val marker=Marker()
    init {
        text=title;textSize=13f;minHeight=NeonUi.dp(c,48);compoundDrawablePadding=NeonUi.dp(c,8)
        supportButtonTintList=null;buttonTintList=null;buttonDrawable=marker
        setPadding(NeonUi.dp(c,2),NeonUi.dp(c,6),NeonUi.dp(c,2),NeonUi.dp(c,6))
        render()
        setOnClickListener{
            val now=SystemClock.uptimeMillis()
            val double=lastTap?.let{now-it in 0..ViewConfiguration.getDoubleTapTimeout().toLong()}?:false
            lastTap=if(double)null else now
            select(if(double)Choice.EXCLUDED else if(choice==Choice.FREE)Choice.REQUIRED else Choice.FREE)
        }
        // An explicit alternative for people who cannot comfortably perform a quick double tap.
        setOnLongClickListener{lastTap=null;select(Choice.EXCLUDED);true}
    }
    override fun toggle(){ /* The three-state model, not CompoundButton's two-state toggle, owns changes. */ }
    private fun select(next:Choice){choice=next;render();changed(next)}
    private fun render(){
        isChecked=choice==Choice.REQUIRED
        setTextColor(if(choice==Choice.EXCLUDED)NeonUi.pink else NeonUi.ink)
        ViewCompat.setStateDescription(this,choice.label)
        contentDescription="$title，${choice.label}"
        marker.invalidateSelf();invalidate()
    }
    private inner class Marker:Drawable(){
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
        override fun getIntrinsicWidth()=NeonUi.dp(context,30)
        override fun getIntrinsicHeight()=NeonUi.dp(context,26)
        override fun draw(canvas:Canvas){
            val size=NeonUi.dp(context,22).toFloat();val left=bounds.left+NeonUi.dp(context,2);val top=bounds.top+(bounds.height()-size)/2
            val color=when(choice){Choice.FREE->NeonUi.muted;Choice.REQUIRED->NeonUi.mint;Choice.EXCLUDED->NeonUi.pink}
            paint.color=color;paint.strokeWidth=NeonUi.dp(context,2).toFloat();val r=NeonUi.dp(context,3).toFloat()
            canvas.drawRoundRect(left.toFloat(),top,left+size,top+size,r,r,paint)
            fun x(f:Float)=left+size*f
            fun y(f:Float)=top+size*f
            when(choice){
                Choice.REQUIRED->{val path=Path();path.moveTo(x(.2f),y(.52f));path.lineTo(x(.43f),y(.75f));path.lineTo(x(.82f),y(.24f));canvas.drawPath(path,paint)}
                Choice.EXCLUDED->{canvas.drawLine(x(.26f),y(.26f),x(.74f),y(.74f),paint);canvas.drawLine(x(.26f),y(.74f),x(.74f),y(.26f),paint)}
                Choice.FREE->Unit
            }
        }
        override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()}
        override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
        @Deprecated("Drawable opacity") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
}
