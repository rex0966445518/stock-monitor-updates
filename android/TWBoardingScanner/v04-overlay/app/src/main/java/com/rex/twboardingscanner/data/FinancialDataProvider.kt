package com.rex.twboardingscanner.data

import android.content.Context
import com.rex.twboardingscanner.domain.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Public pages only; no cookies, credentials, proxy switching or challenge bypass. */
class FinancialDataProvider(context: Context) {
    private val cache = File(context.cacheDir, "histock_financial_v1").apply { mkdirs() }
    private val failures = mutableMapOf<String, Pair<Long,String>>()
    private var blockedUntil = 0L
    private var nextRequest = 0L
    @Synchronized fun load(code: String): FinancialReport {
        require(code.matches(Regex("[0-9]{4}")))
        val messages = mutableListOf<String>()
        val times = mutableListOf<Long>()
        fun <T> page(slug: String, parse: (String) -> T): T? {
            val file = File(cache, "${code}_$slug.html")
            val key = "${code}_$slug"
            val now = System.currentTimeMillis()
            return try {
                if (file.exists() && now - file.lastModified() in 0 until 86_400_000L) {
                    val value = parse(file.readText())
                    times.add(file.lastModified())
                    value
                } else {
                    if (now < blockedUntil) error("來源暫停服務（HTTP 403／429），稍後重試或開啟原站")
                    failures[key]?.takeIf { now-it.first < 300_000 }?.let { error(it.second) }
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val wait = nextRequest - System.currentTimeMillis()
                    if (wait > 0) Thread.sleep(wait.coerceAtMost(1000))
                    val connection = URL(url(code, slug)).openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 6000
                        connection.readTimeout = 8000
                        connection.instanceFollowRedirects = false
                        connection.setRequestProperty("User-Agent", "TWBoardingScanner/0.4.7")
                        val status = connection.responseCode
                        if (status == 403 || status == 429) blockedUntil = System.currentTimeMillis() + 1_800_000
                        check(status == 200) { "HTTP $status，無法取得資料" }
                        val raw = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        val value = parse(raw)
                        runCatching { file.writeText(raw) }
                        times.add(System.currentTimeMillis())
                        failures.remove(key)
                        value
                    } finally { connection.disconnect(); nextRequest = System.currentTimeMillis() + 1000 }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                messages.add("$slug：查核已取消")
                null
            } catch (e: Exception) {
                val message = e.message ?: "讀取失敗"
                failures[key] = System.currentTimeMillis() to message
                messages.add("$slug：$message")
                null
            }
        }
        val eps = page("每股盈餘") { HistockParser.eps(it,code) }
        val cash = page("現金流量表") { HistockParser.periodValues(it,code,"現金流量表","營業現金流") }
        val margin = page("利潤比率") { HistockParser.periodValues(it,code,"利潤比率","營業利益率") }
        return FinancialReport(eps?.first.orEmpty(),eps?.second.orEmpty(),cash.orEmpty(),margin.orEmpty(),
            times.minOrNull() ?: System.currentTimeMillis(),messages)
    }
    companion object {
        fun url(code: String, topic: String) = "https://histock.tw/stock/$code/" + URLEncoder.encode(topic,"UTF-8")
    }
}
