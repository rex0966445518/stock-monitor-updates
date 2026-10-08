package com.rex.twboardingscanner.ui

import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.rex.twboardingscanner.domain.DailyBar
import com.rex.twboardingscanner.domain.RuleMetrics
import com.rex.twboardingscanner.domain.VolumeStats
import java.util.Locale

object VolumeStrip {
    fun bind(summary: TextView, row: LinearLayout, input: List<DailyBar>) {
        row.removeAllViews()
        val stats=VolumeStats.from(input)
        if(stats.latestLots==null) {
            summary.text="舊版紀錄未保存量能圖表；點擊可查詢最新資料"
            return
        }
        val date=RuleMetrics.tradingDate(stats.recent.last().time)
        val change=stats.changePct?.let { String.format(Locale.TAIWAN,"%+.1f%%",it) } ?: "待查核"
        val ratio=stats.ratio20?.let { String.format(Locale.TAIWAN,"%.2f倍",it) } ?: "待查核"
        summary.text="$date 日K成交量 ${String.format(Locale.TAIWAN,"%,.3f",stats.latestLots)} 張\n較前日 $change｜前20日均量 $ratio\n最近 ${stats.recent.size} 個交易日成交量（張）"
        stats.recent.forEachIndexed { i,bar ->
            val label=TextView(row.context).apply {
                val day=RuleMetrics.tradingDate(bar.time).toString().substring(5).replace('-','/')
                text="$day\n"+String.format(Locale.TAIWAN,"%,.1f",bar.volumeShares/1000.0)
                textSize=10f;gravity=Gravity.CENTER
                setPadding(0,8,0,8)
                setTextColor(if(i==stats.recent.lastIndex) Color.rgb(110,235,255) else Color.rgb(163,193,216))
                setBackgroundColor(if(i==stats.recent.lastIndex) Color.rgb(14,53,75) else Color.rgb(8,31,50))
            }
            row.addView(label,LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f).apply { marginEnd=2 })
        }
    }
}
