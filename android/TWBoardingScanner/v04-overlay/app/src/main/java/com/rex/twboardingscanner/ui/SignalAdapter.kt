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
    private val loadingFinancials=mutableSetOf<String>()
    fun financialReport(code:String)=financialReports[code]
    fun updateFinancial(code:String,report:com.rex.twboardingscanner.domain.FinancialReport?,loading:Boolean){
        val changed=financialReports[code]!=report||(code in loadingFinancials)!=loading
        if(report!=null)financialReports[code]=report
        if(loading)loadingFinancials+=code else loadingFinancials-=code
        if(changed)items.indices.filter{items[it].code==code}.forEach{notifyItemChanged(it,"financial")}
    }
    private val items = mutableListOf<SignalResult>()
    init { setHasStableIds(true) }
    override fun getItemId(position:Int)=
        (items[position].code+"-"+items[position].radarType.name).hashCode().toLong() and 0xffffffffL

    fun submit(list:List<SignalResult>) {
        val target=list.toList()
        if(items==target)return
        val previous=items.toList()
        val diff=DiffUtil.calculateDiff(object:DiffUtil.Callback(){
            override fun getOldListSize()=previous.size
            override fun getNewListSize()=target.size
            override fun areItemsTheSame(oldItemPosition:Int,newItemPosition:Int):Boolean {
                val a=previous[oldItemPosition];val b=target[newItemPosition]
                return a.code==b.code&&a.radarType==b.radarType
            }
            override fun areContentsTheSame(oldItemPosition:Int,newItemPosition:Int)=
                previous[oldItemPosition]==target[newItemPosition]
        },false)
        items.clear();items.addAll(target)
        diff.dispatchUpdatesTo(this)
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

        h.b.root.strokeColor = NeonUi.border
        h.b.root.strokeWidth = (1 * h.b.root.resources.displayMetrics.density).toInt()
        h.b.root.animate().cancel()
        h.b.root.alpha = 1f
        h.b.badge.text = r.radarType.label
        h.b.badge.setTextColor(accent)
        h.b.badge.background = GradientDrawable().apply {
            cornerRadius = 20f
            setColor(Color.argb(35,Color.red(accent),Color.green(accent),Color.blue(accent)))
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

        h.b.dEvidence?.visibility=if(r.dEvidence!=null)android.view.View.VISIBLE else android.view.View.GONE
        h.b.dEvidence?.text=r.dEvidence.orEmpty()
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
            chipBackgroundColor = ColorStateList.valueOf(Color.parseColor(if (state == CheckState.PASS) "#103A38" else "#13293F"))
            setTextColor(Color.parseColor(if (state == CheckState.PASS) "#69F0C2" else "#8CA6C0"))
            if (android.os.Build.VERSION.SDK_INT >= 26) tooltipText = label
        }
        h.b.indicatorChips.addView(chip)
    }
}
