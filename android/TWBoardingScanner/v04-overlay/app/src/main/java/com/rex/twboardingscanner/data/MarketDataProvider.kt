package com.rex.twboardingscanner.data

import android.content.Context
import com.rex.twboardingscanner.domain.DailyBar
import com.rex.twboardingscanner.domain.Market
import com.rex.twboardingscanner.domain.MarketStock
import com.rex.twboardingscanner.domain.StockSector
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MarketDataProvider(private val context: Context) {
    private val ua = "Mozilla/5.0 (Android) TWBoardingScanner/0.4"

    fun loadUniverse(onStatus: (String) -> Unit = {}): List<MarketStock> {
        onStatus("下載上市櫃公司分類…")
        val sectorMap = loadSectorMap()
        onStatus("下載基本面快照…")
        val epsMap = loadEpsMap()
        val revMap = loadRevenueMap()
        onStatus("下載全市場最新價量…")
        val result = mutableListOf<MarketStock>()
        loadTwseQuotes(sectorMap, epsMap, revMap, result)
        loadTpexQuotes(sectorMap, epsMap, revMap, result)
        return result.distinctBy { it.code }.sortedBy { it.code }
    }

    fun loadHistory(stock: MarketStock): List<DailyBar> = loadSymbolHistory(stock.code, stock.market, false)

    fun loadChartHistory(code: String, market: Market? = null): List<DailyBar> {
        require(code.matches(Regex("[0-9]{4}")))
        for (candidate in market?.let { listOf(it) } ?: Market.entries) {
            if (Thread.currentThread().isInterrupted) return emptyList()
            val bars = loadSymbolHistory(code, candidate, true)
            if (bars.isNotEmpty()) return bars
        }
        return emptyList()
    }

    private fun loadSymbolHistory(code: String, market: Market, forceRefresh: Boolean): List<DailyBar> {
        val cacheDir = File(context.cacheDir, "price_history_v044").apply { mkdirs() }
        val file = File(cacheDir, "${code}_${market.name}.json")
        if (!forceRefresh && file.exists() && System.currentTimeMillis() - file.lastModified() < 60_000L) {
            val cached = runCatching { parseYahoo(file.readText()) }.getOrDefault(emptyList())
            if (cached.isNotEmpty()) return cached.sortedBy { it.time }.takeLast(320)
        }
        val suffix = if (market == Market.TWSE) ".TW" else ".TWO"
        for (host in listOf("query1.finance.yahoo.com", "query2.finance.yahoo.com")) {
            if (Thread.currentThread().isInterrupted) return emptyList()
            val raw = fetchText("https://$host/v8/finance/chart/$code$suffix?range=2y&interval=1d&events=history") ?: continue
            val bars = parseYahoo(raw)
            if (bars.isEmpty()) continue
            // A refresh never reports stale disk content as newly retrieved data.
            runCatching { synchronized(cacheDir.absolutePath.intern()) { file.writeText(raw) } }
            return bars.sortedBy { it.time }.takeLast(320)
        }
        return emptyList()
    }

    private fun loadSectorMap(): MutableMap<String, Pair<String, StockSector>> {
        val map = mutableMapOf<String, Pair<String, StockSector>>()
        val endpoints = listOf(
            "https://openapi.twse.com.tw/v1/opendata/t187ap03_L",
            "https://www.tpex.org.tw/openapi/v1/mopsfin_t187ap03_O"
        )
        endpoints.forEach { u ->
            val arr = fetchArray(u) ?: return@forEach
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val code = pick(o, "公司代號", "公司代碼", "Code", "SecuritiesCompanyCode") ?: continue
                if (!code.matches(Regex("\\d{4}"))) continue
                val name = pick(o, "公司簡稱", "公司名稱", "Name", "CompanyName") ?: code
                val industry = pickContains(o, listOf("產業別", "產業", "Industry")) ?: ""
                map[code] = name to StockSector.fromIndustry(industry)
            }
        }
        return map
    }

    private fun loadEpsMap(): MutableMap<String, Double> {
        val map = mutableMapOf<String, Double>()
        val endpoints = listOf(
            "https://openapi.twse.com.tw/v1/opendata/t187ap06_L_ci",
            "https://openapi.twse.com.tw/v1/opendata/t187ap06_L_basi",
            "https://openapi.twse.com.tw/v1/opendata/t187ap06_L_bd",
            "https://openapi.twse.com.tw/v1/opendata/t187ap06_L_fh",
            "https://openapi.twse.com.tw/v1/opendata/t187ap06_L_ins",
            "https://www.tpex.org.tw/openapi/v1/mopsfin_t187ap06_O_ci",
            "https://www.tpex.org.tw/openapi/v1/mopsfin_t187ap06_O_basi",
            "https://www.tpex.org.tw/openapi/v1/mopsfin_t187ap06_O_bd",
            "https://www.tpex.org.tw/openapi/v1/mopsfin_t187ap06_O_fh",
            "https://www.tpex.org.tw/openapi/v1/mopsfin_t187ap06_O_ins"
        )
        endpoints.forEach { u ->
            val arr = fetchArray(u) ?: return@forEach
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val code = pick(o, "公司代號", "公司代碼", "Code", "SecuritiesCompanyCode") ?: continue
                val eps = pickNumberContains(o, listOf("每股盈餘", "EPS", "EarningsPerShare"))
                if (eps != null) map[code] = eps
            }
        }
        return map
    }

    private fun loadRevenueMap(): MutableMap<String, Double> {
        val map = mutableMapOf<String, Double>()
        val endpoints = listOf(
            "https://openapi.twse.com.tw/v1/opendata/t187ap05_L",
            "https://www.tpex.org.tw/openapi/v1/mopsfin_t187ap05_O"
        )
        endpoints.forEach { u ->
            val arr = fetchArray(u) ?: return@forEach
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val code = pick(o, "公司代號", "公司代碼", "Code", "SecuritiesCompanyCode") ?: continue
                val yoy = pickNumberContains(o, listOf("去年同月增減", "年增", "YoY", "YearOverYear"))
                if (yoy != null) map[code] = yoy
            }
        }
        return map
    }

    private fun loadTwseQuotes(
        sectorMap: Map<String, Pair<String, StockSector>>,
        epsMap: Map<String, Double>, revMap: Map<String, Double>, out: MutableList<MarketStock>
    ) {
        val arr = fetchArray("https://openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val code = o.optString("Code").trim()
            val meta = sectorMap[code] ?: continue
            val close = num(o.optString("ClosingPrice")) ?: continue
            val open = num(o.optString("OpeningPrice")) ?: close
            val high = num(o.optString("HighestPrice")) ?: close
            val low = num(o.optString("LowestPrice")) ?: close
            val volShares = longNum(o.optString("TradeVolume")) ?: 0L
            val change = num(o.optString("Change")) ?: 0.0
            val prev = if (close - change != 0.0) close - change else close
            val pct = if (prev != 0.0) change / prev * 100.0 else 0.0
            out += MarketStock(code, meta.first, Market.TWSE, meta.second, open, high, low, close, pct, (volShares / 1000L).toInt(), epsMap[code], revMap[code])
        }
    }

    private fun loadTpexQuotes(
        sectorMap: Map<String, Pair<String, StockSector>>,
        epsMap: Map<String, Double>, revMap: Map<String, Double>, out: MutableList<MarketStock>
    ) {
        val arr = fetchArray("https://www.tpex.org.tw/openapi/v1/tpex_mainboard_daily_close_quotes") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val code = pick(o, "SecuritiesCompanyCode", "Code", "代號") ?: continue
            val meta = sectorMap[code] ?: continue
            val close = numberOf(o, "Close", "ClosingPrice", "收盤") ?: continue
            val open = numberOf(o, "Open", "OpeningPrice", "開盤") ?: close
            val high = numberOf(o, "High", "HighestPrice", "最高") ?: close
            val low = numberOf(o, "Low", "LowestPrice", "最低") ?: close
            val volShares = longNumberOf(o, "TradingShares", "TradeVolume", "成交股數") ?: 0L
            val pct = numberOf(o, "ChangePercent", "ChangePct", "漲跌幅") ?: 0.0
            out += MarketStock(code, meta.first, Market.TPEX, meta.second, open, high, low, close, pct, (volShares / 1000L).toInt(), epsMap[code], revMap[code])
        }
    }

    private fun parseYahoo(raw: String): List<DailyBar> = runCatching {
        val chart = JSONObject(raw).getJSONObject("chart")
        val result = chart.optJSONArray("result")?.optJSONObject(0) ?: return@runCatching emptyList()
        val ts = result.optJSONArray("timestamp") ?: return@runCatching emptyList()
        val quote = result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
        val opens = quote.getJSONArray("open")
        val highs = quote.getJSONArray("high")
        val lows = quote.getJSONArray("low")
        val closes = quote.getJSONArray("close")
        val vols = quote.getJSONArray("volume")
        val bars = mutableListOf<DailyBar>()
        for (i in 0 until ts.length()) {
            if (closes.isNull(i)) continue
            val c = closes.optDouble(i, Double.NaN)
            if (!c.isFinite()) continue
            bars += DailyBar(
                ts.optLong(i) * 1000L,
                opens.optDouble(i, c), highs.optDouble(i, c), lows.optDouble(i, c), c,
                if (vols.isNull(i)) 0L else vols.optLong(i)
            )
        }
        bars
    }.getOrElse { emptyList() }

    private fun fetchArray(url: String): JSONArray? = fetchText(url)?.let { runCatching { JSONArray(it) }.getOrNull() }

    private fun fetchText(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 12_000
        c.setRequestProperty("User-Agent", ua)
        c.setRequestProperty("Accept", "application/json")
        try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }.getOrNull()

    private fun pick(o: JSONObject, vararg keys: String): String? {
        keys.forEach { k -> if (o.has(k)) return o.optString(k).trim().takeIf { it.isNotBlank() } }
        return null
    }
    private fun pickContains(o: JSONObject, tokens: List<String>): String? {
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (tokens.any { k.contains(it, true) }) return o.optString(k).trim().takeIf { it.isNotBlank() }
        }
        return null
    }
    private fun pickNumberContains(o: JSONObject, tokens: List<String>): Double? {
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (tokens.any { k.contains(it, true) }) num(o.optString(k))?.let { return it }
        }
        return null
    }
    private fun numberOf(o: JSONObject, vararg keys: String): Double? = keys.firstNotNullOfOrNull { k -> if (o.has(k)) num(o.optString(k)) else null }
    private fun longNumberOf(o: JSONObject, vararg keys: String): Long? = keys.firstNotNullOfOrNull { k -> if (o.has(k)) longNum(o.optString(k)) else null }
    private fun num(s: String): Double? = s.replace(",", "").replace("%", "").trim().takeIf { it.isNotBlank() && it != "--" && it != "---" }?.toDoubleOrNull()
    private fun longNum(s: String): Long? = s.replace(",", "").trim().toDoubleOrNull()?.toLong()
}
