package com.rex.twboardingscanner.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.rex.twboardingscanner.databinding.ItemStockSignalBinding
import com.rex.twboardingscanner.domain.CheckState
import com.rex.twboardingscanner.domain.RuleMetrics
import com.rex.twboardingscanner.domain.RadarType
import com.rex.twboardingscanner.domain.SignalLight
import com.rex.twboardingscanner.domain.SignalResult

class SignalAdapter(private val onClick: (SignalResult) -> Unit = {}, private val onFinancial: (SignalResult) -> Unit = {}):RecyclerView.Adapter<SignalAdapter.VH>() {
    private val items = mutableListOf<SignalResult>()

    fun submit(list:List<SignalResult>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val b:ItemStockSignalBinding):RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(p:ViewGroup, v:Int) =
        VH(ItemStockSignalBinding.inflate(LayoutInflater.from(p.context), p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h:VH, pos:Int) {
        val r = items[pos]
        val s = r.snapshot
        h.b.root.isClickable = true
        h.b.root.isFocusable = true
        h.b.root.setOnClickListener { onClick(r) }
        h.b.chartView.setOnClickListener { onClick(r) }
        h.b.root.contentDescription = "${r.code} ${r.name}，點擊查看日K、成交量與MACD"
        val accent = when(r.radarType) {
            RadarType.A_EARLY_BREAKOUT -> NeonUi.pink
            RadarType.B_DEEP_REVERSAL -> NeonUi.amber
            RadarType.C_LONG_RED_VOLUME -> NeonUi.cyan
        }

        h.b.root.strokeColor = NeonUi.border
        h.b.root.strokeWidth = (1 * h.b.root.resources.displayMetrics.density).toInt()
        h.b.root.animate().cancel()
        h.b.root.alpha = 1f
        h.b.badge.text = when(r.radarType) {
            RadarType.A_EARLY_BREAKOUT -> "A 起漲"
            RadarType.B_DEEP_REVERSAL -> "B 反轉"
            RadarType.C_LONG_RED_VOLUME -> "C 長紅爆量"
        }
        h.b.badge.setTextColor(accent)
        h.b.badge.background = GradientDrawable().apply {
            cornerRadius = 20f
            setColor(Color.argb(35,Color.red(accent),Color.green(accent),Color.blue(accent)))
        }

        h.b.codeName.text = "${s.code}  ${s.name}"
        h.b.sector.text = s.sector.label
        h.b.price.text = String.format("%,.2f  %+.2f%%", s.price, s.changePct)
        h.b.price.setTextColor(if (s.changePct >= 0) NeonUi.pink else NeonUi.mint)
        h.b.scoreRing.setScore(r.score, accent)
        h.b.chartView.showAxes = true
        h.b.chartView.setWindow(60)
        h.b.chartView.setBars(s.bars)
        VolumeStrip.bind(h.b.volumeSummary, h.b.recentVolumes, s.bars)

        h.b.closingAuction.bind(s.sourceStock?.closingAuction)
        h.b.financialPanel.bind(s.sourceStock?.financials,RuleMetrics(s).today)
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
                "extra_risk" to "風險結構", "extra_high" to "避開高點", "extra_limit" to "避開漲停"
            )[check.id] ?: check.label.substringBefore("：")
            addCheck(h, short, check.state, check.selected)
        }
        val date = s.bars.lastOrNull()?.let { RuleMetrics.tradingDate(it.time).toString() } ?: "無資料"
        val upgraded = if (r.technicalUpgrade) "｜技術升級" else ""
        val mode = if (r.radarType == RadarType.C_LONG_RED_VOLUME) "最新日K（可能更新）" else "排除當日"
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
