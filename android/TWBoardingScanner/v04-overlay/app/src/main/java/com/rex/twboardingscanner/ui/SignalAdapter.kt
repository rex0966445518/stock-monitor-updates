package com.rex.twboardingscanner.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import com.google.android.material.chip.Chip
import com.rex.twboardingscanner.databinding.ItemStockSignalBinding
import com.rex.twboardingscanner.domain.CheckState
import com.rex.twboardingscanner.domain.RuleMetrics
import com.rex.twboardingscanner.domain.MainForceCost
import java.util.Locale
import kotlin.math.abs
import com.rex.twboardingscanner.domain.RadarType
import com.rex.twboardingscanner.domain.RsiGoldenCross
import com.rex.twboardingscanner.domain.SignalLight
import com.rex.twboardingscanner.domain.SignalResult

class SignalAdapter(private val onClick: (SignalResult) -> Unit = {}, private val onFinancial: (SignalResult) -> Unit = {}, private val onFinancialVisible:(SignalResult)->Unit = {}):RecyclerView.Adapter<SignalAdapter.VH>() {
    private val financialReports=mutableMapOf<String,com.rex.twboardingscanner.domain.FinancialReport>()
    private var scrolling=false
    private val deferredFinancialCodes=linkedSetOf<String>()
    private val mainHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private var submitVersion=0L
    private var disposed=false
    private val diffWorker=java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task,"scanner-list-diff").apply{isDaemon=true}
    }
    /** Shut down the per-screen diff worker when the Activity is destroyed. */
    fun close(){
        disposed=true
        submitVersion++
        deferredFinancialCodes.clear()
        mainHandler.removeCallbacksAndMessages(null)
        diffWorker.shutdownNow()
    }
    /** Do not relayout financial/chart cards while the user is dragging or flinging. */
    fun setScrolling(active:Boolean){
        if(disposed||scrolling==active)return
        scrolling=active
        if(!active&&deferredFinancialCodes.isNotEmpty()){
            val codes=deferredFinancialCodes.toSet()
            deferredFinancialCodes.clear()
            items.indices.filter{items[it].code in codes}.forEach{notifyItemChanged(it,"financial")}
        }
    }
    private val loadingFinancials=mutableSetOf<String>()
    fun financialReport(code:String)=financialReports[code]
    fun updateFinancial(code:String,report:com.rex.twboardingscanner.domain.FinancialReport?,loading:Boolean){
        if(disposed)return
        val changed=financialReports[code]!=report||(code in loadingFinancials)!=loading
        if(report!=null)financialReports[code]=report
        if(loading)loadingFinancials+=code else loadingFinancials-=code
        if(changed){
            if(scrolling)deferredFinancialCodes+=code
            else items.indices.filter{items[it].code==code}.forEach{notifyItemChanged(it,"financial")}
        }
    }
    private val items = mutableListOf<SignalResult>()
    init { setHasStableIds(true) }
    override fun getItemId(position:Int)=
        (items[position].code+"-"+items[position].radarType.name).hashCode().toLong() and 0xffffffffL


    /**
     * For large lists the nested chart/bar snapshots are expensive to diff.
     * Calculate large diffs off the UI thread and discard superseded work.
     * Small lists stay synchronous for immediate navigation and existing tests.
     */
    fun submit(list:List<SignalResult>) {
        if(disposed)return
        val target=list.toList()
        val generation=++submitVersion
        if(items.size==target.size&&items.indices.all{items[it]===target[it]})return
        val previous=items.toList()
        val callback=object:DiffUtil.Callback(){
            override fun getOldListSize()=previous.size
            override fun getNewListSize()=target.size
            override fun areItemsTheSame(oldItemPosition:Int,newItemPosition:Int):Boolean {
                val a=previous[oldItemPosition];val b=target[newItemPosition]
                return a.code==b.code&&a.radarType==b.radarType
            }
            override fun areContentsTheSame(oldItemPosition:Int,newItemPosition:Int)=
                previous[oldItemPosition]==target[newItemPosition]
        }
        fun apply(diff:DiffUtil.DiffResult){
            if(generation!=submitVersion)return
            items.clear()
            items.addAll(target)
            diff.dispatchUpdatesTo(this)
        }
        if(previous.size+target.size<=50) {
            apply(DiffUtil.calculateDiff(callback,false))
        } else {
            diffWorker.execute {
                val diff=DiffUtil.calculateDiff(callback,false)
                mainHandler.post{apply(diff)}
            }
        }
    }

    class VH(val b:ItemStockSignalBinding):RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(p:ViewGroup, v:Int) =
        VH(ItemStockSignalBinding.inflate(LayoutInflater.from(p.context), p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h:VH,pos:Int,payloads:MutableList<Any>){
        if(payloads.isNotEmpty()&&payloads.all{it=="financial"}){
            val result=items[pos]
            h.b.financialPanel.bind(financialReports[result.code]?:result.snapshot.sourceStock?.financials,
                RuleMetrics(result.snapshot).today,loading=result.code in loadingFinancials)
        }else onBindViewHolder(h,pos)
    }

    override fun onBindViewHolder(h:VH, pos:Int) {
        val r = items[pos]
        val s = r.snapshot
        h.b.root.isClickable = true
        h.b.root.isFocusable = true
        h.b.root.setOnClickListener { onClick(r) }
        h.b.chartView.setOnClickListener { onClick(r) }
        h.b.root.contentDescription = "${r.code} ${r.name}，點擊開啟籌碼K線 App 查看日K"
        val accent = NeonUi.radarColor(r.radarType)

        h.b.root.strokeColor = NeonUi.tint(accent,.80f)
        h.b.root.strokeWidth = (1.2f * h.b.root.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        h.b.root.animate().cancel()
        h.b.root.alpha = 1f
        h.b.badge.text = r.radarType.label
        h.b.badge.setTextColor(accent)
        h.b.badge.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(NeonUi.tint(accent,.34f),NeonUi.surface)).apply {
            cornerRadius = 16f * h.b.root.resources.displayMetrics.density
            setStroke((1f*h.b.root.resources.displayMetrics.density).toInt().coerceAtLeast(1),accent)
        }

        h.b.codeName.text = "${s.code}  ${s.name}"
        h.b.sector.text = s.sector.label
        h.b.price.text = String.format("%,.2f  %+.2f%%", s.price, s.changePct)
        h.b.price.setTextColor(if (s.changePct >= 0) NeonUi.pink else NeonUi.mint)
        val approximateCost=MainForceCost.estimate(s.bars)
        if(approximateCost==null){
            h.b.mainForceCost.text="估算主力成本（20日） · 日K／量能資料不足"
            h.b.mainForceCost.setTextColor(NeonUi.muted)
            h.b.mainForceCostNote.text="需要20個有效交易日的OHLCV，缺資料不推算"
        }else{
            val difference=approximateCost.distancePct(s.price)
            val relation=when{
                difference==null->"現價資料不足"
                difference<0.0->String.format(Locale.TAIWAN,"現價低 %.2f%%",abs(difference))
                difference>0.0->String.format(Locale.TAIWAN,"現價高 %.2f%%",difference)
                else->"現價等於估算成本"
            }
            h.b.mainForceCost.text=String.format(Locale.TAIWAN,
                "估算主力成本（20日） %,.2f 元 · %s",approximateCost.price,relation)
            h.b.mainForceCost.setTextColor(if(difference!=null&&difference<0.0)NeonUi.mint else NeonUi.cyan)
            h.b.mainForceCostNote.text="以日K典型價(H＋L＋C)/3按成交股數加權 · 至 ${approximateCost.to}；非實際大戶成本"
        }
        h.b.mainForceCost.contentDescription=h.b.mainForceCost.text.toString()+
            "；這是量價推算而非券商提供的主力帳戶實際平均持股成本"
        h.b.scoreRing.setScore(r.score, accent)
        h.b.chartView.showAxes = true
        h.b.chartView.setWindow(60)
        h.b.chartView.setBars(s.bars)
        VolumeStrip.bind(h.b.volumeSummary, h.b.recentVolumes, s.bars)

        // A C/D judgement can span thousands of characters. Never measure
        // that entire report inside a RecyclerView item while scrolling.
        val fullEvidence=r.dEvidence?.takeIf{it.isNotBlank()}
        h.b.dEvidence?.apply{
            if(fullEvidence==null){
                visibility=android.view.View.GONE
                setOnClickListener(null)
                isFocusable=false
                text=""
            }else{
                visibility=android.view.View.VISIBLE
                maxLines=5
                ellipsize=android.text.TextUtils.TruncateAt.END
                val excerpt=fullEvidence.replace(Regex("\\s+")," ").take(160)
                text=excerpt+if(fullEvidence.length>160)"…\n▾ 點擊查看完整條件判定" else "\n▾ 點擊查看完整條件判定"
                isClickable=true;isFocusable=true
                contentDescription="${r.code} ${r.name} 的詳細條件判定，點擊閱讀全部內容"
                setOnClickListener {
                    val dialogContent=android.widget.ScrollView(context).apply{
                        isFillViewport=false
                        setPadding(NeonUi.dp(context,14),NeonUi.dp(context,8),NeonUi.dp(context,14),NeonUi.dp(context,6))
                        addView(NeonUi.label(context,fullEvidence,13f,NeonUi.ink).apply{
                            setTextIsSelectable(true)
                            setLineSpacing(NeonUi.dp(context,3).toFloat(),1.1f)
                        })
                    }
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                        .setTitle("${r.code} ${r.name} · ${r.radarType.label} 判定")
                        .setView(dialogContent)
                        .setNegativeButton("關閉",null)
                        .setPositiveButton("複製說明"){_,_->
                            val clipboard=context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("股票條件判定",fullEvidence))
                        }
                        .show()
                }
            }
        }
        h.b.closingAuction.bind(s.sourceStock?.closingAuction)
        h.b.financialPanel.bind(financialReports[r.code]?:s.sourceStock?.financials,RuleMetrics(s).today,loading=r.code in loadingFinancials)
        onFinancialVisible(r)
        h.b.financialButton.setOnClickListener { onFinancial(r) }
        h.b.indicatorChips.removeAllViews()
        r.checks.filter { it.selected }.forEach { check ->
            val short = mapOf(
                "price" to "股價≥50", "volume" to "成交量≥500", "macd" to "MACD起轉",
                "heat" to "未過熱", "converge" to "三線收斂", "ma5up" to "5日線翻揚",
                "above3" to "站上三線", "rsi" to "RSI>50", "contract" to "整理量縮",
                "box" to "突破箱頂", "burst" to "突破放量", "drawdown" to "深跌≥30%",
                "floor" to "底部守住", "higherLow" to "低點墊高", "declineVolume" to "下跌量縮",
                "ma5" to "站回5日線", "red" to "紅K", "gain" to "日漲≥3%", "body" to "實體≥3%",
                "closeHigh" to "收在高檔", "extra_eps" to "EPS／營收", "extra_flow" to "法人",
                "extra_risk" to "風險結構", "extra_high" to "避開高點", "extra_limit" to "避開漲停",
                "d_limit" to "曾觸漲停", "d_green" to "連陽", "d_gap" to "向上缺口",
                "d_unfilled" to "缺口未回補", "d_volume" to "連續放量",
                "d_relative" to "勝過大盤",
                "below_main_force_cost_20" to "低於估算主力成本",
                RsiGoldenCross.ID to "RSI<35黃金交叉"
            )[check.id] ?: check.label.substringBefore("：")
            addCheck(h, short, check.state, check.selected)
        }
        r.exclusionChecks.filter{it.selected}.forEach{check->addCheck(h,"排除規則1（未命中才通過）",check.state,true)}
        val date = s.bars.lastOrNull()?.let { RuleMetrics.tradingDate(it.time).toString() } ?: "無資料"
        val upgraded = if (r.technicalUpgrade) "｜技術升級" else ""
        val mode = if (r.radarType == RadarType.C_LONG_RED_VOLUME||r.radarType==RadarType.D_MAIN_FORCE)
            "截至最新可得日K（當日資料可能未完成）" else "排除當日"
        h.b.trigger.text = "$date · $mode$upgraded" +
            if(r.radarType==RadarType.C_LONG_RED_VOLUME) "\n長紅爆量觀察訊號 · 無隔日續漲保證" else ""



    }

    private fun addCheck(h:VH, label:String, state:CheckState, selected:Boolean) {

        val chip = Chip(h.b.root.context).apply {
            text = when(state) { CheckState.PASS -> "✓ $label"; CheckState.FAIL -> "× $label"; CheckState.PENDING -> "? $label 待查核" } + if (selected) "" else "（未啟用）"
            textSize = 11f
            isClickable = false
            isCheckable = false
            chipMinHeight = 28 * h.b.root.resources.displayMetrics.density
            setEnsureMinTouchTargetSize(false)
            contentDescription = label
            val accent=when(state){
                CheckState.PASS->NeonUi.mint
                CheckState.FAIL->NeonUi.neonRed
                CheckState.PENDING->NeonUi.amber
            }
            chipBackgroundColor = ColorStateList.valueOf(NeonUi.tint(accent,.20f))
            chipStrokeColor = ColorStateList.valueOf(NeonUi.tint(accent,.65f))
            chipStrokeWidth = 1f * h.b.root.resources.displayMetrics.density
            setTextColor(accent)
            if (android.os.Build.VERSION.SDK_INT >= 26) tooltipText = label
        }
        h.b.indicatorChips.addView(chip)
    }
}
