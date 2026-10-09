package com.rex.twboardingscanner.domain

import java.time.LocalDate

data class PeriodValue(val year: Int, val quarter: Int, val value: Double) {
    val index get() = year * 4 + quarter
    val label get() = "${year}Q$quarter"
}
data class FinancialReport(
    val eps: List<QuarterEps> = emptyList(),
    val annualEps: Map<Int, Double> = emptyMap(),
    val operatingCash: List<PeriodValue> = emptyList(),
    val operatingMargin: List<PeriodValue> = emptyList(),
    val fetchedAt: Long = 0,
    val messages: List<String> = emptyList()
) {
    private fun fresh(year: Int, quarter: Int, today: LocalDate): Boolean {
        if (quarter !in 1..4 || year < 2000) return false
        val end = LocalDate.of(year, quarter * 3, 1).plusMonths(1).minusDays(1)
        // Stale or future reports never silently qualify as latest results.
        return !end.isAfter(today) && !end.isBefore(today.minusMonths(6))
    }
    fun quarters(n: Int, today: LocalDate): List<QuarterEps>? {
        val q = eps.sortedBy { it.year * 4 + it.quarter }.takeLast(n)
        if (q.size != n || q.any { it.quarter !in 1..4 || !it.eps.isFinite() } ||
            q.zipWithNext().any { (a,b) -> b.year * 4 + b.quarter != a.year * 4 + a.quarter + 1 } ||
            !fresh(q.last().year, q.last().quarter, today)) return null
        return q
    }
    fun ttm(today: LocalDate): Double? = quarters(4, today)?.sumOf { it.eps }
    fun fourPositive(today: LocalDate): Boolean? = quarters(4, today)?.all { it.eps > 0 }
    fun growth(today: LocalDate): Boolean? = quarters(8, today)?.let {
        val old = it.take(4).sumOf { q -> q.eps }; val new = it.takeLast(4).sumOf { q -> q.eps }
        old > 0 && new > old
    }
    fun stableYears(n: Int, today: LocalDate): Boolean? {
        // Before annual reporting season closes, the previous completed report year is allowed.
        val expected = today.year - if (today.monthValue <= 3) 2 else 1
        val latest = annualEps.keys.filter { it < today.year }.maxOrNull() ?: return null
        if (latest < expected) return null
        val values = (latest-n+1..latest).map { annualEps[it]?.takeIf { v -> v.isFinite() } ?: return null }
        return values.all { it > 0 } && values.zipWithNext().all { (a,b) -> b > a }
    }
    fun latest(values: List<PeriodValue>, today: LocalDate): PeriodValue? = values.maxByOrNull { it.index }
        ?.takeIf { it.value.isFinite() && fresh(it.year, it.quarter, today) }
    fun summary(today: LocalDate): String {
        val q = quarters(4, today)
        val e = q?.joinToString("、") { "${it.year}Q${it.quarter} ${it.eps}" } ?: "四季 EPS 待查核（缺漏或過期）"
        val cash = latest(operatingCash, today)?.let { "${it.label} 營業現金流 ${if(it.value > 0) "正值" else if(it.value < 0) "負值" else "零"}" } ?: "營業現金流待查核"
        val margin = latest(operatingMargin, today)?.let { "${it.label} 營業利益率 ${it.value}%" } ?: "營業利益率待查核"
        return "HiStock｜$e\n近四季 EPS 合計：${ttm(today)?.let { "%.2f 元".format(it) } ?: "待查核"}\n$cash｜$margin" +
            if (messages.isEmpty()) "" else "\n" + messages.joinToString("；")
    }
}
