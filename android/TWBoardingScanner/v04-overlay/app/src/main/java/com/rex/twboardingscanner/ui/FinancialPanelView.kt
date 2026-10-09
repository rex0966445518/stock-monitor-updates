package com.rex.twboardingscanner.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import com.rex.twboardingscanner.domain.*
import java.time.LocalDate
import java.util.Locale

class FinancialPanelView @JvmOverloads constructor(context:Context,attrs:AttributeSet?=null):LinearLayout(context,attrs) {
    init { orientation=VERTICAL }
    fun bind(report:FinancialReport?,today:LocalDate,expanded:Boolean=false) {
        removeAllViews()
        val eps=report?.ttm(today)
        val cash=report?.latest(report.operatingCash,today)
        val margin=report?.latest(report.operatingMargin,today)
        val positive=report?.fourPositive(today)
        val cashText=cash?.let{if(it.value>0) "✓ 正值" else if(it.value<0) "! 負值" else "零"}?:"待查核"
        val cashColor=if(cash==null)NeonUi.muted else if(cash.value>0)NeonUi.mint else NeonUi.amber
        addView(NeonUi.label(context,if(expanded) "財務指標" else "基本面快覽  ·  HiStock",12f,NeonUi.cyan,true).apply { setPadding(0,NeonUi.dp(context,12),0,NeonUi.dp(context,8)) })
        val e=NeonUi.tile(context,"近四季 EPS",eps?.let{String.format(Locale.TAIWAN,"%.2f",it)}?:"待查核","四季合計 · 元",if(eps==null)NeonUi.muted else if(eps>0)NeonUi.pink else NeonUi.amber,expanded)
        val m=NeonUi.tile(context,"營業利益率",margin?.let{String.format(Locale.TAIWAN,"%.2f%%",it.value)}?:"待查核",margin?.label?:"最新列示期間",if(margin==null)NeonUi.muted else if(margin.value>0)NeonUi.cyan else NeonUi.amber,expanded)
        val c=NeonUi.tile(context,"營業現金流",cashText,cash?.label?:"最新列示期間",cashColor,expanded)
        if(expanded) {
            addView(NeonUi.row(context,listOf(e,m)))
            addView(NeonUi.gap(context,8))
            val p=NeonUi.tile(context,"連續四季獲利",when(positive){true->"4 / 4";false->"未通過";null->"待查核"},"每季 EPS > 0",when(positive){true->NeonUi.mint;false->NeonUi.amber;null->NeonUi.muted},true)
            addView(NeonUi.row(context,listOf(c,p)))
            addView(NeonUi.gap(context,14))
            val graph=NeonUi.vertical(context).apply {
                background=NeonUi.panel(context,NeonUi.pink);setPadding(NeonUi.dp(context,12),NeonUi.dp(context,12),NeonUi.dp(context,12),NeonUi.dp(context,12))
                addView(NeonUi.label(context,"單季 EPS 趨勢",14f,NeonUi.ink,true))
                addView(NeonUi.label(context,"最近四個連續季度 · 元",11f))
                addView(MiniBarChartView(context).apply {
                    accent=NeonUi.pink
                    entries=report?.quarters(4,today)?.map { MiniBarChartView.Entry("${it.year%100}Q${it.quarter}",it.eps,"%.2f".format(Locale.TAIWAN,it.eps)) }.orEmpty()
                },LayoutParams(-1,NeonUi.dp(context,145)))
            }
            addView(graph)
            addView(NeonUi.gap(context,14))
            addView(NeonUi.label(context,"財務條件逐項查核",14f,NeonUi.ink,true))
            addView(NeonUi.label(context,"狀態僅供觀察；是否篩選依各區勾選設定",11f))
            val observations=listOf(
                "近四季 EPS 合計為正" to eps?.let{it>0},
                "連續四季 EPS 均為正" to positive,
                "近四季 EPS 高於前四季" to report?.growth(today),
                "三年 EPS 為正且逐年增加" to report?.stableYears(3,today),
                "五年 EPS 為正且逐年增加" to report?.stableYears(5,today),
                "最新期間本業利益率為正" to margin?.value?.let{it>0},
                "最新期間營業現金流為正" to cash?.value?.let{it>0})
            observations.forEach { (label,state) ->
                val color=when(state){true->NeonUi.mint;false->NeonUi.amber;null->NeonUi.muted}
                val row=LinearLayout(context).apply { gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(0,NeonUi.dp(context,10),0,NeonUi.dp(context,10)) }
                row.addView(NeonUi.label(context,when(state){true->"✓";false->"!";null->"?"},16f,color,true),LayoutParams(NeonUi.dp(context,26),-2))
                row.addView(NeonUi.label(context,label,12f,NeonUi.ink),LayoutParams(0,-2,1f))
                row.addView(NeonUi.label(context,when(state){true->"通過";false->"未通過";null->"待查核"},11f,color))
                addView(row)
                addView(View(context).apply{setBackgroundColor(android.graphics.Color.rgb(24,47,68))},LayoutParams(-1,NeonUi.dp(context,1)))
            }
        } else {
            addView(NeonUi.row(context,listOf(e,m,c)))
            val status=when {
                report==null -> "○ 尚未查核 · 點下方查看財報"
                report.messages.isNotEmpty() -> "! 部分資料待查核 · 查看原因 ›"
                eps==null||cash==null||margin==null -> "○ 期間缺漏或過期 · 查看明細 ›"
                else -> "✓ 財報已讀取 · 查看四季 EPS 趨勢 ›"
            }
            addView(NeonUi.label(context,status,11f,if(report?.messages?.isNotEmpty()==true)NeonUi.amber else NeonUi.muted).apply { setPadding(0,NeonUi.dp(context,8),0,NeonUi.dp(context,4)) })
        }
    }
}
