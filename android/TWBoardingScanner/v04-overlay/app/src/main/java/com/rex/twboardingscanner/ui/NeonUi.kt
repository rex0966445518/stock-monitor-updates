package com.rex.twboardingscanner.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/** Shared visual language: quiet surfaces, clear hierarchy and one primary action. */
internal object NeonUi {
    val canvas=Color.rgb(8,14,25)
    val surface=Color.rgb(16,26,42)
    val border=Color.rgb(36,51,70)
    val ink=Color.rgb(235,241,250)
    val muted=Color.rgb(148,164,185)
    val cyan=Color.rgb(111,218,235)
    val pink=Color.rgb(255,130,154)
    val mint=Color.rgb(113,220,184)
    val amber=Color.rgb(231,192,123)
    val blue=Color.rgb(150,167,230)
    fun dp(c:Context,n:Int)=(c.resources.displayMetrics.density*n).toInt()
    fun panel(c:Context,accent:Int=blue)=GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.rgb(20,32,49),surface)).apply {
        cornerRadius=dp(c,20).toFloat()
        setStroke(dp(c,1).coerceAtLeast(1),Color.rgb((Color.red(accent)*.12+31).toInt(),(Color.green(accent)*.12+39).toInt(),(Color.blue(accent)*.12+51).toInt()))
    }
    fun label(c:Context,value:String,size:Float=12f,color:Int=muted,bold:Boolean=false)=TextView(c).apply {
        text=value;textSize=size;setTextColor(color);includeFontPadding=false
        setLineSpacing(dp(c,2).toFloat(),1.06f)
        if(bold)typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    }
    fun vertical(c:Context)=LinearLayout(c).apply { orientation=LinearLayout.VERTICAL }
    fun gap(c:Context,size:Int)=View(c).apply { layoutParams=LinearLayout.LayoutParams(1,dp(c,size)) }
    fun tile(c:Context,title:String,value:String,subtitle:String,accent:Int,large:Boolean=false)=vertical(c).apply {
        background=panel(c,accent);setPadding(dp(c,12),dp(c,14),dp(c,12),dp(c,14))
        addView(label(c,title,11f))
        addView(label(c,value,if(large)32f else 23f,accent,true).apply {
            setPadding(0,dp(c,6),0,dp(c,6));maxLines=1
            setAutoSizeTextTypeUniformWithConfiguration(12,if(large)32 else 23,1,TypedValue.COMPLEX_UNIT_SP)
        },LinearLayout.LayoutParams(-1,dp(c,if(large)52 else 42)))
        addView(label(c,subtitle,10f))
        contentDescription="$title $value $subtitle"
    }
    fun row(c:Context,views:List<View>)=LinearLayout(c).apply {
        gravity=Gravity.CENTER_VERTICAL
        views.forEachIndexed { i,v -> addView(v,LinearLayout.LayoutParams(0,-2,1f).apply { if(i>0)marginStart=dp(c,8) }) }
    }
    fun button(c:Context,value:String,accent:Int=cyan,action:()->Unit)=MaterialButton(c).apply {
        text=value;isAllCaps=false;textSize=13f;minHeight=dp(c,50);minimumWidth=0
        insetTop=dp(c,3);insetBottom=dp(c,3);cornerRadius=dp(c,14);strokeWidth=dp(c,1).coerceAtLeast(1)
        strokeColor=ColorStateList.valueOf(border);backgroundTintList=ColorStateList.valueOf(surface)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(muted,accent)))
        setPadding(dp(c,12),dp(c,8),dp(c,12),dp(c,8));setOnClickListener { action() }
    }
    fun primary(c:Context,value:String,action:()->Unit)=button(c,value,cyan,action).apply{primaryStyle(this)}
    fun primaryStyle(button:MaterialButton){
        button.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(muted,canvas)))
        button.backgroundTintList=ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(border,cyan))
        button.strokeWidth=0;button.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    }
    fun selected(button:MaterialButton,active:Boolean){
        button.isSelected=active
        button.backgroundTintList=ColorStateList.valueOf(if(active)Color.rgb(29,58,70) else surface)
        button.strokeColor=ColorStateList.valueOf(if(active)cyan else border)
        button.setTextColor(if(active)cyan else muted)
    }
    fun header(c:Context,title:String,kicker:String,subtitle:String="",back:()->Unit)=vertical(c).apply{
        layoutParams=LinearLayout.LayoutParams(-1,-2)
        addView(label(c,kicker,10f,cyan,true).apply{letterSpacing=.16f})
        addView(LinearLayout(c).apply{
            layoutParams=LinearLayout.LayoutParams(-1,-2)
            gravity=Gravity.CENTER_VERTICAL
            addView(label(c,title,26f,ink,true),LinearLayout.LayoutParams(0,-2,1f))
            addView(button(c,"返回",muted,back).apply{tag="page-back"},LinearLayout.LayoutParams(dp(c,64),-2).apply{marginStart=dp(c,12)})
        })
        if(subtitle.isNotBlank())addView(label(c,subtitle,12f))
        addView(gap(c,20))
    }
    fun section(c:Context,title:String,subtitle:String="")=vertical(c).apply{
        background=panel(c);setPadding(dp(c,16),dp(c,16),dp(c,16),dp(c,16))
        addView(label(c,title,17f,ink,true))
        if(subtitle.isNotBlank()){addView(gap(c,5));addView(label(c,subtitle,12f))}
        addView(gap(c,12))
    }
    fun field(input:EditText):EditText=input.apply{
        background=GradientDrawable().apply{setColor(canvas);cornerRadius=dp(context,12).toFloat();setStroke(dp(context,1).coerceAtLeast(1),border)}
        setPadding(dp(context,14),dp(context,12),dp(context,14),dp(context,12))
        setTextColor(ink);setHintTextColor(muted);textSize=16f;minHeight=dp(context,52)
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(context,6);bottomMargin=dp(context,10)}
    }
    /** Keep the same child views/listeners when moving secondary controls into a disclosure. */
    fun group(root:LinearLayout,first:View,last:View,title:String,subtitle:String="",key:String="",expanded:Boolean=false):LinearLayout{
        val c=root.context;val from=root.indexOfChild(first);val end=root.indexOfChild(last)
        require(from>=0&&end>=from)
        val body=vertical(c)
        repeat(end-from+1){val child=root.getChildAt(from);root.removeViewAt(from);body.addView(child)}
        val box=vertical(c).apply{background=panel(c);setPadding(dp(c,14),dp(c,10),dp(c,14),dp(c,14))}
        val prefs=c.getSharedPreferences("ui_layout",Context.MODE_PRIVATE)
        var open=if(key.isBlank())expanded else prefs.getBoolean(key,expanded)
        val toggle=button(c,title,ink){}.apply{gravity=Gravity.START or Gravity.CENTER_VERTICAL;strokeWidth=0;backgroundTintList=ColorStateList.valueOf(Color.TRANSPARENT);setPadding(0,dp(c,10),0,dp(c,10));tag="disclosure-$key"}
        fun refresh(){toggle.text="$title  ${if(open)"−" else "+"}";toggle.contentDescription="$title，${if(open)"已展開" else "已收合"}";body.visibility=if(open)View.VISIBLE else View.GONE}
        toggle.setOnClickListener{open=!open;if(key.isNotBlank())prefs.edit().putBoolean(key,open).apply();refresh()}
        box.addView(toggle)
        if(subtitle.isNotBlank())box.addView(label(c,subtitle,12f).apply{setPadding(0,0,0,dp(c,8))})
        box.addView(body);refresh()
        root.addView(box,from,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(c,8);bottomMargin=dp(c,12)})
        return box
    }
    fun empty(c:Context,title:String,message:String)=section(c,title).apply{addView(label(c,message,13f))}
    fun reveal(view:View){
        var ancestor=view.parent
        while(ancestor is ViewGroup){
            if(ancestor.visibility==View.GONE){
                val box=ancestor.parent as? ViewGroup
                (box?.getChildAt(0) as? MaterialButton)?.takeIf{it.tag?.toString()?.startsWith("disclosure-")==true}?.performClick()
            }
            ancestor=ancestor.parent
        }
        view.requestFocus()
        view.post{view.requestRectangleOnScreen(android.graphics.Rect(0,0,view.width,view.height),true)}
    }
}
