package com.rex.twboardingscanner.domain

import kotlin.math.abs

class TechnicalCalculator {
    fun barsForRadar(input: List<DailyBar>, type: RadarType, today: java.time.LocalDate = java.time.LocalDate.now(RuleMetrics.TAIPEI)): List<DailyBar> =
        input.filter { RuleMetrics.tradingDate(it.time) <= today &&
            (type == RadarType.C_LONG_RED_VOLUME || RuleMetrics.tradingDate(it.time) < today) }.sortedBy { it.time }.distinctBy { RuleMetrics.tradingDate(it.time) }

    fun build(stock: MarketStock, inputBars: List<DailyBar>): StockSnapshot {
        val bars = inputBars.sortedBy { it.time }.distinctBy { RuleMetrics.tradingDate(it.time) }
        require(bars.isNotEmpty()) { "No dated bars" }
        val trigger = bars.last()
        val priorClose = bars.dropLast(1).lastOrNull()?.close
        val price = trigger.close
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
        val avg20 = if (volsLots.size >= 21) volsLots.dropLast(1).takeLast(20).average() else 0.0
        val recentHigh = closes.dropLast(1).takeIf { it.size >= 19 }?.takeLast(19)?.maxOrNull()
        val high52 = closes.takeLast(252).maxOrNull()
        val distRecent = recentHigh?.let { (price / it - 1.0) * 100.0 }
        val dist52 = high52?.let { (price / it - 1.0) * 100.0 }
        val fiveGain = closes.takeLast(6).let { if (it.size >= 6 && it.first() != 0.0) (it.last() / it.first() - 1.0) * 100.0 else null }
        val maDist = ma20?.let { if (it != 0.0) (price / it - 1.0) * 100.0 else null }
        val last20Low = closes.takeLast(20).minOrNull()
        val prev20Low = if (closes.size >= 40) closes.dropLast(20).takeLast(20).minOrNull() else null
        val noNewLow = last20Low != null && prev20Low != null && last20Low >= prev20Low
        val lowRecent10 = closes.takeLast(10).minOrNull()
        val lowPrev10 = if (closes.size >= 20) closes.dropLast(10).takeLast(10).minOrNull() else null
        val lowsRising = lowRecent10 != null && lowPrev10 != null && lowRecent10 > lowPrev10
        val pairs = bars.zipWithNext().takeLast(20)
        val recentDown = pairs.takeLast(10).filter { (p,c) -> c.close < p.close }.map { it.second.volumeShares.toDouble() }
        val priorDown = pairs.take(10).filter { (p,c) -> c.close < p.close }.map { it.second.volumeShares.toDouble() }
        val volumeContract = pairs.size == 20 && recentDown.isNotEmpty() && priorDown.isNotEmpty() && recentDown.average() < priorDown.average()
        val maConverging = listOfNotNull(ma5, ma10, ma20).let { m -> m.size == 3 && (m.maxOrNull()!! - m.minOrNull()!!) / (ma20 ?: 1.0) <= 0.03 }
        val fundamentalVerified = stock.quarterlyEps?.size == 4 && stock.revenueYoY != null
        val fundamentalBad = stock.quarterlyEps?.any { it.eps <= 0 } == true || (stock.revenueYoY?.let { it < 0 } == true)

        return StockSnapshot(
            code = stock.code, name = stock.name, sector = stock.sector, price = price,
            openPrice = trigger.open, highPrice = trigger.high, lowPrice = trigger.low, changePct = if (priorClose != null && priorClose > 0) (price / priorClose - 1) * 100 else 0.0,
            volumeLots = (trigger.volumeShares / 1000L).toInt(), avg20VolumeLots = avg20,
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
            breakoutResistance = recentHigh != null && price > recentHigh,
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
            timestamp = System.currentTimeMillis(), sourceStock = stock
        )
    }

    data class MacdState(
        val dif: Double, val signal: Double, val hist: Double,
        val difRising: Boolean, val goldenCross: Boolean, val negShrinking: Boolean,
        val turnedPositive: Boolean, val redExpanding: Boolean, val histRising: Boolean
    )

    private fun sma(v: List<Double>, n: Int): Double? = if (v.size >= n) v.takeLast(n).average() else null

    private fun rsi(v: List<Double>, n: Int): Double? {
        if (v.size < n + 1) return null
        val diffs = v.takeLast(n + 1).zipWithNext { a, b -> b - a }
        val gain = diffs.filter { it > 0 }.sum() / n
        val loss = -diffs.filter { it < 0 }.sum() / n
        if (loss == 0.0) return if (gain == 0.0) 50.0 else 100.0
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

    fun macd(v: List<Double>): MacdState? {
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
            hist[i] > 0 && hist[i] > hist[p],
            hist[i] > hist[p]
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
