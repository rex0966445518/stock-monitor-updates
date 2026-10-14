package com.rex.twboardingscanner.ui

import com.rex.twboardingscanner.domain.*

/** Allows the entire activity scan path to be tested with a complete offline market. */
internal interface MainScanInput {
    fun universe(progress:(String)->Unit):List<MarketStock>
    fun history(stock:MarketStock):List<DailyBar>
    fun guard():MarketGuardDecision
}
