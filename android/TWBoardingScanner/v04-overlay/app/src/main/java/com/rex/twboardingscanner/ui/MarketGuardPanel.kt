package com.rex.twboardingscanner.ui

import android.content.Context
import android.widget.LinearLayout
import com.rex.twboardingscanner.domain.MarketCrashGuard

internal class MarketGuardPanel(c:Context,subtitle:String="歷史交易逐日套用；不受今日大盤狀態影響"):LinearLayout(c){
    init {
        orientation=VERTICAL;tag="market-guard-panel";background=NeonUi.panel(c,NeonUi.amber)
        setPadding(NeonUi.dp(c,12),NeonUi.dp(c,12),NeonUi.dp(c,12),NeonUi.dp(c,12))
        addView(NeonUi.label(c,"大盤暴跌保護 · 新測試已啟用",15f,NeonUi.amber,true))
        addView(NeonUi.label(c,"跌逾 1,000 點 → 次日暫停買入",14f,NeonUi.ink,true))
        addView(NeonUi.label(c,"連漲 2 日 ＋ 收盤站回 5 日線\n確認後下一交易日恢復買入",12f,NeonUi.cyan))
        addView(NeonUi.label(c,"保護期間仍可賣出；資料不足先暫停買入。",11f))
        addView(NeonUi.label(c,subtitle,11f))
        contentDescription=MarketCrashGuard.SUMMARY
    }
}
