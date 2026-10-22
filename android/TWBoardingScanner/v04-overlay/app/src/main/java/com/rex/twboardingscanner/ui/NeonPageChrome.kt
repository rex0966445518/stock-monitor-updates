package com.rex.twboardingscanner.ui

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Shared interactive bottom navigation. The original content and callbacks are
 * kept intact; page switching never starts a background job automatically.
 */
internal object NeonPageChrome {
    enum class Page { SCAN, BACKTEST, ROBOT, UPDATE }
    private data class Tab(val label:String,val glyph:String,val key:String,val page:Page?)
    private val tabs=listOf(
        Tab("首頁","⌂","home",null),
        Tab("主掃描","◎","scan",Page.SCAN),
        Tab("回測","▥","backtest",Page.BACKTEST),
        Tab("機器人","◇","robot",Page.ROBOT),
        Tab("更新","↻","update",Page.UPDATE)
    )
    fun attach(activity:AppCompatActivity,content:View,selected:Page){
        (content.parent as? ViewGroup)?.removeView(content)
        val host=LinearLayout(activity).apply{
            tag="neon-v2-content-root";orientation=LinearLayout.VERTICAL
            background=NeonUi.backdrop(activity);clipToPadding=false
        }
        host.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        val nav=LinearLayout(activity).apply{
            tag="neon-bottom-navigation"
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            setPadding(NeonUi.dp(activity,4),NeonUi.dp(activity,3),NeonUi.dp(activity,4),NeonUi.dp(activity,3))
            background=NeonUi.panel(activity,NeonUi.violet)
        }
        tabs.forEach{tab->
            val current=tab.page==selected
            val item=TextView(activity).apply{
                text="${tab.glyph}\n${tab.label}"
                textSize=10.5f;gravity=Gravity.CENTER
                includeFontPadding=false
                setTextColor(if(current)NeonUi.ink else NeonUi.muted)
                if(current)background=NeonUi.panel(activity,NeonUi.violet)
                tag="neon-nav-${tab.key}"
                contentDescription="${tab.label}${if(current)"，目前頁面" else "，開啟頁面"}"
                isFocusable=true;isClickable=true
                setOnClickListener{if(!current||tab.page==null)route(activity,tab.page)}
            }
            nav.addView(item,LinearLayout.LayoutParams(0,-1,1f))
        }
        host.addView(nav,LinearLayout.LayoutParams(-1,NeonUi.dp(activity,60)))
        activity.setContentView(host)
    }
    private fun route(activity:AppCompatActivity,page:Page?){
        val destination=when(page){
            null,Page.SCAN->MainActivity::class.java
            Page.BACKTEST->BacktestActivity::class.java
            Page.ROBOT->BtRobotActivity::class.java
            Page.UPDATE->AppUpdateActivity::class.java
        }
        activity.startActivity(Intent(activity,destination).apply{
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if(page==Page.SCAN)putExtra("neon-open-scanner",true)
        })
    }
}
