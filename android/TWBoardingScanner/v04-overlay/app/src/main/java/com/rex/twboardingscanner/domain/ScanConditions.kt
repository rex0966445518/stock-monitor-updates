package com.rex.twboardingscanner.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class ScanCondition(val id: String, val label: String, val extra: Boolean = false,
    val test: (RuleMetrics) -> Boolean?) {
    val defaultEnabled: Boolean get() = !extra
}

object ScanConditions {
    const val VERSION = "20261009"
    fun forRadar(type: RadarType): List<ScanCondition> {
        val basic = listOf(
            ScanCondition("price", "股價 ≥ 50 元") { it.last?.close?.let { p -> p >= 50 } },
            ScanCondition("volume", "成交量 ≥ 500 張") { it.last?.volumeShares?.let { v -> v >= 500000L } }
        )
        val ab = listOf(
            ScanCondition("macd", "MACD 起轉：DIF 高於前日，且柱狀體高於前日") { it.macdUp },
            ScanCondition("heat", "近 5 日漲幅 ≤ 15%，且高於 20 日線的幅度 ≤ 10%") { it.notHot },
            ScanCondition("converge", "5／10／20 日線差距 ÷ 20 日線 ≤ 3%") { it.converging },
            ScanCondition("ma5up", "5 日均線高於前一天") { it.ma5Up }
        )
        val specific = when(type) {
            RadarType.A_EARLY_BREAKOUT -> ab + listOf(
                ScanCondition("above3", "收盤價同時高於 5／10／20 日線") { it.aboveThree },
                ScanCondition("rsi", "RSI > 50") { it.s.rsi?.let { v -> v > 50 } },
                ScanCondition("contract", "觸發日前 9 日均量 < 再前面 10 日均量") { it.consolidationContracting },
                ScanCondition("box", "收盤價 > 前 19 交易日最高收盤價") { it.breakout },
                ScanCondition("burst", "成交量 ≥ 前 20 日均量 1.5 倍（不含觸發日）") { it.volumeAtLeast(1.5) }
            )
            RadarType.B_DEEP_REVERSAL -> ab + listOf(
                ScanCondition("drawdown", "比最近最多 252 日最高收盤價低至少 30%") { it.drawdown },
                ScanCondition("floor", "近 20 日最低收盤價 ≥ 再前 20 日最低收盤價") { it.floorHeld },
                ScanCondition("higherLow", "近 10 日最低收盤價 > 前 10 日最低收盤價") { it.higherLow },
                ScanCondition("declineVolume", "近 10 日下跌日均量 < 前 10 日下跌日均量（兩段都須有下跌日）") { it.declineContracting },
                ScanCondition("ma5", "收盤價 > 5 日均線") { it.aboveMa5 }
            )
            RadarType.C_LONG_RED_VOLUME -> listOf(
                ScanCondition("red", "紅 K：收盤價 > 開盤價") { it.last?.let { b -> b.close > b.open } },
                ScanCondition("gain", "相較前一日收盤價，上漲 ≥ 3%") { it.dayGain?.let { v -> v >= 3.0 - 1e-9 } },
                ScanCondition("body", "相較當日開盤價，上漲 ≥ 3%") { it.bodyGain?.let { v -> v >= 3.0 - 1e-9 } },
                ScanCondition("burst", "成交量 ≥ 前 20 日均量 2 倍（不含觸發日）") { it.volumeAtLeast(2.0) },
                ScanCondition("closeHigh", "收盤位置位於當日振幅上方 25%") { it.closeHigh }
            )
        }
        return basic + specific + listOf(
            ScanCondition("extra_eps", "EPS／營收：連續四季 EPS 均 > 0，且營收年增率 ≥ 0", true) { it.earnings },
            ScanCondition("fin_ttm", "近四季獲利：四個連續單季 EPS 合計 > 0", true) { it.financial?.ttm(it.today)?.let { v -> v > 0 } },
            ScanCondition("fin_positive", "穩定獲利：連續四季 EPS 每季均 > 0", true) { it.financial?.fourPositive(it.today) },
            ScanCondition("fin_growth", "EPS 成長：近四季合計 > 前四季合計，且前四季合計 > 0", true) { it.financial?.growth(it.today) },
            ScanCondition("fin_year3", "三年成長：最近三個完整年度 EPS 均 > 0 且逐年增加", true) { it.financial?.stableYears(3, it.today) },
            ScanCondition("fin_year5", "五年成長：最近五個完整年度 EPS 均 > 0 且逐年增加", true) { it.financial?.stableYears(5, it.today) },
            ScanCondition("fin_margin", "本業獲利：最新列示期間營業利益率 > 0（不代表無業外收益）", true) { it.financial?.latest(it.financial.operatingMargin, it.today)?.value?.let { v -> v > 0 } },
            ScanCondition("fin_cash", "營業現金流：最新列示期間 > 0（原站期間口徑未明，不跨季加總）", true) { it.financial?.latest(it.financial.operatingCash, it.today)?.value?.let { v -> v > 0 } },
            ScanCondition("extra_flow", "法人：外資或投信近 5 日累計買超，且至少 3 日買超", true) { it.institutions },
            ScanCondition("extra_risk", "風險結構：報酬風險比 ≥ 2", true) { it.rewardRisk?.let { v -> v >= 2.0 - 1e-9 } },
            ScanCondition("extra_high", "避開高點：排除現價 ≥ 近 30 日最高價的 95%", true) { it.belowHigh },
            ScanCondition("extra_limit", "避開漲停：排除現價達估算漲停價", true) { it.belowLimit }
        )
    }
}

class RuleMetrics(val s: StockSnapshot) {
    val today: LocalDate = java.time.Instant.ofEpochMilli(s.timestamp).atZone(TAIPEI).toLocalDate()
    val financial = s.sourceStock?.financials
    val bars = s.bars
    val last = bars.lastOrNull()
    private val previous = bars.dropLast(1)
    private val closes = bars.map { it.close }
    private fun avg(n: Int, skip: Int = 0): Double? = closes.dropLast(skip).takeIf { it.size >= n }?.takeLast(n)?.average()
    private val ma5 = avg(5)
    private val ma10 = avg(10)
    private val ma20 = avg(20)
    val macdUp: Boolean? get() = TechnicalCalculator().macd(closes)?.let { it.difRising && it.histRising }
    val notHot: Boolean? get() {
        if (closes.size < 20 || ma20 == null || ma20 <= 0 || closes[closes.lastIndex - 5] <= 0) return null
        val five = (closes.last() / closes[closes.lastIndex - 5] - 1) * 100
        val distance = (closes.last() / ma20 - 1) * 100
        return five <= 15 + 1e-9 && distance <= 10 + 1e-9
    }
    val converging: Boolean? get() {
        val m = listOfNotNull(ma5, ma10, ma20)
        if (m.size != 3 || ma20 == null || ma20 <= 0) return null
        return (m.maxOrNull()!! - m.minOrNull()!!) / ma20 <= 0.03 + 1e-12
    }
    val ma5Up: Boolean? get() = avg(5, 1)?.let { old -> ma5?.let { it > old } }
    val aboveMa5: Boolean? get() = ma5?.let { last!!.close > it }
    val aboveThree: Boolean? get() = if (ma5 == null || ma10 == null || ma20 == null) null else last!!.close > ma5 && last.close > ma10 && last.close > ma20
    val consolidationContracting: Boolean? get() = if (previous.size < 19) null else previous.takeLast(9).map { it.volumeShares.toDouble() }.average() < previous.dropLast(9).takeLast(10).map { it.volumeShares.toDouble() }.average()
    val breakout: Boolean? get() = if (previous.size < 19) null else last!!.close > previous.takeLast(19).maxOf { it.close }
    fun volumeAtLeast(multiplier: Double): Boolean? {
        if (previous.size < 20) return null
        val average = previous.takeLast(20).map { it.volumeShares.toDouble() }.average()
        return if (average <= 0) null else last!!.volumeShares >= average * multiplier
    }
    val drawdown: Boolean? get() = bars.takeIf { it.isNotEmpty() }?.takeLast(252)?.maxOf { it.close }?.takeIf { it > 0 }?.let { last!!.close <= it * 0.70 + 1e-9 }
    val floorHeld: Boolean? get() = if (bars.size < 40) null else closes.takeLast(20).minOrNull()!! >= closes.dropLast(20).takeLast(20).minOrNull()!!
    val higherLow: Boolean? get() = if (bars.size < 20) null else closes.takeLast(10).minOrNull()!! > closes.dropLast(10).takeLast(10).minOrNull()!!
    val declineContracting: Boolean? get() {
        if (bars.size < 21) return null
        val days = bars.zipWithNext().takeLast(20)
        val recent = days.takeLast(10).filter { (p, c) -> c.close < p.close }.map { it.second.volumeShares.toDouble() }
        val older = days.take(10).filter { (p, c) -> c.close < p.close }.map { it.second.volumeShares.toDouble() }
        if (recent.isEmpty() || older.isEmpty()) return false
        return recent.average() < older.average()
    }
    val dayGain: Double? get() = previous.lastOrNull()?.close?.takeIf { it > 0 }?.let { (last!!.close / it - 1) * 100 }
    val bodyGain: Double? get() = last?.open?.takeIf { it > 0 }?.let { (last!!.close / it - 1) * 100 }
    val closeHigh: Boolean? get() = last?.let { if (it.high <= it.low) null else (it.close - it.low) / (it.high - it.low) >= 0.75 - 1e-12 }
    val earnings: Boolean? get() {
        if (financial != null) {
            val positive = financial.fourPositive(today) ?: return null
            val revenue = s.sourceStock?.revenueYoY?.takeIf { it.isFinite() } ?: return null
            return positive && revenue >= 0
        }
        val q = s.sourceStock?.quarterlyEps?.sortedBy { it.year * 4 + it.quarter }?.takeLast(4) ?: return null
        val revenue = s.sourceStock?.revenueYoY ?: return null
        if (q.size != 4 || q.any { it.quarter !in 1..4 || !it.eps.isFinite() } || !revenue.isFinite()) return null
        if (q.zipWithNext().any { (a, b) -> b.year * 4 + b.quarter != a.year * 4 + a.quarter + 1 }) return null
        return q.all { it.eps > 0 } && revenue >= 0
    }
    val institutions: Boolean? get() {
        if (bars.size < 5) return null
        val dates = bars.takeLast(5).map { tradingDate(it.time) }
        fun passes(input: List<InstitutionDay>?): Boolean? {
            if (input == null) return null
            val byDate = input.associateBy { it.date }
            if (dates.any { it !in byDate }) return null
            val values = dates.map { byDate.getValue(it).netBuy }
            return values.sum() > 0 && values.count { it > 0 } >= 3
        }
        val f = passes(s.sourceStock?.foreignDaily)
        val t = passes(s.sourceStock?.trustDaily)
        return if (f == true || t == true) true else if (f == null || t == null) null else false
    }
    // Explicit estimate: prior 20-day low is support, 1% below it invalidates,
    // prior 60-day high is resistance. No overhead resistance => pending.
    val rewardRisk: Double? get() {
        if (previous.size < 60) return null
        val support = previous.takeLast(20).minOf { it.low }
        val invalid = support * 0.99
        val resistance = previous.takeLast(60).maxOf { it.high }
        val price = last!!.close
        if (support <= 0 || resistance <= price) return null
        if (price <= support) return 0.0
        return (resistance - price) / (price - invalid)
    }
    val belowHigh: Boolean? get() = if (bars.size < 30) null else last!!.close < bars.takeLast(30).maxOf { it.high } * 0.95
    val belowLimit: Boolean? get() = previous.lastOrNull()?.close?.takeIf { it > 0 }?.let { last!!.close < estimatedLimit(it) - 1e-9 }
    val technicalUpgrade: Boolean get() = (s.rsi ?: 0.0) > 50 && ma20?.let { last!!.close > it } == true
    companion object {
        val TAIPEI: ZoneId = ZoneId.of("Asia/Taipei")
        fun tradingDate(time: Long): LocalDate = Instant.ofEpochMilli(time).atZone(TAIPEI).toLocalDate()
        fun estimatedLimit(previous: Double): Double {
            val ceiling = BigDecimal.valueOf(previous).multiply(BigDecimal("1.10"))
            val tick = when {
                ceiling < BigDecimal("10") -> "0.01"
                ceiling < BigDecimal("50") -> "0.05"
                ceiling < BigDecimal("100") -> "0.1"
                ceiling < BigDecimal("500") -> "0.5"
                ceiling < BigDecimal("1000") -> "1"
                else -> "5"
            }.let { BigDecimal(it) }
            return ceiling.divide(tick, 0, RoundingMode.FLOOR).multiply(tick).toDouble()
        }
    }
}
