package com.rex.twboardingscanner.ui

import android.graphics.*
import android.graphics.drawable.Drawable

/**
 * Shared static mountain + candlestick wallpaper for ALL internal pages.
 * Restrained enough to leave all interactive TextViews fully legible.
 * No bitmap allocation or animation; safe behind ScrollViews and dialogs.
 */
internal class NeonBackdrop(private val density:Float):Drawable(){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas:Canvas){
        val w=bounds.width().toFloat()
        val h=bounds.height().toFloat()
        if(w<=0f||h<=0f)return
        canvas.save()
        canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
        paint.style=Paint.Style.FILL
        paint.shader=LinearGradient(0f,0f,0f,h,
            intArrayOf(Color.rgb(24,5,46),Color.rgb(13,4,33),NeonUi.canvas),
            floatArrayOf(0f,.43f,1f),Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,w,h,paint)
        paint.shader=null

        // Vignetted mountain contours stay in the top header and never cover card content.
        val mountain=Path().apply{
            moveTo(w*.19f,162*density)
            lineTo(w*.41f,67*density)
            lineTo(w*.48f,16*density)
            lineTo(w*.55f,78*density)
            lineTo(w*.65f,42*density)
            lineTo(w*.82f,151*density)
            close()
        }
        paint.shader=LinearGradient(w*.48f,10*density,w*.72f,182*density,
            intArrayOf(Color.rgb(83,24,147),Color.rgb(22,7,52)),null,Shader.TileMode.CLAMP)
        canvas.drawPath(mountain,paint)
        paint.shader=null
        paint.style=Paint.Style.STROKE
        paint.strokeWidth=.95f*density
        paint.color=Color.argb(75,202,88,255)
        canvas.drawPath(Path().apply{
            moveTo(w*.48f,16*density);lineTo(w*.50f,62*density)
            lineTo(w*.56f,77*density);lineTo(w*.65f,42*density)
        },paint)

        // Decorative market candles are limited to the right side of the title band.
        val values=floatArrayOf(.76f,.66f,.72f,.58f,.60f,.49f,.57f,.45f,.37f,.44f,.33f,.26f)
        for(i in values.indices){
            val x=w*(.67f+i*.027f)
            val y=(66f+values[i]*80f)*density
            paint.color=if(i%4==2)Color.argb(100,83,142,255) else Color.argb(116,244,62,223)
            paint.strokeWidth=.9f*density
            canvas.drawLine(x,y-7*density,x,y+9*density,paint)
            paint.style=Paint.Style.FILL
            canvas.drawRect(x-1.8f*density,y-3.5f*density,x+1.8f*density,y+4*density,paint)
            paint.style=Paint.Style.STROKE
        }

        // Thin purple frame in lieu of the old red/orange radar outline.
        val inset=3f*density
        val round=19f*density
        paint.strokeWidth=1.05f*density
        paint.color=Color.argb(100,181,65,255)
        canvas.drawRoundRect(inset,inset,w-inset,h-inset,round,round,paint)
        paint.style=Paint.Style.FILL
        canvas.restore()
    }
    override fun setAlpha(alpha:Int){}
    override fun setColorFilter(colorFilter:ColorFilter?){}
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.OPAQUE
}
