package com.rex.twboardingscanner.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.*
import android.widget.*
import org.json.JSONObject

/** Embedded public chart, loaded only while the holding card is on screen. */
class WantGooInlineChartView(context:Context,val code:String):LinearLayout(context) {
    private val handler=Handler(Looper.getMainLooper())
    private val status=NeonUi.label(context,"日K · 成交量 · MACD  ｜  WantGoo 玩股網",11f,NeonUi.cyan)
    private val frame=FrameLayout(context)
    private val retry=NeonUi.button(context,"重新載入圖表",NeonUi.muted){failed=false;release();if(active)start()}
    private var web:WebView?=null
    private var active=false
    private var failed=false
    private var attempts=0
    private var ready=false
    init {
        require(code.matches(Regex("[0-9]{4,6}")))
        orientation=VERTICAL
        addView(status)
        addView(frame,LayoutParams(-1,NeonUi.dp(context,840)))
        addView(retry,LayoutParams(-1,-2))
        retry.visibility=View.GONE
        frame.setBackgroundColor(NeonUi.canvas)
    }
    fun setActive(value:Boolean) {
        active=value
        if(value){if(web==null&&!failed)start();web?.onResume()}else release()
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun start() {
        if(web!=null)return
        ready=false;attempts=0
        frame.layoutParams=frame.layoutParams.apply{height=NeonUi.dp(context,840)}
        status.text="日K · 成交量 · MACD  ｜  WantGoo 載入中…"
        retry.visibility=View.GONE
        val w=WebView(context)
        web=w
        w.alpha=0f
        w.setBackgroundColor(NeonUi.canvas)
        w.settings.apply {
            javaScriptEnabled=true;domStorageEnabled=true
            allowFileAccess=false;allowContentAccess=false
            mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture=true
        }
        w.webViewClient=object:WebViewClient() {
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean =
                !isChartUrl(request.url.toString(),code)
            override fun onPageFinished(view:WebView,url:String) {
                if(web===view&&isChartUrl(url,code)){handler.removeCallbacks(poll);handler.post(poll)}
            }
            override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError) {
                if(request.isForMainFrame&&web===view)fail("圖表載入失敗，請重試")
            }
            override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,response:WebResourceResponse) {
                if(request.isForMainFrame&&web===view)fail("WantGoo HTTP ${response.statusCode}，請稍後重試")
            }
            override fun onRenderProcessGone(view:WebView,detail:RenderProcessGoneDetail):Boolean {
                if(web===view)fail("圖表暫停，請重新載入")
                return true
            }
        }
        w.isVerticalScrollBarEnabled=false
        // Vertical gestures belong to the portfolio; horizontal gestures stay with the chart.
        var touchX=0f;var touchY=0f
        w.setOnTouchListener {v,event ->
            if(event.actionMasked==android.view.MotionEvent.ACTION_DOWN){touchX=event.x;touchY=event.y}
            if(event.actionMasked==android.view.MotionEvent.ACTION_MOVE) {
                v.parent.requestDisallowInterceptTouchEvent(kotlin.math.abs(event.x-touchX)>kotlin.math.abs(event.y-touchY))
            }
            false
        }
        frame.addView(w,FrameLayout.LayoutParams(-1,-1))
        w.loadUrl(url(code))
        handler.postDelayed(timeout,35_000)
    }
    private val timeout=Runnable { if(!ready&&web!=null)fail("WantGoo 尚未載入圖表，可按下方重新載入") }
    private val poll=object:Runnable {
        override fun run() {
            val w=web?:return
            w.evaluateJavascript(SCRIPT.replace("__STOCK_CODE__",code)) { raw ->
                if(web!==w||!active)return@evaluateJavascript
                val result=runCatching{JSONObject(raw)}.getOrNull()
                if(result?.optBoolean("ready")==true) {
                    ready=true;w.alpha=1f;handler.removeCallbacks(timeout)
                    val stamp=result.optString("stamp")
                    status.text="日K · 成交量 · MACD  ｜  WantGoo 玩股網" + if(stamp.isBlank())"" else "\n資料時間 $stamp"
                }else if(++attempts<30)handler.postDelayed(this,800)
                else fail("原站圖表尚未就緒，請重新載入")
            }
        }
    }
    private fun fail(message:String) {
        failed=true;release();status.text=message;retry.visibility=View.VISIBLE
        frame.layoutParams=frame.layoutParams.apply{height=NeonUi.dp(context,40)}
    }
    private fun release() {
        handler.removeCallbacks(poll);handler.removeCallbacks(timeout)
        val old=web;web=null
        old?.let{frame.removeView(it);it.stopLoading();it.onPause();it.destroy()}
    }
    fun dispose(){active=false;release()}
    companion object {
        fun url(code:String):String {
            require(code.matches(Regex("[0-9]{4,6}")))
            return "https://www.wantgoo.com/stock/$code/technical-chart"
        }
        fun isChartUrl(raw:String,code:String):Boolean {
            val uri=Uri.parse(raw)
            return uri.scheme=="https"&&uri.host=="www.wantgoo.com"&&uri.path=="/stock/$code/technical-chart"
        }
        // Presentation only: retain the original SVG chart and its source credits.
        // Controls use the same public options available on the source page.
        val SCRIPT="""
            (function(){
                var chart=document.getElementById('technical-chart');
                if(!chart || !chart.querySelector('svg .highcharts-point'))return {ready:false};
                var identity=document.querySelector('.astock-code');
                if(!identity || identity.textContent.trim()!=='__STOCK_CODE__')return {ready:false};
                var stamp=(document.getElementById('lastQuoteTime')||{}).textContent||'';
                if(!document.getElementById('rex-inline-chart-style')){
                    var css=document.createElement('style');css.id='rex-inline-chart-style';
                    css.textContent='html,body{margin:0!important;padding:0!important;min-width:0!important;background:#0b1423!important;} .rex-chart-ancestor{margin:0!important;padding:0!important;width:100%!important;max-width:none!important;min-width:0!important;} .rex-chart-ancestor > :not(.rex-chart-ancestor):not(#technical-chart){display:none!important;} .highcharts-stocktools-wrapper{display:none!important;}';
                    document.head.appendChild(css);
                    var node=chart;
                    while(node && node!==document.body){
                        var parent=node.parentElement;
                        if(!parent)break;
                        Array.from(parent.children).forEach(function(sibling){if(sibling!==node && sibling.tagName!=='SCRIPT' && sibling.tagName!=='STYLE')sibling.style.setProperty('display','none','important');});
                        parent.classList.add('rex-chart-ancestor');node=parent;
                    }
                    var selectors=document.querySelectorAll('#indicator-groups [indicator-group] select');
                    ['K線及均線','成交量','KD','MACD'].forEach(function(value,i){
                        var select=selectors[i];if(select && Array.from(select.options).some(function(o){return o.value===value;}) && select.value!==value){select.value=value;select.dispatchEvent(new Event('change',{bubbles:true}));}
                    });
                    var daily=document.querySelector('#candlestick-types button[type="daily"]');
                    if(daily && !daily.classList.contains('active'))daily.click();
                    window.dispatchEvent(new Event('resize'));
                    window.scrollTo(0,0);
                    return {ready:false};
                }
                return {ready:true,stamp:stamp.trim()};
            })()
        """.trimIndent()
    }
}
