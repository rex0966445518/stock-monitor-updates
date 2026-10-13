package com.rex.twboardingscanner.domain

enum class RadarType(val displayName:String) {
    A_EARLY_BREAKOUT("準備起漲"), B_DEEP_REVERSAL("深跌反轉"), C_LONG_RED_VOLUME("突發爆量"),
    D_MAIN_FORCE("主力進場"), E_DIF_TOUCH("DIF金叉"), F_VOLUME_CONTRACTION("縮量上漲");
    val label:String get()="${name.take(1)} $displayName"
    companion object {
        fun displayStored(value:String):String = value.split("/").joinToString(" / "){part->
            entries.firstOrNull{it.name==part || it.name.take(1)==part}?.label?:part
        }
    }
}
enum class SignalLight { NONE, YELLOW, ORANGE, RED, WEAKENING }
enum class Market { TWSE, TPEX }

enum class StockSector(val label: String) {
    SEMICONDUCTOR("半導體"),
    ELECTRONICS("電子零組件"),
    AI_SERVER("電腦／資訊／雲端"),
    OPTOELECTRONICS("光電"),
    COMMUNICATION("通訊網路"),
    ELECTROMECHANICAL("電機機械"),
    TRADITIONAL("傳產"),
    FINANCE("金融"),
    BIOTECH("生技醫療"),
    SHIPPING("航運"),
    CONSTRUCTION("建材營造"),
    TOURISM_RETAIL("觀光／零售"),
    OTHER("其他"),
    UNKNOWN("待分類");

    companion object {
        fun fromIndustry(raw: String): StockSector {
            val s = raw.trim()
            val code = s.toIntOrNull()
            if (code != null) return when(code) {
                24 -> SEMICONDUCTOR
                28,29,31 -> ELECTRONICS
                25,30,36 -> AI_SERVER
                26 -> OPTOELECTRONICS
                27 -> COMMUNICATION
                5,6 -> ELECTROMECHANICAL
                1,2,3,4,7,8,9,10,11,12,21,23,33,35 -> TRADITIONAL
                17 -> FINANCE
                22 -> BIOTECH
                15 -> SHIPPING
                14 -> CONSTRUCTION
                16,18,32,34,37,38 -> TOURISM_RETAIL
                19,20,91 -> OTHER
                else -> UNKNOWN
            }
            return when {
                s.contains("半導體") -> SEMICONDUCTOR
                s.contains("電子零組件") || s.contains("其他電子") || s.contains("電子通路") -> ELECTRONICS
                s.contains("電腦") || s.contains("資訊服務") || s.contains("數位雲端") -> AI_SERVER
                s.contains("光電") -> OPTOELECTRONICS
                s.contains("通信") || s.contains("通訊") -> COMMUNICATION
                s.contains("電機") || s.contains("電器") -> ELECTROMECHANICAL
                s.contains("金融") || s.contains("銀行") || s.contains("保險") || s.contains("證券") -> FINANCE
                s.contains("生技") || s.contains("醫療") -> BIOTECH
                s.contains("航運") -> SHIPPING
                s.contains("建材") || s.contains("營造") -> CONSTRUCTION
                s.contains("觀光") || s.contains("百貨") || s.contains("貿易") || s.contains("居家生活") || s.contains("運動休閒") || s.contains("文化創意") || s.contains("電子商務") -> TOURISM_RETAIL
                listOf("水泥", "食品", "塑膠", "紡織", "化學", "化工", "玻璃", "造紙", "鋼鐵", "橡膠", "汽車", "油電燃氣", "農業科技", "綠能環保").any { s.contains(it) } -> TRADITIONAL
                s.contains("其他") || s.contains("綜合") || s.contains("存託憑證") -> OTHER
                else -> UNKNOWN
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

/**
 * Executed aggressive trade volumes, not five-level outstanding order quantities.
 * buyAtAsk = 外盤成交（買方主動吃賣價）, sellAtBid = 內盤成交（賣方主動打買價）.
 * Values must come from a dated, sourced snapshot; no inference from daily OHLCV.
 */
data class TradeSideVolume(
    val date:java.time.LocalDate,
    val buyAtAsk:Long,
    val sellAtBid:Long,
    val asOf:java.time.LocalTime,
    val source:String
){
    init{
        require(buyAtAsk>=0&&sellAtBid>=0&&buyAtAsk<=10_000_000_000L&&sellAtBid<=10_000_000_000L)
        require(source.isNotBlank()&&source.length<=140)
        require(!asOf.isBefore(java.time.LocalTime.of(9,0))&&!asOf.isAfter(java.time.LocalTime.of(13,30)))
    }
}

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
    val revenueYoY: Double?,
    val quarterlyEps: List<QuarterEps>? = null,
    val foreignDaily: List<InstitutionDay>? = null,
    val trustDaily: List<InstitutionDay>? = null,
    val financials: FinancialReport? = null,
    val closingAuction: ClosingAuction? = null,
    val tradeSideVolume:TradeSideVolume? = null
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
    val timestamp: Long,
    val sourceStock: MarketStock? = null,
    val tradeSideVolume:TradeSideVolume? = null,
    val dConfig:DPatternConfig=DPatternConfig.DEFAULT,
    val eConfig:EPatternConfig=EPatternConfig.DEFAULT,
    val fConfig:FPatternConfig=FPatternConfig.DEFAULT,
    val marketIndexCloses:Map<java.time.LocalDate,Double>?=null,
    val bConfig:BPatternConfig=BPatternConfig.DEFAULT
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
    val snapshot: StockSnapshot,
    val checks: List<ConditionCheck> = emptyList(),
    val technicalUpgrade: Boolean = false,
    val dEvidence:String?=null,
    val exclusionChecks:List<ConditionCheck> = emptyList()
)

// Optional evidence is never synthesized from a single reported EPS or partial flow series.
data class QuarterEps(val year: Int, val quarter: Int, val eps: Double)
data class InstitutionDay(val date: java.time.LocalDate, val netBuy: Long)
enum class CheckState { PASS, FAIL, PENDING }
data class ConditionCheck(val id: String, val label: String, val state: CheckState, val selected: Boolean, val extra: Boolean)
