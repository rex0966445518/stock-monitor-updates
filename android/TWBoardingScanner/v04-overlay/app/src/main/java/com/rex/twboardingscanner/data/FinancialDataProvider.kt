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
    /** Keep requests rate-limited; fetch only statements needed by checked rules. */
    @Synchronized fun load(code: String, topics:Set<String> = ALL_TOPICS, forceRefresh:Boolean = false, onPartial:((FinancialReport)->Unit)? = null): FinancialReport {
        require(code.matches(Regex("[0-9]{4}")))
        val messages = mutableListOf<String>()
        val times = mutableListOf<Long>()
        fun <T> page(slug: String, parse: (String) -> T): T? {
            val file = File(cache, "${code}_$slug.html")
            val key = "${code}_$slug"
            val now = System.currentTimeMillis()
            return try {
                if (!forceRefresh && file.exists() && now - file.lastModified() in 0 until 86_400_000L) {
                    val value = parse(file.readText())
                    times.add(file.lastModified())
                    value
                } else {
                    if (now < blockedUntil) error("來源暫停服務（HTTP 403／429），稍後重試或開啟原站")
                    failures[key]?.takeIf { !forceRefresh && now-it.first < 300_000 }?.let { error(it.second) }
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
                        runCatching {
                            val temp=File(cache,"${code}_$slug.tmp")
                            temp.writeText(raw);check(temp.renameTo(file))
                        }
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
        var eps:Pair<List<QuarterEps>,Map<Int,Double>>?=null
        var cash:List<PeriodValue>?=null
        var margin:List<PeriodValue>?=null
        fun report()=FinancialReport(eps?.first.orEmpty(),eps?.second.orEmpty(),cash.orEmpty(),margin.orEmpty(),
            times.minOrNull() ?: System.currentTimeMillis(),messages.toList())
        fun publish(){onPartial?.let{runCatching{it(report())}}}
        if("每股盈餘" in topics){eps=page("每股盈餘") { HistockParser.eps(it,code) };publish()}
        if("利潤比率" in topics){margin=page("利潤比率") { HistockParser.periodValues(it,code,"利潤比率","營業利益率") };publish()}
        if("現金流量表" in topics){cash=page("現金流量表") { HistockParser.periodValues(it,code,"現金流量表","營業現金流") };publish()}
        return report()
    }
    /** Cache reads never wait behind another stock's network request. No HTTP on this path. */
    fun cached(code:String,now:Long=System.currentTimeMillis()):FinancialReport? {
        require(code.matches(Regex("[0-9]{4}")))
        val times=mutableListOf<Long>()
        fun <T> read(slug:String,parse:(String)->T):T?=runCatching{
            val f=File(cache,"${code}_$slug.html")
            if(!f.exists()||now-f.lastModified() !in 0 until 86_400_000L)return@runCatching null
            val data=parse(f.readText());times+=f.lastModified();data
        }.getOrNull()
        val eps=read("每股盈餘"){HistockParser.eps(it,code)}
        val margin=read("利潤比率"){HistockParser.periodValues(it,code,"利潤比率","營業利益率")}
        val cash=read("現金流量表"){HistockParser.periodValues(it,code,"現金流量表","營業現金流")}
        if(times.isEmpty())return null
        return FinancialReport(eps?.first.orEmpty(),eps?.second.orEmpty(),cash.orEmpty(),margin.orEmpty(),times.minOrNull()?:now)
    }
    companion object {
        val ALL_TOPICS=setOf("每股盈餘","現金流量表","利潤比率")
        /** A selected technical or liquidity rule NEVER triggers three financial HTTP pages. */
        fun topicsForRuleIds(ids:Collection<String>):Set<String> = buildSet {
            if(ids.any{it=="extra_eps"||it in setOf("fin_ttm","fin_positive","fin_growth","fin_year3","fin_year5")})add("每股盈餘")
            if("fin_cash" in ids)add("現金流量表")
            if("fin_margin" in ids)add("利潤比率")
        }
        /** Every selected non-financial gate must pass, including optional technical filters. */
        fun neededTopics(result:SignalResult,ids:Set<String>,excluded:Set<String> = emptySet()):Set<String> {
            val topics=topicsForRuleIds(ids)
            if(topics.isEmpty())return emptySet()
            val technical=ids.filter{topicsForRuleIds(listOf(it)).isEmpty()}
            if(technical.any{id->result.checks.none{it.id==id&&it.state==CheckState.PASS}})return emptySet()
            val metrics=RuleMetrics(result.snapshot)
            if(ExclusionConditions.forRadar(result.radarType).any{it.id in excluded&&it.test(metrics)!=false})return emptySet()
            // An EPS download cannot repair an absent/negative revenue gate.
            if("extra_eps" in ids&&result.snapshot.sourceStock?.revenueYoY?.let{it.isFinite()&&it>=0}!=true)return emptySet()
            return topics
        }
        fun url(code: String, topic: String) = "https://histock.tw/stock/$code/" + URLEncoder.encode(topic,"UTF-8")
    }
}
