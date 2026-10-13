package com.rex.twboardingscanner.ui

import android.graphics.*
import android.graphics.drawable.Drawable

/** Static edge lighting. No animated shaders or bitmap backgrounds behind readable content. */
internal class NeonBackdrop(private val density:Float):Drawable(){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas:Canvas){
        val w=bounds.width().toFloat();val h=bounds.height().toFloat()
        if(w<=0||h<=0)return
        canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
        paint.style=Paint.Style.FILL
        paint.shader=LinearGradient(0f,0f,w,180*density,intArrayOf(Color.rgb(39,15,24),NeonUi.canvas),null,Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,w,h,paint);paint.shader=null
        val inset=5*density;val cut=18*density
        val edge=Path().apply{moveTo(inset,70*density);lineTo(inset,cut);lineTo(cut,inset);lineTo(w*.32f,inset)}
        val right=Path().apply{moveTo(w-inset,120*density);lineTo(w-inset,cut);lineTo(w-cut,inset);lineTo(w*.76f,inset)}
        paint.style=Paint.Style.STROKE
        for(width in listOf(7f,3f,1f)){
            paint.strokeWidth=width*density
            paint.color=when(width){7f->Color.argb(15,255,55,82);3f->Color.argb(35,255,55,82);else->Color.argb(170,255,55,82)}
            canvas.drawPath(edge,paint);canvas.drawPath(right,paint)
        }
        paint.strokeWidth=density;paint.color=Color.argb(65,255,55,82)
        canvas.drawLine(inset,140*density,inset,h-24*density,paint)
        canvas.drawLine(w-inset,160*density,w-inset,h-24*density,paint)
        for(i in 0..2){val x=w-22*density-i*11*density;canvas.drawLine(x,h-8*density,x+6*density,h-14*density,paint)}
        canvas.restore()
    }
    override fun setAlpha(alpha:Int){}
    override fun setColorFilter(colorFilter:ColorFilter?){}
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.OPAQUE
}
