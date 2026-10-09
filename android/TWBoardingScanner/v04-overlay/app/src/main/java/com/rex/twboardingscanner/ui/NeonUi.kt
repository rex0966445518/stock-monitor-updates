package com.rex.twboardingscanner.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton

internal object NeonUi {
    val ink=Color.rgb(235,245,255)
    val muted=Color.rgb(135,166,193)
    val cyan=Color.rgb(77,210,250)
    val pink=Color.rgb(255,83,125)
    val mint=Color.rgb(92,231,192)
    val amber=Color.rgb(255,188,91)
    val blue=Color.rgb(56,129,212)
    fun dp(c:Context,n:Int)=(c.resources.displayMetrics.density*n).toInt()
    fun panel(c:Context,accent:Int=blue)=GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.rgb(13,37,59),Color.rgb(5,20,36))).apply {
        cornerRadius=dp(c,14).toFloat(); setStroke(dp(c,1).coerceAtLeast(1),Color.argb(145,Color.red(accent),Color.green(accent),Color.blue(accent)))
    }
    fun label(c:Context,value:String,size:Float=12f,color:Int=muted,bold:Boolean=false)=TextView(c).apply {
        text=value;textSize=size;setTextColor(color);includeFontPadding=false
        if(bold) setTypeface(typeface,Typeface.BOLD)
    }
    fun vertical(c:Context)=LinearLayout(c).apply { orientation=LinearLayout.VERTICAL }
    fun gap(c:Context,size:Int)=View(c).apply { layoutParams=LinearLayout.LayoutParams(1,dp(c,size)) }
    fun tile(c:Context,title:String,value:String,subtitle:String,accent:Int,large:Boolean=false)=vertical(c).apply {
        background=panel(c,accent);setPadding(dp(c,10),dp(c,12),dp(c,10),dp(c,12))
        addView(label(c,title,11f))
        addView(label(c,value,if(large)32f else 23f,accent,true).apply {
            setPadding(0,dp(c,5),0,dp(c,5));maxLines=1
            setAutoSizeTextTypeUniformWithConfiguration(12,if(large)32 else 23,1,TypedValue.COMPLEX_UNIT_SP)
        },LinearLayout.LayoutParams(-1,dp(c,if(large)48 else 36)))
        addView(label(c,subtitle,10f))
        contentDescription="$title $value $subtitle"
    }
    fun row(c:Context,views:List<View>)=LinearLayout(c).apply {
        gravity=Gravity.TOP
        views.forEachIndexed { i,v -> addView(v,LinearLayout.LayoutParams(0,-2,1f).apply { if(i>0)marginStart=dp(c,6) }) }
    }
    fun button(c:Context,value:String,accent:Int=cyan,action:()->Unit)=MaterialButton(c).apply {
        text=value;isAllCaps=false;textSize=12f;minHeight=dp(c,48);minimumWidth=0
        insetTop=0;insetBottom=0;cornerRadius=dp(c,12);strokeWidth=dp(c,1).coerceAtLeast(1)
        strokeColor=ColorStateList.valueOf(accent);backgroundTintList=ColorStateList.valueOf(Color.rgb(12,35,55))
        setTextColor(accent);setPadding(dp(c,8),dp(c,4),dp(c,8),dp(c,4));setOnClickListener { action() }
    }
}
