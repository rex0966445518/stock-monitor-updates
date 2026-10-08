package com.rex.twboardingscanner.domain

import kotlin.math.abs

class TechnicalCalculator {
    fun build(stock: MarketStock, inputBars: List<DailyBar>): StockSnapshot {
        val bars = inputBars.sortedBy { it.time }
        val closes = bars.map { it.close }
        val lows = bars.map { it.low }
        val highs = bars.map { it.high }
        val volsLots = bars.map { it.volumeShares / 1000.0 }
        val ma5 = sma(closes, 5)
        val ma10 = sma(closes, 10)
        val ma20 = sma(closes, 20)
        val ma60 = sma(closes, 60)
        val prevMa5 = sma(closes.dropLast(1), 5)
        val rsi = rsi(closes, 14)
        val macd = macd(closes)
        val kd = stochastic(highs, lows, closes, 9)
        val avg20 = volsLots.takeLast(20).averageOrZero()
        val recentHigh = highs.dropLast(1).takeLast(20).maxOrNull()
        val high52 = highs.maxOrNull()
        val distRecent = recentHigh?.let { (stock.close / it - 1.0) * 100.0 }
        val dist52 = high52?.let { (stock.close / it - 1.0) * 100.0 }
        val fiveGain = closes.takeLast(6).let { if (it.size >= 6 && it.first() != 0.0) (it.last() / it.first() - 1.0) * 100.0 else null }
        val maDist = ma20?.let { if (it != 0.0) (stock.close / it - 1.0) * 100.0 else null }
        val last20Low = lows.takeLast(20).minOrNull()
        val prev20Low = if (lows.size >= 40) lows.dropLast(20).takeLast(20).minOrNull() else null
        val noNewLow = last20Low != null && (prev20Low == null || last20Low >= prev20Low * 0.97)
        val lowRecent10 = lows.takeLast(10).minOrNull()
        val lowPrev10 = if (lows.size >= 20) lows.dropLast(10).takeLast(10).minOrNull() else null
        val lowsRising = lowRecent10 != null && lowPrev10 != null && lowRecent10 > lowPrev10
        val volLast5 = volsLots.takeLast(5).averageOrZero()
        val volPrev5 = if (volsLots.size >= 10) volsLots.dropLast(5).takeLast(5).averageOrZero() else 0.0
        val volumeContract = volPrev5 > 0 && volLast5 < volPrev5 * 0.85
        val maConverging = listOfNotNull(ma5, ma10, ma20).let { m -> m.size == 3 && (m.maxOrNull()!! - m.minOrNull()!!) / (ma20 ?: 1.0) <= 0.035 }
        val fundamentalVerified = stock.epsTtm != null || stock.revenueYoY != null
        val fundamentalBad = (stock.epsTtm ?: 0.0) < 0 && (stock.revenueYoY ?: 0.0) < -10.0

        return StockSnapshot(
            code = stock.code, name = stock.name, sector = stock.sector, price = stock.close,
            openPrice = stock.open, highPrice = stock.high, lowPrice = stock.low, changePct = stock.changePct,
            volumeLots = stock.volumeLots, avg20VolumeLots = avg20,
            ma5 = ma5, ma10 = ma10, ma20 = ma20, ma60 = ma60,
            ma5SlopeUp = ma5 != null && prevMa5 != null && ma5 > prevMa5,
            maConverging = maConverging,
            rsi = rsi, kdOverheated = (kd?.first ?: 0.0) >= 80 || (kd?.second ?: 0.0) >= 80,
            dif = macd?.dif, signal = macd?.signal, osc = macd?.hist,
            difRising = macd?.difRising ?: false,
            macdGoldenCross = macd?.goldenCross ?: false,
            macdNegBarsShrinking = macd?.negShrinking ?: false,
            macdTurnedPositive = macd?.turnedPositive ?: false,
            macdRedExpanding = macd?.redExpanding ?: false,
            foreignToday = null, foreign3d = null, foreign5d = null, trust3d = null, trust5d = null,
            foreignSellingShrinking = false,
            nearResistance = distRecent != null && distRecent >= -3.0,
            breakoutResistance = recentHigh != null && stock.close > recentHigh,
            breakoutLine = recentHigh,
            recentHighDistancePct = distRecent,
            high52wDistancePct = dist52,
            drawdown52wPct = dist52,
            noNewLow20to60d = noNewLow,
            lowsRising = lowsRising,
            volumeContractingDuringDecline = volumeContract,
            epsTtm = stock.epsTtm,
            revenueYoY = stock.revenueYoY,
            fundamentalsDeteriorating = fundamentalBad,
            fundamentalsVerified = fundamentalVerified,
            fiveDayGainPct = fiveGain,
            distanceFromMa20Pct = maDist,
            bars = bars,
            timestamp = System.currentTimeMillis()
        )
    }

    data class MacdState(
        val dif: Double, val signal: Double, val hist: Double,
        val difRising: Boolean, val goldenCross: Boolean, val negShrinking: Boolean,
        val turnedPositive: Boolean, val redExpanding: Boolean
    )

    private fun sma(v: List<Double>, n: Int): Double? = if (v.size >= n) v.takeLast(n).average() else null

    private fun rsi(v: List<Double>, n: Int): Double? {
        if (v.size < n + 1) return null
        val diffs = v.takeLast(n + 1).zipWithNext { a, b -> b - a }
        val gain = diffs.filter { it > 0 }.sum() / n
        val loss = -diffs.filter { it < 0 }.sum() / n
        if (loss == 0.0) return 100.0
        val rs = gain / loss
        return 100.0 - 100.0 / (1.0 + rs)
    }

    private fun emaSeries(v: List<Double>, n: Int): List<Double> {
        if (v.isEmpty()) return emptyList()
        val k = 2.0 / (n + 1)
        val out = ArrayList<Double>(v.size)
        var e = v.first()
        v.forEachIndexed { i, x ->
            e = if (i == 0) x else x * k + e * (1 - k)
            out += e
        }
        return out
    }

    private fun macd(v: List<Double>): MacdState? {
        if (v.size < 30) return null
        val e12 = emaSeries(v, 12)
        val e26 = emaSeries(v, 26)
        val dif = v.indices.map { e12[it] - e26[it] }
        val sig = emaSeries(dif, 9)
        val hist = dif.indices.map { dif[it] - sig[it] }
        val i = dif.lastIndex
        val p = i - 1
        if (p < 0) return null
        return MacdState(
            dif[i], sig[i], hist[i],
            dif[i] > dif[p],
            dif[p] <= sig[p] && dif[i] > sig[i],
            hist[i] < 0 && hist[p] < 0 && abs(hist[i]) < abs(hist[p]),
            hist[p] <= 0 && hist[i] > 0,
            hist[i] > 0 && hist[i] > hist[p]
        )
    }

    private fun stochastic(high: List<Double>, low: List<Double>, close: List<Double>, n: Int): Pair<Double, Double>? {
        if (close.size < n) return null
        var k = 50.0
        var d = 50.0
        for (i in (close.size - n).coerceAtLeast(n - 1) until close.size) {
            val from = (i - n + 1).coerceAtLeast(0)
            val hh = high.subList(from, i + 1).maxOrNull() ?: continue
            val ll = low.subList(from, i + 1).minOrNull() ?: continue
            val rsv = if (hh == ll) 50.0 else (close[i] - ll) / (hh - ll) * 100.0
            k = k * 2.0 / 3.0 + rsv / 3.0
            d = d * 2.0 / 3.0 + k / 3.0
        }
        return k to d
    }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
}
