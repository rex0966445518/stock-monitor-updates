package com.rex.twboardingscanner.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.rex.twboardingscanner.domain.*

internal object ResultFilters {
    fun select(results: List<SignalResult>, index: Int): List<SignalResult> = results
        .distinctBy { "${it.code}_${it.radarType}" }
        .filter { r -> when(index) {
            0 -> r.radarType == RadarType.A_EARLY_BREAKOUT && r.light != SignalLight.NONE
            1 -> r.radarType == RadarType.B_DEEP_REVERSAL && r.light != SignalLight.NONE
            2 -> r.radarType == RadarType.C_LONG_RED_VOLUME && r.light != SignalLight.NONE
            3 -> r.checks.any { it.selected && it.state == CheckState.PENDING }
            4 -> r.checks.any { it.selected && it.state == CheckState.FAIL }
            else -> false
        }}.sortedByDescending { it.score }
}

class ResultFilterBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    var onSelected: ((Int) -> Unit)? = null
    private val titles = listOf("A 起漲", "B 反轉", "C 爆量", "待查核", "未通過")
    private val accents = listOf(NeonUi.pink, NeonUi.amber, NeonUi.cyan, NeonUi.amber, NeonUi.muted)
    private val buttons = titles.mapIndexed { index, title ->
        NeonUi.button(context, title, accents[index]) { onSelected?.invoke(index) }.apply {
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setSingleLine(false)
            maxLines = 2
            minimumWidth = 0
        }
    }
    init {
        orientation = VERTICAL
        addView(NeonUi.row(context, buttons.take(3)))
        addView(NeonUi.gap(context, 6))
        addView(NeonUi.row(context, buttons.drop(3)))
        update(List(5) { 0 }, 0)
    }
    fun update(counts: List<Int>, selected: Int) {
        buttons.forEachIndexed { i, button ->
            val active = i == selected
            val color = accents[i]
            button.text = "${titles[i]}  ${counts.getOrElse(i) { 0 }}"
            button.isSelected = active
            button.strokeWidth = NeonUi.dp(context, if(active) 2 else 1).coerceAtLeast(1)
            button.strokeColor = ColorStateList.valueOf(if(active) color else Color.rgb(39,69,92))
            button.backgroundTintList = ColorStateList.valueOf(if(active)
                Color.rgb((Color.red(color)*.23).toInt(), (Color.green(color)*.23).toInt()+12, (Color.blue(color)*.23).toInt()+20)
                else Color.rgb(9,28,45))
            button.setTextColor(if(active) NeonUi.ink else color)
            button.contentDescription = "${titles[i]}，${counts.getOrElse(i) { 0 }} 筆${if(active) "，已選取" else ""}"
        }
    }
}
