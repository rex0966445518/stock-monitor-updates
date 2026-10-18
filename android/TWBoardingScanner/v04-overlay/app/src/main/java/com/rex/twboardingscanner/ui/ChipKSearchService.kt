package com.rex.twboardingscanner.ui

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
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

/** Search navigation is unchanged; the same user-enabled helper now also handles seven safe tabs. */
class ChipKSearchService:AccessibilityService(){
    private val handler=Handler(Looper.getMainLooper())
    private val batchStore by lazy { ChipKSevenPageStore(this) }
    private var requestAt=0L
    private var startedAt=0L
    private var scheduled=false
    private var plan:ChipKNavigation?=null
    private var batchId=""
    private var batchIndex=-1
    private var pageStartedAt=0L
    private var lastActionAt=0L
    private var clickAt=0L
    private var beforeClick=""
    private var stableFingerprint=""
    private var stableFrames=0
    private var scrolled=0

    override fun onServiceConnected() {
        ChipKSearchRequest.get(this)?.let {
            packageManager.getLaunchIntentForPackage(ChipKLauncher.PACKAGE)?.let { launch ->
                startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        schedule(300)
    }

    override fun onAccessibilityEvent(event:AccessibilityEvent?) {
        if (event?.packageName?.toString()!=ChipKLauncher.PACKAGE)return
        if (ChipKSearchRequest.get(this)!=null || batchStore.snapshot()?.active==true) schedule(250)
    }

    private fun schedule(delay:Long) {
        if (scheduled) return
        scheduled=true
        handler.postDelayed(tick,delay)
    }

    private fun collectNodes(root:AccessibilityNodeInfo):List<AccessibilityNodeInfo> {
        val nodes=mutableListOf<AccessibilityNodeInfo>()
        fun walk(n:AccessibilityNodeInfo) {
            if(nodes.size>=500)return
            nodes+=n
            for(i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        return nodes
    }

    private fun label(n:AccessibilityNodeInfo):String {
        val own=listOfNotNull(n.text,n.contentDescription,n.hintText)
            .map{it.toString()}.distinct().joinToString(" ")
        if(n.isEditable || !n.isClickable)return own
        val texts=mutableListOf<String>()
        fun children(c:AccessibilityNodeInfo,depth:Int){
            c.text?.toString()?.takeIf{it.isNotBlank()}?.let{texts+=it}
            if(depth<3)for(i in 0 until c.childCount)c.getChild(i)?.let{children(it,depth+1)}
        }
        children(n,0)
        return (listOf(own)+texts).filter{it.isNotBlank()}.distinct().joinToString(" ")
    }

    private fun performSearch(request:ChipKSearchRequest.Request,root:AccessibilityNodeInfo) {
        if(request.at!=requestAt) {
            requestAt=request.at
            startedAt=System.currentTimeMillis()
            plan=ChipKNavigation(request.code,request.name)
        }
        if(System.currentTimeMillis()-startedAt>45000) {
            ChipKSearchRequest.clear(this)
            Toast.makeText(this,"尚未完成自動定位；股號 ${request.code} 已複製，可貼入籌碼K線搜尋",
                Toast.LENGTH_LONG).show()
            return
        }
        val nodes=collectNodes(root)
        val model=nodes.mapIndexedNotNull{i,n->
            if(!n.isVisibleToUser)null else ChipKNavigation.Node(
                i,label(n),n.viewIdResourceName.orEmpty(),n.isEditable,
                n.isClickable,n.isPassword,n.isSelected)
        }
        val navigator=plan ?: return
        val action=navigator.next(model)
        if(action!=null) {
            val node=nodes.getOrNull(action.key) ?: return
            val ok=if(action.text!=null) node.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text) }
            ) else node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if(ok) navigator.applied(action)
        }
        if(navigator.complete) ChipKSearchRequest.clear(this)
    }

    private fun performBatch(root:AccessibilityNodeInfo) {
        val session=batchStore.snapshot() ?: return
        if(!session.active || ChipKSearchRequest.get(this)!=null)return
        val page=ChipKPage.at(session.index) ?: return
        val now=System.currentTimeMillis()
        if(session.id!=batchId || session.index!=batchIndex) {
            batchId=session.id
            batchIndex=session.index
            pageStartedAt=now
            lastActionAt=0L
            clickAt=0L
            scrolled=0
            beforeClick=""
            stableFingerprint=""
            stableFrames=0
            batchStore.requestNavigation(batchIndex)
        }
        if(session.readyIndex==batchIndex) {
            batchStore.seen(batchIndex)
            return
        }
        if(now-pageStartedAt>22000) {
            batchStore.skip(batchIndex,"22 秒內無法定位或確認「${page.title}」頁籤；可能需要不同 UI 校準、會員權限或手動切換")
            return
        }
        val nodes=collectNodes(root)
        val rootBox=Rect()
        root.getBoundsInScreen(rootBox)
        val screenHeight=maxOf(1,rootBox.bottom)
        val controls=mutableListOf<ChipKTabNode>()
        nodes.forEachIndexed { i,n ->
            if(!n.isVisibleToUser || n.isPassword) return@forEachIndexed
            val rect=Rect()
            n.getBoundsInScreen(rect)
            val own=label(n).trim()
            if(n.isClickable || n.isScrollable) {
                controls+=ChipKTabNode(i,own,n.viewIdResourceName.orEmpty(),
                    n.isClickable,n.isSelected,n.isChecked,n.isScrollable,
                    rect.top,rect.bottom,screenHeight)
            }
            if(!n.isClickable && own.length in 1..12 &&
                ChipKPage.ordered.any { ChipKPageNavigator.matches(it,own) }) {
                // CMoney sometimes marks only the parent container as clickable.
                val parent=n.parent
                val parentKey=nodes.indexOf(parent)
                if(parentKey>=0 && parent.isClickable && parent.isVisibleToUser) {
                    val box=Rect()
                    parent.getBoundsInScreen(box)
                    controls+=ChipKTabNode(parentKey,own,parent.viewIdResourceName.orEmpty(),
                        true,parent.isSelected||n.isSelected,parent.isChecked||n.isChecked,
                        parent.isScrollable,box.top,box.bottom,screenHeight)
                }
            }
        }
        val fingerprint=nodes.filter { it.isVisibleToUser && !it.isEditable && !it.isPassword }
            .take(260).joinToString("|") {
                listOfNotNull(it.text,it.contentDescription).joinToString("").take(80)
            }
        if(fingerprint==stableFingerprint)stableFrames++ else {
            stableFingerprint=fingerprint
            stableFrames=0
        }
        val pageChanged=clickAt>0 && fingerprint.isNotEmpty() && fingerprint!=beforeClick
        val decision=ChipKPageNavigator.decide(
            page,controls,clickAt>0,pageChanged,
            clickAt>0 && now-clickAt>=1300 && stableFrames>=2,
            scrolled
        )
        when(decision) {
            is ChipKPageDecision.Confirm -> batchStore.ready(batchIndex,decision.evidence)
            is ChipKPageDecision.Tap -> if(now-lastActionAt>=850) {
                val clicked=nodes.getOrNull(decision.key)?.performAction(
                    AccessibilityNodeInfo.ACTION_CLICK) == true
                lastActionAt=now
                if(clicked) {
                    clickAt=now
                    beforeClick=fingerprint
                    stableFrames=0
                }
            }
            is ChipKPageDecision.Scroll -> if(now-lastActionAt>=900) {
                val scrolledOk=nodes.getOrNull(decision.key)?.performAction(
                    if(decision.forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                ) == true
                lastActionAt=now
                if(scrolledOk)scrolled++ else scrolled=5
            }
            ChipKPageDecision.Wait -> Unit
        }
    }

    private val tick=object:Runnable { override fun run() {
        scheduled=false
        val searching=ChipKSearchRequest.get(this@ChipKSearchService)
        val active=batchStore.snapshot()?.active==true
        if(searching==null && !active)return
        val root=rootInActiveWindow
        if(root?.packageName?.toString()==ChipKLauncher.PACKAGE) {
            if(searching!=null) performSearch(searching,root)
            else if(active)performBatch(root)
        }
        if(ChipKSearchRequest.get(this@ChipKSearchService)!=null || batchStore.snapshot()?.active==true)
            schedule(650)
    }}

    override fun onInterrupt() {
        scheduled=false
        handler.removeCallbacksAndMessages(null)
        ChipKSearchRequest.clear(this)
        batchStore.stop("無障礙輔助已中斷；保留已完成截圖")
    }
    override fun onDestroy() {
        scheduled=false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
