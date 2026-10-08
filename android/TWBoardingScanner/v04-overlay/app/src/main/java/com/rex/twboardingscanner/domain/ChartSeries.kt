package com.rex.twboardingscanner.domain

/** Indicators are calculated before choosing the visible time window. */
data class ChartPoint(val bar: DailyBar, val ma5: Double?, val ma10: Double?, val ma20: Double?,
    val dif: Double?, val dea: Double?, val histogram: Double?)

object ChartSeries {
    fun prepare(input: List<DailyBar>): List<ChartPoint> {
        val bars = input.filter { b ->
            listOf(b.open,b.high,b.low,b.close).all { it.isFinite() && it > 0 } &&
                b.high >= maxOf(b.open,b.close,b.low) && b.low <= minOf(b.open,b.close,b.high) && b.volumeShares >= 0
        }.sortedBy { it.time }.groupBy { RuleMetrics.tradingDate(it.time) }.values.map { it.last() }
        var fast = 0.0; var slow = 0.0; var signal = 0.0
        return bars.mapIndexed { i, b ->
            fast = if (i == 0) b.close else b.close * 2 / 13 + fast * 11 / 13
            slow = if (i == 0) b.close else b.close * 2 / 27 + slow * 25 / 27
            val dif = fast - slow
            signal = if (i == 0) dif else dif * 2 / 10 + signal * 8 / 10
            fun ma(n: Int): Double? = if (i + 1 < n) null else bars.subList(i + 1 - n, i + 1).map { it.close }.average()
            ChartPoint(b, ma(5), ma(10), ma(20), if (i >= 29) dif else null,
                if (i >= 29) signal else null, if (i >= 29) dif - signal else null)
        }
    }
    fun goodinfoUrl(code: String): String {
        require(code.matches(Regex("[0-9]{4}")))
        return "https://goodinfo.tw/tw/ShowK_Chart.asp?STOCK_ID=$code"
    }
}
