package com.rex.twboardingscanner.domain

/** Volume uses shares throughout; fractional lots remain visible. */
data class VolumeStats(val recent: List<DailyBar>, val latestLots: Double?, val changePct: Double?, val ratio20: Double?) {
    companion object {
        fun from(input: List<DailyBar>): VolumeStats {
            val bars=ChartSeries.prepare(input).map { it.bar }
            val latest=bars.lastOrNull() ?: return VolumeStats(emptyList(),null,null,null)
            val previous=bars.dropLast(1)
            val priorVolume=previous.lastOrNull()?.volumeShares?.takeIf { it > 0 }
            val average=previous.takeIf { it.size >= 20 }?.takeLast(20)?.map { it.volumeShares.toDouble() }?.average()?.takeIf { it > 0 }
            return VolumeStats(bars.takeLast(5),latest.volumeShares/1000.0,
                priorVolume?.let { (latest.volumeShares.toDouble()/it-1)*100 },
                average?.let { latest.volumeShares/it })
        }
    }
}
