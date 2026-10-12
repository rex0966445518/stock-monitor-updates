package com.rex.twboardingscanner.ui

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

internal object ChipKSearchRequest{
    data class Request(val code:String,val name:String,val at:Long)
    private fun prefs(c:Context)=c.getSharedPreferences("chipk-search-request",0)
    fun put(c:Context,code:String,name:String){require(code.matches(Regex("[0-9]{4,6}")))
        prefs(c).edit().putString("code",code).putString("name",name).putLong("at",System.currentTimeMillis()).commit()}
    fun get(c:Context):Request?{
        val p=prefs(c);val code=p.getString("code","").orEmpty();val at=p.getLong("at",0)
        if(!code.matches(Regex("[0-9]{4,6}"))||System.currentTimeMillis()-at !in 0..120000){clear(c);return null}
        return Request(code,p.getString("name","").orEmpty(),at)
    }
    fun clear(c:Context){prefs(c).edit().clear().commit()}
    fun enabled(c:Context):Boolean{
        val expected=ComponentName(c,ChipKSearchService::class.java)
        return Settings.Secure.getString(c.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').mapNotNull{ComponentName.unflattenFromString(it)}.any{it==expected}
    }
}

/** Activated by the user, limited by XML + runtime checks to com.cmoney.chipk. */
class ChipKSearchService:AccessibilityService(){
    private val handler=Handler(Looper.getMainLooper())
    private var requestAt=0L;private var startedAt=0L;private var scheduled=false
    private var plan:ChipKNavigation?=null
    override fun onServiceConnected(){
        ChipKSearchRequest.get(this)?.let{request->
            packageManager.getLaunchIntentForPackage(ChipKLauncher.PACKAGE)?.let{startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))}
            scheduled=true;handler.post(tick)
        }
    }
    override fun onAccessibilityEvent(event:AccessibilityEvent?){
        if(event?.packageName?.toString()!=ChipKLauncher.PACKAGE)return
        if(ChipKSearchRequest.get(this)!=null&&!scheduled){scheduled=true;handler.postDelayed(tick,250)}
    }
    private val tick=object:Runnable{override fun run(){
        scheduled=false
        val request=ChipKSearchRequest.get(this@ChipKSearchService)?:return
        if(request.at!=requestAt){requestAt=request.at;startedAt=System.currentTimeMillis();plan=ChipKNavigation(request.code,request.name)}
        if(System.currentTimeMillis()-startedAt>45000){
            ChipKSearchRequest.clear(this@ChipKSearchService)
            Toast.makeText(this@ChipKSearchService,"尚未完成自動定位；股號 ${request.code} 已複製，可貼入籌碼K線搜尋",Toast.LENGTH_LONG).show();return
        }
        val root=rootInActiveWindow
        if(root?.packageName?.toString()==ChipKLauncher.PACKAGE){
            val nodes=mutableListOf<AccessibilityNodeInfo>()
            fun walk(n:AccessibilityNodeInfo){if(nodes.size>=400)return;nodes+=n;for(i in 0 until n.childCount)n.getChild(i)?.let{walk(it)}}
            walk(root)
            fun label(n:AccessibilityNodeInfo):String{
                val own=listOfNotNull(n.text,n.contentDescription,n.hintText).map{it.toString()}.distinct().joinToString(" ")
                if(n.isEditable||!n.isClickable)return own
                val texts=mutableListOf<String>();fun childText(c:AccessibilityNodeInfo,depth:Int){
                    c.text?.toString()?.takeIf{it.isNotBlank()}?.let{texts+=it}
                    if(depth<3)for(i in 0 until c.childCount)c.getChild(i)?.let{childText(it,depth+1)}
                };childText(n,0)
                return (listOf(own)+texts).filter{it.isNotBlank()}.distinct().joinToString(" ")
            }
            val model=nodes.mapIndexedNotNull{i,n->if(!n.isVisibleToUser)null else ChipKNavigation.Node(i,label(n),n.viewIdResourceName.orEmpty(),n.isEditable,n.isClickable,n.isPassword,n.isSelected)}
            val navigator=plan!!;val action=navigator.next(model)
            if(action!=null){
                val node=nodes[action.key]
                val ok=if(action.text!=null)node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,action.text)})
                    else node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if(ok)navigator.applied(action)
            }
            if(navigator.complete){ChipKSearchRequest.clear(this@ChipKSearchService);return}
        }
        scheduled=true;handler.postDelayed(this,500)
    }}
    override fun onInterrupt(){scheduled=false;handler.removeCallbacksAndMessages(null);ChipKSearchRequest.clear(this)}
    override fun onDestroy(){scheduled=false;handler.removeCallbacksAndMessages(null);super.onDestroy()}
}
