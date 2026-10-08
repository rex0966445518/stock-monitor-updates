package com.rex.twboardingscanner.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.rex.twboardingscanner.databinding.ItemStockSignalBinding
import com.rex.twboardingscanner.domain.RadarType
import com.rex.twboardingscanner.domain.SignalLight
import com.rex.twboardingscanner.domain.SignalResult

class SignalAdapter:RecyclerView.Adapter<SignalAdapter.VH>() {
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
        val accent = when(r.radarType) {
            RadarType.A_EARLY_BREAKOUT -> Color.parseColor("#FF496C")
            RadarType.B_DEEP_REVERSAL -> Color.parseColor("#F6A623")
            RadarType.C_LONG_RED_VOLUME -> Color.parseColor("#36A3FF")
        }

        h.b.root.strokeColor = accent
        h.b.root.strokeWidth = if (r.light == SignalLight.RED) 3 else 1
        h.b.badge.text = when(r.radarType) {
            RadarType.A_EARLY_BREAKOUT -> "A 起漲"
            RadarType.B_DEEP_REVERSAL -> "B 反轉"
            RadarType.C_LONG_RED_VOLUME -> "C 長紅爆量"
        }
        h.b.badge.background = GradientDrawable().apply {
            cornerRadius = 20f
            setColor(accent)
        }

        h.b.codeName.text = "${s.code}  ${s.name}"
        h.b.sector.text = s.sector.label
        h.b.price.text = String.format("%,.2f  %+.2f%%", s.price, s.changePct)
        h.b.price.setTextColor(if (s.changePct >= 0) Color.parseColor("#FF496C") else Color.parseColor("#2FD18A"))
        h.b.scoreRing.setScore(r.score, accent)
        h.b.chartView.setBars(s.bars)

        h.b.indicatorChips.removeAllViews()
        val macd = s.difRising && (s.macdGoldenCross || s.macdTurnedPositive || s.macdRedExpanding || s.macdNegBarsShrinking)
        val vr = if (s.avg20VolumeLots > 0) s.volumeLots / s.avg20VolumeLots else 0.0
        addCheck(h, "MACD", macd)
        addCheck(h, "MA20", s.ma20?.let { s.price >= it } == true)
        addCheck(h, "RSI", (s.rsi ?: 0.0) > 50)
        addCheck(h, "成交量", vr >= 1.2)
        addCheck(h, "EPS", (s.epsTtm ?: Double.NEGATIVE_INFINITY) > 0)

        h.b.trigger.text = if (r.reasons.isEmpty()) "尚未達主要觸發" else "觸發：${r.reasons.take(3).joinToString("＋")}"

        if (r.light == SignalLight.RED) {
            h.b.root.animate().alpha(.62f).setDuration(350).withEndAction {
                h.b.root.animate().alpha(1f).setDuration(350).start()
            }.start()
        } else {
            h.b.root.alpha = 1f
        }
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
