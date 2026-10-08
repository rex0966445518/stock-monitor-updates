package com.rex.twboardingscanner.domain

// One catalogue drives both the checklist and the evaluator.
data class ScanCondition(val id: String, val label: String, val weight: Int = 5,
    val defaultEnabled: Boolean = false, val passes: (StockSnapshot) -> Boolean)

object ScanConditions {
    private fun ratio(s: StockSnapshot) = if (s.avg20VolumeLots > 0) s.volumeLots / s.avg20VolumeLots else 0.0
    private fun macd(s: StockSnapshot) = s.difRising && (s.macdGoldenCross || s.macdNegBarsShrinking || s.macdTurnedPositive || s.macdRedExpanding)
    private fun common() = listOf(
        ScanCondition("volume", "成交量至少 500 張", 5, true) { it.volumeLots >= 500 },
        ScanCondition("eps", "最近四季合計 EPS > 0（缺資料視為未通過）", 10) { (it.epsTtm ?: Double.NEGATIVE_INFINITY) > 0 },
        ScanCondition("fundamentals", "基本面已核實且未明顯惡化", 5) { it.fundamentalsVerified && !it.fundamentalsDeteriorating },
        ScanCondition("hot", "避開近 5 日漲幅 ≥ 15%（需有資料）") { it.fiveDayGainPct?.let { v -> v < 15 } == true },
        ScanCondition("distance", "與 20 日線乖離絕對值 < 10%（需有資料）") { it.distanceFromMa20Pct?.let { v -> kotlin.math.abs(v) < 10 } == true },
        ScanCondition("heat", "KD 未過熱且 RSI < 75") { !it.kdOverheated && it.rsi?.let { v -> v < 75 } == true },
        ScanCondition("limit", "避開漲幅 ≥ 9.5% 的股票") { it.changePct < 9.5 },
        ScanCondition("ma5up", "5 日均線翻揚") { it.ma5SlopeUp },
        ScanCondition("converge", "5／10／20 日均線收斂") { it.maConverging },
        ScanCondition("ma5", "股價站上 5 日線") { it.ma5?.let { v -> it.price >= v } == true },
        ScanCondition("ma10", "股價站上 10 日線") { it.ma10?.let { v -> it.price >= v } == true },
        ScanCondition("ma20", "股價站上 20 日線") { it.ma20?.let { v -> it.price >= v } == true }
    )
    fun forRadar(type: RadarType): List<ScanCondition> {
        val specific = when (type) {
            RadarType.A_EARLY_BREAKOUT -> listOf(
                ScanCondition("macd", "MACD 起轉：DIF 向上，搭配金叉／負柱縮短／翻紅／紅柱擴大", 20, true, ::macd),
                ScanCondition("rsi", "RSI > 50", 10) { (it.rsi ?: 0.0) > 50 },
                ScanCondition("rsiRange", "RSI 介於 50～70") { it.rsi?.let { v -> v > 50 && v <= 70 } == true },
                ScanCondition("burst", "成交量 ≥ 20 日均量 1.5 倍", 15) { ratio(it) >= 1.5 },
                ScanCondition("chips", "外資／投信近 3～5 日買超，或外資賣量縮小", 15) { (it.foreign3d ?: 0) > 0 || (it.foreign5d ?: 0) > 0 || (it.trust3d ?: 0) > 0 || (it.trust5d ?: 0) > 0 || it.foreignSellingShrinking },
                ScanCondition("base", "均線收斂／接近壓力／低點墊高任一成立", 10) { it.maConverging || it.nearResistance || it.lowsRising },
                ScanCondition("breakout", "突破重要壓力") { it.breakoutResistance }
            )
            RadarType.B_DEEP_REVERSAL -> listOf(
                ScanCondition("macd", "低檔 MACD 起轉：DIF 向上且柱狀體改善", 20, true, ::macd),
                ScanCondition("rsi", "RSI 介於 50～70", 10) { it.rsi?.let { v -> v >= 50 && v <= 70 } == true },
                ScanCondition("rsi45", "RSI ≥ 45") { (it.rsi ?: 0.0) >= 45 },
                ScanCondition("cross", "MACD 黃金交叉或柱狀體剛翻紅") { it.macdGoldenCross || it.macdTurnedPositive },
                ScanCondition("contract", "下跌期間賣量縮小", 7) { it.volumeContractingDuringDecline },
                ScanCondition("burst", "成交量 ≥ 20 日均量 1.2 倍", 8) { ratio(it) >= 1.2 },
                ScanCondition("chips", "外資賣量縮小／當日回補，或投信近 3 日買超", 15) { it.foreignSellingShrinking || (it.foreignToday ?: 0) > 0 || (it.trust3d ?: 0) > 0 },
                ScanCondition("drawdown25", "距 52 週高點回落至少 25%") { (it.drawdown52wPct ?: 0.0) <= -25 },
                ScanCondition("drawdown40", "距 52 週高點回落至少 40%", 10) { (it.drawdown52wPct ?: 0.0) <= -40 },
                ScanCondition("noLow", "最近 20 日低點不低於前 20 日低點的 97%（需 40 日資料）", 5) { it.bars.size >= 40 && it.noNewLow20to60d },
                ScanCondition("lows", "低點逐步墊高") { it.lowsRising }
            )
            RadarType.C_LONG_RED_VOLUME -> listOf(
                ScanCondition("red", "長紅 K：實體漲幅 ≥ 2%，當日漲幅為正", 30, true) { it.openPrice > 0 && (it.price - it.openPrice) / it.openPrice * 100 >= 2 && it.changePct > 0 },
                ScanCondition("burst", "爆量：成交量 ≥ 20 日均量 1.8 倍", 30, true) { ratio(it) >= 1.8 },
                ScanCondition("closeHigh", "收盤位於當日振幅頂端 30%", 15, true) { (it.highPrice - it.price) / (it.highPrice - it.lowPrice).coerceAtLeast(0.01) <= 0.30 },
                ScanCondition("macd", "MACD 同步改善：DIF 向上／金叉／翻紅／紅柱擴大", 10) { it.difRising || it.macdGoldenCross || it.macdTurnedPositive || it.macdRedExpanding },
                ScanCondition("chips", "外資當日／近 3 日買超，或投信近 3 日買超") { (it.foreignToday ?: 0) > 0 || (it.foreign3d ?: 0) > 0 || (it.trust3d ?: 0) > 0 }
            )
        }
        return specific + common()
    }
}
