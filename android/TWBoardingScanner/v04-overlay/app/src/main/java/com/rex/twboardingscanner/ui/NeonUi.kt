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
    val canvas=Color.rgb(9,11,16)
    val surface=Color.rgb(20,25,34)
    val border=Color.rgb(53,64,79)
    val ink=Color.rgb(235,241,250)
    val muted=Color.rgb(148,164,185)
    val cyan=Color.rgb(111,218,235)
    val pink=Color.rgb(255,130,154)
    val mint=Color.rgb(113,220,184)
    val amber=Color.rgb(231,192,123)
    val blue=Color.rgb(150,167,230)
    val neonRed=Color.rgb(255,55,82)
    val ruby=Color.rgb(143,24,47)
    fun radarColor(type:com.rex.twboardingscanner.domain.RadarType):Int = when(type){
        com.rex.twboardingscanner.domain.RadarType.A_EARLY_BREAKOUT->Color.rgb(99,213,196)
        com.rex.twboardingscanner.domain.RadarType.B_DEEP_REVERSAL->Color.rgb(177,154,244)
        com.rex.twboardingscanner.domain.RadarType.C_LONG_RED_VOLUME->Color.rgb(237,188,109)
        com.rex.twboardingscanner.domain.RadarType.D_MAIN_FORCE->Color.rgb(128,175,255)
        com.rex.twboardingscanner.domain.RadarType.E_DIF_TOUCH->Color.rgb(232,154,186)
        com.rex.twboardingscanner.domain.RadarType.F_VOLUME_CONTRACTION->Color.rgb(130,203,168)
        com.rex.twboardingscanner.domain.RadarType.G_MA5_WAVE->Color.rgb(243,124,131)
    }
    private fun labelAccent(value:CharSequence):Int? = com.rex.twboardingscanner.domain.RadarType.entries
        .firstOrNull{value.length>1 && value[0]==it.name[0] && value[1]==' '}?.let{radarColor(it)}
    fun tint(accent:Int,amount:Float=.10f)=androidx.core.graphics.ColorUtils.blendARGB(surface,accent,amount)
    fun backdrop(c:Context)=NeonBackdrop(c.resources.displayMetrics.density)
    fun categoryStyle(button:MaterialButton,type:com.rex.twboardingscanner.domain.RadarType){
        val accent=radarColor(type)
        button.setTextColor(accent);button.strokeColor=ColorStateList.valueOf(tint(accent,.48f))
        button.backgroundTintList=ColorStateList.valueOf(tint(accent,.06f))
    }
    fun dp(c:Context,n:Int)=(c.resources.displayMetrics.density*n).toInt()
    fun panel(c:Context,accent:Int=blue)=GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.rgb(24,29,38),surface)).apply {
        cornerRadius=dp(c,16).toFloat()
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
    fun row(c:Context,views:List<View>):LinearLayout=LinearLayout(c).apply {
        gravity=Gravity.CENTER_VERTICAL
        views.forEachIndexed { i,v -> addView(v,LinearLayout.LayoutParams(0,-2,1f).apply { if(i>0)marginStart=dp(c,8) }) }
    }
    fun radarRows(c:Context,views:List<View>):LinearLayout=vertical(c).apply{views.chunked(3).forEach{addView(row(c,it),LinearLayout.LayoutParams(-1,-2))}}
    fun button(c:Context,value:String,accent:Int=cyan,action:()->Unit)=MaterialButton(c).apply {
        text=value;isAllCaps=false;textSize=13f;minHeight=dp(c,50);minimumWidth=0
        insetTop=dp(c,3);insetBottom=dp(c,3);cornerRadius=dp(c,14);strokeWidth=dp(c,1).coerceAtLeast(1)
        val effectiveAccent=labelAccent(value)?:accent
        strokeColor=ColorStateList.valueOf(tint(effectiveAccent,.35f));backgroundTintList=ColorStateList.valueOf(tint(effectiveAccent,.04f))
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(muted,effectiveAccent)))
        setPadding(dp(c,12),dp(c,8),dp(c,12),dp(c,8));setOnClickListener { action() }
    }
    fun primary(c:Context,value:String,action:()->Unit)=button(c,value,cyan,action).apply{primaryStyle(this)}
    fun primaryStyle(button:MaterialButton){
        button.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(muted,ink)))
        button.backgroundTintList=ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(border,ruby))
        button.strokeWidth=dp(button.context,1).coerceAtLeast(1);button.strokeColor=ColorStateList.valueOf(neonRed);button.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    }
    fun radarSwitch(button:MaterialButton,type:com.rex.twboardingscanner.domain.RadarType,on:Boolean){
        button.text="${type.label}\n${if(on)"✓ 已啟用" else "— 已停用"}"
        button.textSize=12f;button.minHeight=dp(button.context,64)
        button.isSelected=on;button.isCheckable=true;button.isChecked=on
        val accent=radarColor(type)
        button.setTextColor(if(on)canvas else muted)
        button.backgroundTintList=ColorStateList.valueOf(if(on)accent else surface)
        button.strokeColor=ColorStateList.valueOf(if(on)accent else border)
        button.strokeWidth=dp(button.context,if(on)2 else 1)
        button.contentDescription="${type.label}，${if(on)"已啟用，點擊停用" else "已停用，點擊啟用"}"
    }
    fun selected(button:MaterialButton,active:Boolean,accent:Int=labelAccent(button.text)?:cyan){
        button.isSelected=active
        button.backgroundTintList=ColorStateList.valueOf(if(active)tint(accent,.15f) else surface)
        button.strokeColor=ColorStateList.valueOf(if(active)accent else border)
        button.setTextColor(if(active)accent else muted)
    }
    fun header(c:Context,title:String,kicker:String,subtitle:String="",back:()->Unit)=vertical(c).apply{
        layoutParams=LinearLayout.LayoutParams(-1,-2)
        addView(label(c,kicker,10f,neonRed,true).apply{letterSpacing=.16f})
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
        addView(label(c,"▍ $title",17f,ink,true))
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
