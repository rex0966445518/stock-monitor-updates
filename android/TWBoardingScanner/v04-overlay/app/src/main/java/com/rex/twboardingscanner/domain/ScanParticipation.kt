package com.rex.twboardingscanner.domain

import com.rex.twboardingscanner.backtest.BtSettings

/** Keep scanner outputs separate from an explicitly running paper strategy. */
class ScanParticipation(
    private val enabled:Set<RadarType>,
    private val sectors:Set<StockSector>,
    paperEnabled:Boolean=false,
    savedPaperSettings:BtSettings?=null
) {
    val paperSettings=savedPaperSettings.takeIf{paperEnabled}

    fun <T:Any> evaluate(sector:StockSector, calculate:(RadarType)->T?):List<T> =
        RadarType.entries.filter { type ->
            (sector in sectors && type in enabled) ||
                (paperSettings?.let{sector in it.sectors && type in it.enabledRadars}==true)
        }.mapNotNull(calculate)

    fun mainResults(results:List<SignalResult>)=results.filter{it.radarType in enabled && it.sector in sectors}
    fun paperResults(results:List<SignalResult>)=results.filter{r->
        paperSettings?.let{r.radarType in it.enabledRadars && r.sector in it.sectors}==true
    }
}
