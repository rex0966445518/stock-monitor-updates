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

class SignalAdapter: RecyclerView.Adapter<SignalAdapter.VH>() {
    private val items = mutableListOf<SignalResult>()
    fun submit(list: List<SignalResult>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
    class VH(val b: ItemStockSignalBinding): RecyclerView.ViewHolder(b.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(ItemStockSignalBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = items[position]; val s = r.snapshot
        val accent = when(r.radarType){
            RadarType.A_EARLY_BREAKOUT -> Color.parseColor("#FF4768")
            RadarType.B_DEEP_REVERSAL -> Color.parseColor("#F6A623")
            RadarType.C_LONG_RED_VOLUME -> Color.parseColor("#36A3FF")
        }
        val type = when(r.radarType){
            RadarType.A_EARLY_BREAKOUT -> "A 起漲"
            RadarType.B_DEEP_REVERSAL -> "B 反轉"
            RadarType.C_LONG_RED_VOLUME -> "C 長紅爆量"
        }
        h.b.root.strokeColor = accent; h.b.root.strokeWidth = if(r.light==SignalLight.RED) 3 else 1
        h.b.badge.text = type
        h.b.badge.background = GradientDrawable().apply { cornerRadius=18f; setColor(accent) }
        h.b.codeName.text = "${s.code}  ${s.name}"
        h.b.sector.text = s.sector.label
        h.b.price.text = String.format("%,.2f  %+.2f%%", s.price, s.changePct)
        h.b.price.setTextColor(if(s.changePct>=0) Color.parseColor("#FF496C") else Color.parseColor("#2FD18A"))
        h.b.score.text = "${r.score}%"
        h.b.score.setTextColor(accent)
        h.b.chartView.setBars(s.bars)
        h.b.indicatorChips.removeAllViews()
        addChip(h, "MACD轉強", s.difRising && (s.macdGoldenCross || s.macdTurnedPositive || s.macdRedExpanding || s.macdNegBarsShrinking))
        addChip(h, "站上20MA", s.ma20?.let{s.price>=it})
        addChip(h, "RSI > 50", s.rsi?.let{it>50})
        val vr=if(s.avg20VolumeLots>0) s.volumeLots/s.avg20VolumeLots else 0.0
        addChip(h, "量增", if(s.avg20VolumeLots>0) vr>=1.2 else null)
        val chips = listOfNotNull(s.foreignToday,s.foreign3d,s.trust3d)
        addChip(h, "法人買超", if(chips.isEmpty()) null else chips.any{it>0})
        h.b.trigger.text = if(r.reasons.isEmpty()) "尚未達主要觸發" else "觸發：${r.reasons.take(3).joinToString("＋")}"
        if(r.blockers.any{it.contains("資料不足")}) h.b.trigger.append("  · 歷史資料累積中")
        if(r.light==SignalLight.RED){
            h.b.root.animate().alpha(.62f).setDuration(350).withEndAction{h.b.root.animate().alpha(1f).setDuration(350).start()}.start()
        } else h.b.root.alpha=1f
    }

    private fun addChip(h: VH, label:String, pass:Boolean?){
        val chip=Chip(h.b.root.context)
        chip.isClickable=false; chip.isCheckable=false; chip.textSize=11f
        when(pass){
            true -> { chip.text="✓ $label"; chip.chipBackgroundColor=ColorStateList.valueOf(Color.parseColor("#123B36")); chip.setTextColor(Color.parseColor("#69F0C2")) }
            false -> { chip.text="— $label"; chip.chipBackgroundColor=ColorStateList.valueOf(Color.parseColor("#263445")); chip.setTextColor(Color.parseColor("#AAB7C6")) }
            null -> { chip.text="… $label"; chip.chipBackgroundColor=ColorStateList.valueOf(Color.parseColor("#263445")); chip.setTextColor(Color.parseColor("#71869A")) }
        }
        h.b.indicatorChips.addView(chip)
    }
}
