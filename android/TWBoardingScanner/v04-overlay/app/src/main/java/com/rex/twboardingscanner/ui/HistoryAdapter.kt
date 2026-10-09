package com.rex.twboardingscanner.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.rex.twboardingscanner.data.HistoryRow
import com.rex.twboardingscanner.databinding.ItemHistorySignalBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(private val onClick: (HistoryRow) -> Unit = {}): RecyclerView.Adapter<HistoryAdapter.VH>() {
    private val items = mutableListOf<HistoryRow>()

    fun submit(v:List<HistoryRow>) {
        items.clear()
        items.addAll(v)
        notifyDataSetChanged()
    }

    class VH(val b:ItemHistorySignalBinding):RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(p:ViewGroup, v:Int) =
        VH(ItemHistorySignalBinding.inflate(LayoutInflater.from(p.context), p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h:VH, pos:Int) {
        val r = items[pos]
        h.b.root.isClickable = true
        h.b.root.isFocusable = true
        h.b.root.setOnClickListener { onClick(r) }
        h.b.root.contentDescription = "${r.code} ${r.name} 歷史紀錄，點擊查詢最新日線圖表"
        val accent = when {
            r.radar.startsWith("A_") -> Color.parseColor("#FF496C")
            r.radar.startsWith("B_") -> Color.parseColor("#F6A623")
            else -> Color.parseColor("#36A3FF")
        }

        h.b.root.strokeColor = accent
        h.b.badge.text = when {
            r.radar.startsWith("A_") -> "A"
            r.radar.startsWith("B_") -> "B"
            else -> "C"
        }
        h.b.badge.background = GradientDrawable().apply {
            cornerRadius = 22f
            setColor(accent)
        }
        h.b.codeName.text = "${r.code}  ${r.name}"
        h.b.meta.text = "${r.sector}  ·  ${SimpleDateFormat("MM/dd HH:mm", Locale.TAIWAN).format(Date(r.ts))}"
        h.b.price.text = String.format("%,.2f  %+.2f%%", r.price, r.changePct)
        h.b.price.setTextColor(if (r.changePct >= 0) Color.parseColor("#FF496C") else Color.parseColor("#2FD18A"))
        h.b.scoreRing.setScore(r.score, accent)
        h.b.chartView.showAxes = true
        h.b.chartView.setWindow(60)
        h.b.chartView.setBars(r.chartBars)
        h.b.chartView.visibility = if (r.chartBars.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        h.b.chartView.setOnClickListener { onClick(r) }
        VolumeStrip.bind(h.b.volumeSummary, h.b.recentVolumes, r.chartBars)

        h.b.closingAuction.bind(r.closingAuction)
        h.b.indicatorChips.removeAllViews()
        h.b.reasons.text = if (r.ruleReport.isNotBlank()) r.reasons + "\n" + r.ruleReport
            else "舊版掃描紀錄（不套用新規則）\n" + r.reasons.replace("+", " · ")
    }

    private fun addCheck(h:VH, label:String, pass:Boolean) {
        if (!pass) return
        val chip = Chip(h.b.root.context).apply {
            text = "✓"
            textSize = 16f
            isClickable = false
            isCheckable = false
            contentDescription = label
            chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#123B36"))
            setTextColor(Color.parseColor("#69F0C2"))
            if (android.os.Build.VERSION.SDK_INT >= 26) tooltipText = label
        }
        h.b.indicatorChips.addView(chip)
    }
}
