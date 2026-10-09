package com.rex.twboardingscanner.data

import com.rex.twboardingscanner.domain.*
import org.jsoup.Jsoup

/** Parse only labeled financial tables. Missing cells stay missing, never zero. */
object HistockParser {
    private fun number(raw: String): Double? = raw.trim().replace(",", "").replace("%", "")
        .replace("−", "-").let { if (it.startsWith("(") && it.endsWith(")")) "-" + it.substring(1, it.length-1) else it }
        .toDoubleOrNull()?.takeIf { it.isFinite() }
    private fun tables(html: String, code: String, topic: String): List<List<List<String>>> {
        require(code.matches(Regex("[0-9]{4}")))
        val doc = Jsoup.parse(html)
        require(Regex("(?<![0-9])$code(?![0-9])").containsMatchIn(doc.title()) && doc.text().contains(topic)) { "股票或報表標題不符" }
        return doc.select("table").map { table ->
            table.select("tr").filter { row -> row.parents().firstOrNull { it.tagName() == "table" } == table }
                .map { row -> row.children().filter { it.tagName() in listOf("td", "th") }.map { it.text().trim() } }
        }
    }
    fun eps(html: String, code: String): Pair<List<QuarterEps>, Map<Int, Double>> {
        val table = tables(html, code, "每股盈餘").singleOrNull { rows -> rows.any { it.firstOrNull() == "季別/年度" } }
            ?: error("找不到單季 EPS 表格")
        val header = table.first { it.firstOrNull() == "季別/年度" }
        val years = header.drop(1).map { it.toIntOrNull()?.takeIf { y -> y in 2000..2100 } ?: error("EPS 年度格式變更") }
        require(years.distinct().size == years.size)
        val out = mutableListOf<QuarterEps>()
        for (q in 1..4) {
            val row = table.singleOrNull { it.firstOrNull() == "Q$q" } ?: continue
            years.forEachIndexed { i, year -> row.getOrNull(i+1)?.let { number(it) }?.let { out.add(QuarterEps(year,q,it)) } }
        }
        require(out.isNotEmpty()) { "沒有可用的單季 EPS" }
        val total = table.singleOrNull { it.firstOrNull() == "總計" }
        val annual = years.mapIndexedNotNull { i, year ->
            if (out.count { it.year == year } != 4) null else total?.getOrNull(i+1)?.let { number(it) }?.let { year to it }
        }.toMap()
        return out to annual
    }
    fun periodValues(html: String, code: String, topic: String, column: String): List<PeriodValue> {
        val table = tables(html, code, topic).singleOrNull { rows -> rows.any { it.firstOrNull() == "年度/季別" && column in it } }
            ?: error("找不到$column 表格")
        val header = table.first { it.firstOrNull() == "年度/季別" && column in it }
        val index = header.indexOf(column)
        val out = table.mapNotNull { row ->
            val period = Regex("(20[0-9]{2})Q([1-4])").matchEntire(row.firstOrNull().orEmpty()) ?: return@mapNotNull null
            val value = row.getOrNull(index)?.let { number(it) } ?: return@mapNotNull null
            PeriodValue(period.groupValues[1].toInt(),period.groupValues[2].toInt(),value)
        }
        require(out.isNotEmpty() && out.map { it.index }.distinct().size == out.size) { "期間缺漏或重複" }
        return out
    }
}
