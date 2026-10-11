package com.rex.twboardingscanner.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.Toast
import com.rex.twboardingscanner.domain.ChartSeries
import com.rex.twboardingscanner.data.BacktestFiveMinuteRepository
import com.rex.twboardingscanner.data.FiveMinuteResult
import com.rex.twboardingscanner.data.StockDirectory
import java.time.LocalDate

/**
 * Embedded preview. Query only once this panel is actually on-screen; archived reports
 * can contain thousands of fills, and unloaded cards must not trigger HTTP requests.
 */
internal class BacktestFiveMinutePanel(
    c:Context,private val code:String,dateText:String,private val side:String,
    private val price:Double?,private val recordedTime:String,private val timeKind:String
):LinearLayout(c){
    private val day=runCatching{LocalDate.parse(dateText)}.getOrNull()
    private val dateLabel=day?.toString()?: "日期未記錄"
    private val handler=Handler(Looper.getMainLooper())
    private val status=NeonUi.label(c,"捲動至此卡片時載入真實 5 分 K…",11f)
    private val chart=BacktestFiveMinuteChartView(c)
    private val retry=NeonUi.button(c,"重試載入 5 分 K",NeonUi.cyan){startLoading()}
    // Goodinfo's verified historical chart is a DAILY chart, not a 5-minute OHLCV feed.
    // Keep the real five-minute feed/cache unchanged; never label daily candles as minute candles.
    private val goodinfo=NeonUi.button(c,"Goodinfo 歷史日 K（非 5 分 K）",NeonUi.cyan){
        try{
            require(code.matches(Regex("[0-9]{4}"))){"股號未記錄"}
            c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(ChartSeries.goodinfoUrl(code))))
        }catch(e:Exception){
            Toast.makeText(c,"目前無法開啟 Goodinfo，請確認網路及瀏覽器",Toast.LENGTH_SHORT).show()
        }
    }.apply{
        tag="goodinfo-historical-daily"
        contentDescription="$code Goodinfo 長期日 K 線；不是五分鐘 K 線"
        isEnabled=code.matches(Regex("[0-9]{4}"))
    }
    private var loading=false
    private var done=false
    private var observer:ViewTreeObserver?=null
    private val onScroll=ViewTreeObserver.OnScrollChangedListener{checkVisible()}
    init{
        orientation=VERTICAL
        background=NeonUi.panel(c,NeonUi.blue)
        setPadding(NeonUi.dp(c,10),NeonUi.dp(c,10),NeonUi.dp(c,10),NeonUi.dp(c,12))
        addView(NeonUi.label(c,"${if(side=="BUY")"買入" else "賣出"}當日 5 分 K  ·  $dateLabel",13f,NeonUi.cyan,true))
        addView(NeonUi.gap(c,4))
        addView(status)
        chart.visibility=View.GONE
        addView(chart,LayoutParams(LayoutParams.MATCH_PARENT,NeonUi.dp(c,194)))
        val exact=(side=="BUY"&&recordedTime.startsWith("13:30"))||(side=="SELL"&&recordedTime=="09:00")
        addView(NeonUi.label(c,
            if(exact)"黃虛線為模擬成交價；黃直線為模型時間（非逐筆成交），請對照當時 5 分鐘走勢。"
            else "模擬交易 ${recordedTime.ifBlank{"時間未記錄"}}，無精確成交分鐘；黃虛線僅標示模型成交價，不偽造進出場 K 棒。",
            10f,NeonUi.muted))
        if(timeKind.isNotBlank())addView(NeonUi.label(c,timeKind,10f,NeonUi.amber))
        retry.visibility=View.GONE
        addView(retry)
        addView(NeonUi.gap(c,6))
        addView(goodinfo)
        addView(NeonUi.label(c,
            "Goodinfo 公開圖表提供長期日／週／月 K；目前未確認指定歷史日期的 5 分 K。此入口僅供長期走勢對照，不能取代上方的真實 5 分 K。",
            10f,NeonUi.muted))
    }
    private fun checkVisible(){
        if(done||loading||!isAttachedToWindow)return
        val rect=Rect()
        if(getGlobalVisibleRect(rect)&&rect.height()>NeonUi.dp(context,24))startLoading()
    }
    private fun startLoading(){
        if(loading)return
        val date=day ?: run {
            status.text="舊回測紀錄沒有日期，無法查詢真實 5 分 K"
            retry.visibility=View.GONE;done=true;return
        }
        loading=true;retry.visibility=View.GONE
        status.text="正在查詢 $dateLabel 的 5 分鐘行情…"
        val market=StockDirectory(context).cached().firstOrNull{it.code==code}?.market
        BacktestFiveMinuteRepository.executor.execute {
            val result=runCatching{BacktestFiveMinuteRepository(context).load(code,date,market)}
                .getOrElse{FiveMinuteResult.Unavailable("5 分 K 讀取發生錯誤：${it.message?.take(80)?: "未知錯誤"}")}
            handler.post{
                loading=false
                if(!isAttachedToWindow){done=false;return@post}
                when(result){
                    is FiveMinuteResult.Available->{
                        done=true;chart.visibility=View.VISIBLE
                        chart.setData(result.bars,side,recordedTime,price)
                        status.text="${result.source} · ${result.bars.size} 根 · 僅供行情對照，不代表模擬成交的逐筆時間"
                        retry.visibility=View.GONE
                    }
                    is FiveMinuteResult.Unavailable->{
                        done=true;chart.visibility=View.GONE
                        status.text=result.reason
                        retry.visibility=if(result.reason.contains("60 天")||date>LocalDate.now(com.rex.twboardingscanner.domain.RuleMetrics.TAIPEI))View.GONE else View.VISIBLE
                    }
                }
            }
        }
    }
    override fun onAttachedToWindow(){
        super.onAttachedToWindow()
        observer=viewTreeObserver.takeIf{it.isAlive}
        observer?.addOnScrollChangedListener(onScroll)
        post{checkVisible()}
    }
    override fun onDetachedFromWindow(){
        observer?.takeIf{it.isAlive}?.removeOnScrollChangedListener(onScroll)
        observer=null
        super.onDetachedFromWindow()
    }
}
