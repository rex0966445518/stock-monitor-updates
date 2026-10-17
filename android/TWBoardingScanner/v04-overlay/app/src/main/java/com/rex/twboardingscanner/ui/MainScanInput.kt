package com.rex.twboardingscanner.ui

import com.rex.twboardingscanner.domain.*

/** Allows the entire activity scan path to be tested with a complete offline market. */
internal interface MainScanInput {
    fun universe(progress:(String)->Unit):List<MarketStock>
    fun history(stock:MarketStock):List<DailyBar>
    fun auction(stock:MarketStock):com.rex.twboardingscanner.domain.ClosingAuction =
        com.rex.twboardingscanner.domain.ClosingAuction(note="離線來源未提供收盤撮合")
    fun guard():MarketGuardDecision
}
