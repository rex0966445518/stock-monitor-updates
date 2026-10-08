package com.rex.twboardingscanner.domain

enum class RadarType { A_EARLY_BREAKOUT, B_DEEP_REVERSAL, C_LONG_RED_VOLUME }
enum class SignalLight { NONE, YELLOW, ORANGE, RED, WEAKENING }
enum class Market { TWSE, TPEX }

enum class StockSector(val label: String) {
    SEMICONDUCTOR("半導體"),
    ELECTRONICS("電子零組件"),
    AI_SERVER("AI／伺服器"),
    OPTOELECTRONICS("光電"),
    COMMUNICATION("通訊網路"),
    ELECTROMECHANICAL("電機機械"),
    TRADITIONAL("傳產"),
    FINANCE("金融"),
    BIOTECH("生技醫療"),
    SHIPPING("航運"),
    CONSTRUCTION("建材營造"),
    TOURISM_RETAIL("觀光／零售"),
    OTHER("其他");

    companion object {
        fun fromIndustry(raw: String): StockSector {
            val s = raw.trim()
            return when {
                s.contains("半導體") -> SEMICONDUCTOR
                s.contains("電子零組件") || s.contains("其他電子") -> ELECTRONICS
                s.contains("電腦") || s.contains("資訊服務") -> AI_SERVER
                s.contains("光電") -> OPTOELECTRONICS
                s.contains("通信") || s.contains("通訊") -> COMMUNICATION
                s.contains("電機") || s.contains("電器") -> ELECTROMECHANICAL
                s.contains("金融") || s.contains("銀行") || s.contains("保險") || s.contains("證券") -> FINANCE
                s.contains("生技") || s.contains("醫療") -> BIOTECH
                s.contains("航運") -> SHIPPING
                s.contains("建材") || s.contains("營造") -> CONSTRUCTION
                s.contains("觀光") || s.contains("百貨") || s.contains("貿易") || s.contains("居家生活") -> TOURISM_RETAIL
                listOf("水泥", "食品", "塑膠", "紡織", "化學", "化工", "玻璃", "造紙", "鋼鐵", "橡膠", "汽車", "油電燃氣").any { s.contains(it) } -> TRADITIONAL
                else -> OTHER
            }
        }
    }
}

data class DailyBar(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volumeShares: Long
)

data class MarketStock(
    val code: String,
    val name: String,
    val market: Market,
    val sector: StockSector,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val changePct: Double,
    val volumeLots: Int,
    val epsTtm: Double?,
    val revenueYoY: Double?
)

data class StockSnapshot(
    val code: String,
    val name: String,
    val sector: StockSector,
    val price: Double,
    val openPrice: Double,
    val highPrice: Double,
    val lowPrice: Double,
    val changePct: Double,
    val volumeLots: Int,
    val avg20VolumeLots: Double,
    val ma5: Double?, val ma10: Double?, val ma20: Double?, val ma60: Double?,
    val ma5SlopeUp: Boolean,
    val maConverging: Boolean,
    val rsi: Double?,
    val kdOverheated: Boolean,
    val dif: Double?, val signal: Double?, val osc: Double?,
    val difRising: Boolean,
    val macdGoldenCross: Boolean,
    val macdNegBarsShrinking: Boolean,
    val macdTurnedPositive: Boolean,
    val macdRedExpanding: Boolean,
    val foreignToday: Int?, val foreign3d: Int?, val foreign5d: Int?,
    val trust3d: Int?, val trust5d: Int?,
    val foreignSellingShrinking: Boolean,
    val nearResistance: Boolean,
    val breakoutResistance: Boolean,
    val breakoutLine: Double?,
    val recentHighDistancePct: Double?,
    val high52wDistancePct: Double?,
    val drawdown52wPct: Double?,
    val noNewLow20to60d: Boolean,
    val lowsRising: Boolean,
    val volumeContractingDuringDecline: Boolean,
    val epsTtm: Double?,
    val revenueYoY: Double?,
    val fundamentalsDeteriorating: Boolean,
    val fundamentalsVerified: Boolean,
    val fiveDayGainPct: Double?,
    val distanceFromMa20Pct: Double?,
    val bars: List<DailyBar>,
    val timestamp: Long
)

data class SignalResult(
    val code: String,
    val name: String,
    val sector: StockSector,
    val radarType: RadarType,
    val score: Int,
    val light: SignalLight,
    val reasons: List<String>,
    val blockers: List<String>,
    val snapshot: StockSnapshot
)
