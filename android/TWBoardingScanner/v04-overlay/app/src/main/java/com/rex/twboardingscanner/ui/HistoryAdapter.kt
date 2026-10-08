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

class HistoryAdapter: RecyclerView.Adapter<HistoryAdapter.VH>() {
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

        h.b.indicatorChips.removeAllViews()
        addCheck(h, "MACD", r.macdPass)
        addCheck(h, "MA20", r.ma20Pass)
        addCheck(h, "RSI", r.rsiPass)
        addCheck(h, "成交量", r.volumePass)
        if (r.epsPass == true) addCheck(h, "EPS", true)

        h.b.reasons.text = if (r.reasons.isBlank()) "無觸發說明" else r.reasons.replace("+", " · ")
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
