package com.rex.twboardingscanner.ui

import android.graphics.*
import android.graphics.drawable.Drawable
import com.rex.twboardingscanner.domain.RadarType

/** Small resolution-independent category icons; labels remain the source of meaning. */
internal class RadarGlyph(private val radar:RadarType):Drawable(){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=NeonUi.radarColor(radar);strokeWidth=1.8f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    override fun draw(c:Canvas){
        c.save();c.translate(bounds.left.toFloat(),bounds.top.toFloat());c.scale(bounds.width()/24f,bounds.height()/24f)
        fun line(vararg xy:Float){paint.style=Paint.Style.STROKE;val p=Path();p.moveTo(xy[0],xy[1]);for(i in 2 until xy.size step 2)p.lineTo(xy[i],xy[i+1]);c.drawPath(p,paint)}
        when(radar){
            RadarType.A_EARLY_BREAKOUT->{line(3f,18f,9f,12f,13f,14f,21f,4f);line(15f,4f,21f,4f,21f,10f)}
            RadarType.B_DEEP_REVERSAL->{line(3f,6f,8f,18f,13f,18f,20f,5f);line(14f,5f,20f,5f,21f,11f)}
            RadarType.C_LONG_RED_VOLUME->{paint.style=Paint.Style.FILL;c.drawRoundRect(3f,13f,7f,21f,1f,1f,paint);c.drawRoundRect(10f,4f,14f,21f,1f,1f,paint);c.drawRoundRect(17f,9f,21f,21f,1f,1f,paint)}
            RadarType.D_MAIN_FORCE->{paint.style=Paint.Style.STROKE;c.drawCircle(12f,6f,3f,paint);c.drawArc(5f,12f,19f,26f,180f,180f,false,paint);line(3f,10f,3f,18f);line(21f,10f,21f,18f)}
            RadarType.E_DIF_TOUCH->{line(3f,19f,8f,16f,12f,10f,17f,6f,21f,4f);line(3f,10f,8f,10f,12f,9f,17f,8f,21f,7f)}
            RadarType.F_VOLUME_CONTRACTION->{line(3f,11f,9f,8f,14f,9f,21f,3f);line(17f,3f,21f,3f,21f,7f);line(5f,15f,5f,21f);line(12f,17f,12f,21f);line(19f,19f,19f,21f)}
            RadarType.G_MA5_WAVE->{line(2f,16f,7f,7f,11f,12f,15f,8f,22f,3f);line(2f,20f,22f,20f)}
        }
        c.restore()
    }
    override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()}
    override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
