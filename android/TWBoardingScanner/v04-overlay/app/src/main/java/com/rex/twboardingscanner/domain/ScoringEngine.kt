package com.rex.twboardingscanner.domain

class ScoringEngine {
    private fun historyReady(s: StockSnapshot) = s.bars.size >= 35

    fun evaluateA(s: StockSnapshot): SignalResult {
        val reasons = mutableListOf<String>()
        val blockers = mutableListOf<String>()
        if (s.volumeLots < 500) blockers += "成交量低於500張"
        if (s.epsTtm != null && s.epsTtm <= 0.0) blockers += "近季EPS非正值"
        if (s.fundamentalsDeteriorating) blockers += "基本面明顯惡化"
        if ((s.fiveDayGainPct ?: 0.0) >= 15.0) blockers += "近5日漲幅過大"
        if (kotlin.math.abs(s.distanceFromMa20Pct ?: 0.0) >= 10.0) blockers += "距20MA乖離過大"
        if (!historyReady(s)) blockers += "歷史資料不足35日"

        val macdHardPass = s.difRising && (s.macdGoldenCross || s.macdNegBarsShrinking || s.macdTurnedPositive || s.macdRedExpanding)
        if (!macdHardPass) blockers += "MACD未達必要轉強條件"

        var score = 0.0
        if (macdHardPass) { score += 20; reasons += "MACD轉強" }
        if (s.ma5SlopeUp) { score += 6; reasons += "5MA翻揚" }
        if (s.maConverging) { score += 5; reasons += "5/10/20MA收斂" }
        if (s.ma5 != null && s.price >= s.ma5) score += 2
        if (s.ma10 != null && s.price >= s.ma10) score += 1
        if (s.ma20 != null && s.price >= s.ma20) { score += 1; reasons += "站上20MA" }

        val rsi = s.rsi
        if (rsi != null && rsi > 50) { score += if (rsi <= 70) 10 else 5; reasons += "RSI>50" }
        val volRatio = if (s.avg20VolumeLots > 0) s.volumeLots / s.avg20VolumeLots else 0.0
        if (volRatio >= 1.5) { score += 15; reasons += "量能>=20日均量1.5倍" }
        else if (volRatio >= 1.0) score += 8

        val chipsPass = (s.foreign3d ?: 0) > 0 || (s.foreign5d ?: 0) > 0 || (s.trust3d ?: 0) > 0 || (s.trust5d ?: 0) > 0 || s.foreignSellingShrinking
        if (chipsPass) { score += 15; reasons += "法人籌碼改善" }
        if (s.maConverging || s.nearResistance || s.lowsRising) score += 10
        if (s.fundamentalsVerified && (s.epsTtm ?: 0.0) > 0) { score += 10; reasons += "基本面已核實" }
        if (!s.kdOverheated && (rsi == null || rsi < 75)) score += 5
        if (s.breakoutResistance) reasons += "突破重要壓力"

        val finalScore = score.toInt().coerceIn(0, 100)
        val redHard = historyReady(s) && s.fundamentalsVerified && macdHardPass && (rsi ?: 0.0) > 50 &&
            (s.ma20?.let { s.price >= it } == true) && s.breakoutResistance && volRatio >= 1.5
        val hardBlock = blockers.any { it.contains("成交量") || it.contains("基本面明顯") || it.contains("MACD") }
        val light = when {
            hardBlock -> SignalLight.NONE
            redHard && finalScore >= 85 -> SignalLight.RED
            finalScore >= 70 && macdHardPass -> SignalLight.YELLOW
            else -> SignalLight.NONE
        }
        return SignalResult(s.code, s.name, s.sector, RadarType.A_EARLY_BREAKOUT, finalScore, light, reasons, blockers, s)
    }

    fun evaluateB(s: StockSnapshot): SignalResult {
        val reasons = mutableListOf<String>()
        val blockers = mutableListOf<String>()
        if (s.volumeLots < 500) blockers += "成交量低於500張"
        if (s.fundamentalsDeteriorating) blockers += "基本面明顯惡化"
        if (!s.noNewLow20to60d) blockers += "仍在持續創低風險區"
        if (!historyReady(s)) blockers += "歷史資料不足35日"

        val macdHardPass = s.difRising && (s.macdGoldenCross || s.macdNegBarsShrinking || s.macdTurnedPositive || s.macdRedExpanding)
        if (!macdHardPass) blockers += "MACD未達必要轉強條件"

        var score = 0.0
        if (macdHardPass) { score += 20; reasons += "低檔MACD轉強" }
        if (s.ma5SlopeUp) { score += 7; reasons += "5MA翻揚" }
        if (s.maConverging) score += 5
        if (s.ma5 != null && s.price >= s.ma5) score += 3
        if (s.ma20 != null && s.price >= s.ma20) { score += 5; reasons += "重新站回20MA" }
        val rsi = s.rsi
        if (rsi != null && rsi >= 50 && rsi <= 70) { score += 10; reasons += "RSI突破50" }
        else if (rsi != null && rsi >= 40) score += 6
        val volRatio = if (s.avg20VolumeLots > 0) s.volumeLots / s.avg20VolumeLots else 0.0
        if (s.volumeContractingDuringDecline) { score += 7; reasons += "下跌賣量縮小" }
        if (volRatio >= 1.2) score += 8
        if (s.foreignSellingShrinking || (s.foreignToday ?: 0) > 0 || (s.trust3d ?: 0) > 0) { score += 15; reasons += "法人賣壓縮小/回補" }
        val dd = kotlin.math.abs(s.drawdown52wPct ?: 0.0)
        if (dd >= 40) { score += 10; reasons += "距52週高點回落>=40%" }
        else if (dd >= 25) score += 7
        if (s.noNewLow20to60d && s.lowsRising) score += 3
        if (s.fundamentalsVerified && (s.epsTtm ?: 0.0) > 0) score += 10
        if (!s.kdOverheated && (rsi == null || rsi < 75)) score += 5

        val finalScore = score.toInt().coerceIn(0, 100)
        val redHard = historyReady(s) && s.fundamentalsVerified && macdHardPass && (rsi ?: 0.0) > 50 &&
            (s.ma20?.let { s.price >= it } == true) && volRatio >= 1.0
        val orangeHard = macdHardPass && (s.macdGoldenCross || s.macdTurnedPositive) && (rsi ?: 0.0) >= 45 && (s.ma5?.let { s.price >= it } == true)
        val yellowHard = s.noNewLow20to60d && s.volumeContractingDuringDecline && macdHardPass && s.ma5SlopeUp
        val hardBlock = blockers.any { it.contains("成交量") || it.contains("基本面明顯") || it.contains("MACD") }
        val light = when {
            hardBlock -> SignalLight.NONE
            redHard && finalScore >= 85 -> SignalLight.RED
            orangeHard && finalScore >= 75 -> SignalLight.ORANGE
            yellowHard && finalScore >= 65 -> SignalLight.YELLOW
            else -> SignalLight.NONE
        }
        return SignalResult(s.code, s.name, s.sector, RadarType.B_DEEP_REVERSAL, finalScore, light, reasons, blockers, s)
    }

    fun evaluateC(s: StockSnapshot): SignalResult {
        val reasons = mutableListOf<String>()
        val blockers = mutableListOf<String>()
        if (s.volumeLots < 500) blockers += "成交量低於500張"
        if (s.fundamentalsDeteriorating) blockers += "基本面明顯惡化"
        if ((s.fiveDayGainPct ?: 0.0) >= 15.0) blockers += "近5日漲幅過大"
        if (kotlin.math.abs(s.distanceFromMa20Pct ?: 0.0) >= 10.0) blockers += "距20MA乖離過大"
        if (s.kdOverheated || (s.rsi ?: 0.0) >= 75.0) blockers += "短線過熱"

        val bodyPct = if (s.openPrice > 0) (s.price - s.openPrice) / s.openPrice * 100.0 else 0.0
        val dayRange = (s.highPrice - s.lowPrice).coerceAtLeast(0.01)
        val closeNearHigh = ((s.highPrice - s.price) / dayRange) <= 0.30
        val volRatio = if (s.avg20VolumeLots > 0) s.volumeLots / s.avg20VolumeLots else 0.0
        val longRed = bodyPct >= 2.0 && s.changePct > 0.0
        val volumeBurst = volRatio >= 1.8
        var score = 0.0
        if (longRed) { score += 30; reasons += "長紅K實體>=2%" }
        if (volumeBurst) { score += 30; reasons += "爆量>=20日均量1.8倍" }
        else if (volRatio >= 1.5) score += 20
        if (closeNearHigh) { score += 15; reasons += "收盤接近當日高點" }
        if (s.price >= (s.ma5 ?: s.price)) score += 5
        if (s.price >= (s.ma20 ?: s.price)) { score += 5; reasons += "站上20MA" }
        if (s.difRising || s.macdGoldenCross || s.macdTurnedPositive || s.macdRedExpanding) { score += 10; reasons += "MACD同步改善" }
        if ((s.foreignToday ?: 0) > 0 || (s.foreign3d ?: 0) > 0 || (s.trust3d ?: 0) > 0) score += 5
        val finalScore = score.toInt().coerceIn(0, 100)
        val hardPass = longRed && volumeBurst && closeNearHigh && s.bars.size >= 20
        val hardBlock = blockers.any { it.contains("成交量") || it.contains("基本面明顯") || it.contains("過熱") }
        val light = when {
            hardBlock -> SignalLight.NONE
            hardPass && finalScore >= 80 -> SignalLight.RED
            longRed && volRatio >= 1.5 && finalScore >= 65 -> SignalLight.ORANGE
            bodyPct >= 1.0 && volRatio >= 1.3 && finalScore >= 55 -> SignalLight.YELLOW
            else -> SignalLight.NONE
        }
        return SignalResult(s.code, s.name, s.sector, RadarType.C_LONG_RED_VOLUME, finalScore, light, reasons, blockers, s)
    }
}
