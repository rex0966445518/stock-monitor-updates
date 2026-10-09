package com.rex.twboardingscanner.ui

import android.widget.LinearLayout
import android.widget.TextView
import com.rex.twboardingscanner.domain.DailyBar
import com.rex.twboardingscanner.domain.RuleMetrics
import com.rex.twboardingscanner.domain.VolumeStats
import java.util.Locale

object VolumeStrip {
    fun bind(summary:TextView,row:LinearLayout,input:List<DailyBar>) {
        row.removeAllViews();row.orientation=LinearLayout.VERTICAL
        val stats=VolumeStats.from(input)
        summary.textSize=12f
        if(stats.latestLots==null) { summary.text="量能資料待查核 · 點擊查看最新圖表";return }
        val c=row.context
        val date=RuleMetrics.tradingDate(stats.recent.last().time)
        summary.text="量能動態  ·  $date"
        val change=stats.changePct?.let{String.format(Locale.TAIWAN,"%+.1f%%",it)}?:"待查核"
        val ratio=stats.ratio20?.let{String.format(Locale.TAIWAN,"%.2f×",it)}?:"待查核"
        row.addView(NeonUi.row(c,listOf(
            NeonUi.tile(c,"最新成交量",String.format(Locale.TAIWAN,"%,.3f",stats.latestLots),"張",NeonUi.cyan),
            NeonUi.tile(c,"較前一日",change,"成交量增減",NeonUi.cyan),
            NeonUi.tile(c,"放量倍數",ratio,"前20日均量",NeonUi.pink))))
        row.addView(NeonUi.gap(c,8))
        val chart=MiniBarChartView(c).apply {
            entries=stats.recent.map { bar ->
                val value=bar.volumeShares/1000.0
                MiniBarChartView.Entry(RuleMetrics.tradingDate(bar.time).toString().substring(5).replace('-','/'),value,
                    if(value>=10000)String.format(Locale.TAIWAN,"%.1f萬",value/10000) else String.format(Locale.TAIWAN,"%,.1f",value))
            }
            contentDescription="最近五個交易日成交量，單位張："+stats.recent.joinToString("；"){"${RuleMetrics.tradingDate(it.time)} ${it.volumeShares/1000.0}張"}
        }
        row.addView(chart,LinearLayout.LayoutParams(-1,NeonUi.dp(c,100)))
    }
}
