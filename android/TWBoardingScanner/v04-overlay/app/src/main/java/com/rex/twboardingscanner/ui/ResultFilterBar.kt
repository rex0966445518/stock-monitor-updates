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
            3 -> r.radarType==RadarType.D_MAIN_FORCE && r.light!=SignalLight.NONE
            4 -> (r.checks+r.exclusionChecks).any { it.selected && it.state == CheckState.PENDING }
            5 -> (r.checks+r.exclusionChecks).any { it.selected && it.state == CheckState.FAIL }
             6 -> r.light != SignalLight.NONE
            8 -> r.radarType==RadarType.F_VOLUME_CONTRACTION && r.light!=SignalLight.NONE
            7 -> r.radarType==RadarType.E_DIF_TOUCH && r.light!=SignalLight.NONE
            else -> false
        }}.sortedByDescending { it.score }
}

class ResultFilterBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    var onSelected: ((Int) -> Unit)? = null
    private val titles = listOf("A 起漲", "B 反轉", "C 爆量", "D 主力", "待查核", "未通過", "全部入選", "E DIF 接觸", "F 縮量上漲")
    private val accents = listOf(NeonUi.pink, NeonUi.amber, NeonUi.cyan, NeonUi.mint, NeonUi.amber, NeonUi.muted, NeonUi.cyan, NeonUi.pink, NeonUi.mint)
    private val buttons = titles.mapIndexed { index, title ->
        NeonUi.button(context, title, accents[index]) { onSelected?.invoke(index) }.apply {
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setSingleLine(false)
            maxLines = 2
            minimumWidth = 0
            minHeight=NeonUi.dp(context,36)
            minimumHeight=NeonUi.dp(context,36)
            insetTop=0;insetBottom=0
            setPadding(NeonUi.dp(context,3),NeonUi.dp(context,2),NeonUi.dp(context,3),NeonUi.dp(context,2))
        }
    }
    init {
        orientation = VERTICAL
        addView(NeonUi.row(context, buttons.take(4)))
        addView(NeonUi.gap(context, 3))
        addView(NeonUi.row(context, listOf(buttons[6],buttons[7],buttons[8],buttons[4],buttons[5])))
        update(List(9) { 0 }, 6)
    }
    fun update(counts: List<Int>, selected: Int) {
        buttons.forEachIndexed { i, button ->
            val active = i == selected
            val color = accents[i]
            button.text = "${titles[i]}  ${counts.getOrElse(i) { 0 }}"
            button.isSelected = active
            button.strokeWidth = NeonUi.dp(context, 1).coerceAtLeast(1)
            button.strokeColor = ColorStateList.valueOf(if(active) color else NeonUi.border)
            button.backgroundTintList = ColorStateList.valueOf(if(active)
                Color.rgb((Color.red(color)*.23).toInt(), (Color.green(color)*.23).toInt()+12, (Color.blue(color)*.23).toInt()+20)
                else NeonUi.surface)
            button.setTextColor(if(active) NeonUi.ink else NeonUi.muted)
            button.contentDescription = "${titles[i]}，${counts.getOrElse(i) { 0 }} 筆${if(active) "，已選取" else ""}"
        }
    }
}
