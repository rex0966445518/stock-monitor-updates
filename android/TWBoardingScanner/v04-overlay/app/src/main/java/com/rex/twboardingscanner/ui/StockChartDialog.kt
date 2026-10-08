package com.rex.twboardingscanner.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.rex.twboardingscanner.data.MarketDataProvider
import com.rex.twboardingscanner.domain.ChartPoint
import com.rex.twboardingscanner.domain.ChartSeries
import com.rex.twboardingscanner.domain.DailyBar
import com.rex.twboardingscanner.domain.Market
import com.rex.twboardingscanner.domain.RuleMetrics
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future

class StockChartDialog(
    context: Context,
    private val provider: MarketDataProvider,
    private val code: String,
    private val stockName: String,
    private val market: Market? = null,
    private val initial: List<DailyBar> = emptyList()
): Dialog(context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var request: Future<*>? = null
    private var closed = false
    private var generation = 0
    private var shown = initial
    private lateinit var status: TextView
    private lateinit var values: TextView
    private lateinit var loading: ProgressBar
    private lateinit var retry: Button
    private lateinit var chart: MiniStockChartView
    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(4,17,31))
            setPadding(dp(12),dp(12),dp(12),dp(12))
        }
        val top=LinearLayout(context).apply { gravity=android.view.Gravity.CENTER_VERTICAL }
        top.addView(label("$code  $stockName",20f),LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        top.addView(button("關閉") { dismiss() })
        root.addView(top)
        val scroll=ScrollView(context).apply { isFillViewport=true }
        val body=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        status=label("正在查詢最新日線…",13f)
        body.addView(status)
        loading=ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate=true }
        body.addView(loading,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(4)))
        val actions=LinearLayout(context)
        retry=button("重新查詢") { reload() }
        actions.addView(retry,LinearLayout.LayoutParams(0,dp(48),1f))
        actions.addView(button("Goodinfo 日K ↗") { openGoodinfo() },LinearLayout.LayoutParams(0,dp(48),1f))
        body.addView(actions)
        body.addView(label("資料：Yahoo Finance 日線｜可含當日未收盤 K 棒\nMA5 黃・MA10 藍・MA20 紫｜DIF 青・DEA 橘\nMACD 柱 = DIF − DEA；點選或拖動 K 棒查看數值",12f))
        val ranges=LinearLayout(context)
        listOf(30,60,120,250).forEach { days ->
            ranges.addView(button("${days}日") {
                chart.setWindow(days)
                for(i in 0 until ranges.childCount) {
                    val choice = ranges.getChildAt(i) as Button
                    choice.isSelected = choice.tag == days
                    choice.text = (if (choice.isSelected) "✓" else "") + "${choice.tag}日"
                }
            }.apply { tag=days; isSelected=days==60; text=(if(days==60) "✓" else "") + "${days}日" },LinearLayout.LayoutParams(0,dp(48),1f))
        }
        body.addView(ranges)
        values=label("選取 K 棒後顯示開高低收、成交量及 MACD",13f)
        values.minHeight=dp(94)
        body.addView(values)
        chart=MiniStockChartView(context).apply {
            detailed=true
            onSelected={ point -> showValues(point) }
            setWindow(60)
            contentDescription="日線K線、成交量與MACD；拖動可選取交易日"
        }
        body.addView(chart,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(460)))
        body.addView(label("圖表顯示最新查詢資料。A／B 掃描排除當日日線，因此可能與原卡片觸發日不同。",12f))
        if(initial.isNotEmpty())chart.setBars(initial)
        setContentView(root)
        window?.setBackgroundDrawable(ColorDrawable(Color.rgb(4,17,31)))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(12)+bars.left,dp(8)+bars.top,dp(12)+bars.right,dp(8)+bars.bottom)
            insets
        }
    }
    override fun onStart() {
        super.onStart()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
        if(request==null)reload()
    }
    private fun label(value: String, size: Float) = TextView(context).apply {
        text=value;textSize=size;setTextColor(Color.rgb(209,228,241));setPadding(0,dp(5),0,dp(5))
    }
    private fun button(value: String, action: () -> Unit) = Button(context).apply {
        text=value;isAllCaps=false;textSize=12f;minWidth=0;minimumWidth=0
        setTextColor(Color.rgb(80,199,255));setOnClickListener { action() }
    }
    private fun showValues(point: ChartPoint) {
        val b=point.bar
        fun number(value: Double?)=value?.let { String.format(Locale.TAIWAN,"%.2f",it) } ?: "待累積"
        values.text="${RuleMetrics.tradingDate(b.time)}\n開 ${number(b.open)}　高 ${number(b.high)}　低 ${number(b.low)}　收 ${number(b.close)}\n"+
            "成交量 ${String.format(Locale.TAIWAN,"%,.3f",b.volumeShares/1000.0)} 張\n"+
            "DIF ${number(point.dif)}　DEA ${number(point.dea)}　柱 ${number(point.histogram)}"
    }
    private fun reload() {
        if(closed || request?.isDone==false)return
        val token=++generation
        loading.visibility=View.VISIBLE;retry.isEnabled=false
        status.text=if(shown.isEmpty()) "正在查詢 $code 最新日線…" else "正在更新 $code…目前保留先前資料（${RuleMetrics.tradingDate(shown.last().time)}）"
        request=executor.submit {
            val result=runCatching { provider.loadChartHistory(code,market) }.getOrDefault(emptyList())
            val clean=ChartSeries.prepare(result).map { it.bar }
            handler.post {
                if(!closed && isShowing && token==generation) {
                    request=null
                    loading.visibility=View.GONE;retry.isEnabled=true
                    if(clean.isEmpty()) {
                        status.text=if(shown.isEmpty()) "日線資料載入失敗，請重新查詢或開啟 Goodinfo。" else "更新失敗；保留 ${RuleMetrics.tradingDate(shown.last().time)} 的資料，可重新查詢。"
                    } else {
                        shown=clean;chart.setBars(clean)
                        val now=java.time.ZonedDateTime.now(RuleMetrics.TAIPEI)
                        status.text="最新日K ${RuleMetrics.tradingDate(clean.last().time)}｜共 ${clean.size} 根\n查詢時間 ${now.format(java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm:ss"))}（台灣）；報價可能延遲"
                    }
                }
            }
        }
    }
    private fun openGoodinfo() {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(ChartSeries.goodinfoUrl(code)))) }
            .onFailure { Toast.makeText(context,"找不到可開啟網頁的瀏覽器",Toast.LENGTH_SHORT).show() }
    }
    override fun dismiss() {
        closed=true;generation++;request?.cancel(true);executor.shutdownNow();handler.removeCallbacksAndMessages(null)
        super.dismiss()
    }
}
