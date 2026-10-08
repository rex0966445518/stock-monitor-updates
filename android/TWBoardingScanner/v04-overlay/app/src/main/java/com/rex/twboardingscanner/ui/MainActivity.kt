package com.rex.twboardingscanner.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.rex.twboardingscanner.data.HistoryRow
import com.rex.twboardingscanner.data.MarketDataProvider
import com.rex.twboardingscanner.data.SignalHistoryDb
import com.rex.twboardingscanner.databinding.ActivityMainBinding
import com.rex.twboardingscanner.domain.DailyBar
import com.rex.twboardingscanner.domain.RadarType
import com.rex.twboardingscanner.domain.ScanConditions
import com.rex.twboardingscanner.domain.ScoringEngine
import com.rex.twboardingscanner.domain.SignalLight
import com.rex.twboardingscanner.domain.SignalResult
import com.rex.twboardingscanner.domain.StockSector
import com.rex.twboardingscanner.domain.TechnicalCalculator
import java.util.Calendar
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity: AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private var chartDialog: StockChartDialog? = null
    private val adapter = SignalAdapter { result ->
        openStockChart(result.code, result.name, result.snapshot.sourceStock?.market, result.snapshot.bars)
    }
    private val engine = ScoringEngine()
    private val calculator = TechnicalCalculator()
    private lateinit var provider: MarketDataProvider
    private lateinit var history: SignalHistoryDb

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val coordinator = Executors.newSingleThreadExecutor()
    private val workers = Executors.newFixedThreadPool(6)

    private var latest = listOf<SignalResult>()
    private var selectedTab = 0
    private var isScanning = false

    private val prefs by lazy { getSharedPreferences("scanner_filters", MODE_PRIVATE) }
    private var enabledSectors: MutableSet<StockSector> = mutableSetOf()
    private var sectorCounts: Map<StockSector, Int> = emptyMap()
    private val radarSelections = mutableMapOf<RadarType, Set<String>>()
    private var rescanPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        provider = MarketDataProvider(this)
        history = SignalHistoryDb(this)
        createNotificationChannel()
        askNotificationPermission()
        loadScanSettings()

        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        listOf("今日新觸發", "A｜起漲", "B｜深跌", "C｜長紅爆量", "待查核", "未通過").forEach {
            b.tabLayout.addTab(b.tabLayout.newTab().setText(it))
        }
        b.tabLayout.addOnTabSelectedListener(object: TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                selectedTab = tab?.position ?: 0
                render()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        b.conditionAButton.setOnClickListener { showRadarConditions(RadarType.A_EARLY_BREAKOUT) }
        b.conditionBButton.setOnClickListener { showRadarConditions(RadarType.B_DEEP_REVERSAL) }
        b.conditionCButton.setOnClickListener { showRadarConditions(RadarType.C_LONG_RED_VOLUME) }
        b.scanConditionButton.setOnClickListener { showScanConditionsDialog() }
        b.refreshButton.setOnClickListener { startFullScan() }
        b.logButton.setOnClickListener { showLogDatePicker() }
        b.searchButton.setOnClickListener { searchHistory() }

        startFullScan()
    }

    private fun startFullScan() {
        if (isScanning) {
            Toast.makeText(this, "掃描進行中", Toast.LENGTH_SHORT).show()
            return
        }

        val scanDate = java.time.LocalDate.now(com.rex.twboardingscanner.domain.RuleMetrics.TAIPEI)
        val scanRules = radarSelections.mapValues { it.value.toSet() }
        val scanSectors = enabledSectors.toSet()
        isScanning = true
        b.scanProgress.isIndeterminate = true
        b.progressTitle.text = "準備全市場資料…"
        b.progressCount.text = "正在取得上市櫃清單"
        b.etaText.text = "計算預估時間中"
        b.progressStats.text = "已分析 0｜排除 0｜資料不足 0｜失敗 0"

        coordinator.submit {
            val universe = provider.loadUniverse { msg ->
                runOnUiThread { b.progressTitle.text = msg }
            }

            if (universe.isEmpty()) {
                runOnUiThread {
                    isScanning = false
                    b.scanProgress.isIndeterminate = false
                    b.progressCount.text = "無法取得上市櫃資料，請檢查網路"
                }
                return@submit
            }

            sectorCounts = universe.groupingBy { it.sector }.eachCount()

            val total = universe.size
            val done = AtomicInteger(0)
            val analyzed = AtomicInteger(0)
            val excluded = AtomicInteger(0)
            val insufficient = AtomicInteger(0)
            val failed = AtomicInteger(0)
            val newCount = AtomicInteger(0)
            val latch = CountDownLatch(total)
            val results = Collections.synchronizedList(mutableListOf<SignalResult>())
            val started = System.currentTimeMillis()

            runOnUiThread {
                b.scanProgress.isIndeterminate = false
                b.scanProgress.max = total
                b.scanProgress.progress = 0
                b.progressTitle.text = "全市場逐檔掃描中"
                b.statusText.text = "全市場 ${total} 檔｜每一檔都會回報處理狀態"
            }

            universe.forEach { stock ->
                workers.submit {
                    try {
                        val userExcluded =
                            stock.sector !in scanSectors

                        if (userExcluded) {
                            excluded.incrementAndGet()
                        } else {
                            val bars = loadHistoryWithRetry(stock)
                            if (bars.isEmpty()) {
                                failed.incrementAndGet()
                            } else if (bars.size < 20) {
                                insufficient.incrementAndGet()
                            } else {
                                run {
                                    analyzed.incrementAndGet()
                                    val r = RadarType.entries.mapNotNull { type ->
                                        val datedBars = calculator.barsForRadar(bars, type, scanDate)
                                        if (datedBars.isEmpty()) null else {
                                            val snap = calculator.build(stock, datedBars)
                                            when(type) {
                                                RadarType.A_EARLY_BREAKOUT -> engine.evaluateA(snap, scanRules.getValue(type))
                                                RadarType.B_DEEP_REVERSAL -> engine.evaluateB(snap, scanRules.getValue(type))
                                                RadarType.C_LONG_RED_VOLUME -> engine.evaluateC(snap, scanRules.getValue(type))
                                            }
                                        }
                                    }
                                    results.addAll(r)

                                    r.filter { it.light != SignalLight.NONE }.forEach { sig ->
                                        if (history.insertIfNew(sig)) {
                                            newCount.incrementAndGet()
                                            if (sig.light == SignalLight.RED || sig.light == SignalLight.ORANGE) {
                                                notifyNewSignal(sig)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                        failed.incrementAndGet()
                    } finally {
                        val d = done.incrementAndGet()
                        updateProgress(
                            d, total, started,
                            analyzed.get(), excluded.get(), insufficient.get(), failed.get()
                        )

                        if (d % 20 == 0 || d == total) {
                            val copy = synchronized(results) { results.toList() }
                            runOnUiThread {
                                latest = copy
                                render()
                            }
                        }
                        latch.countDown()
                    }
                }
            }

            latch.await()
            val copy = synchronized(results) { results.toList() }
            latest = copy

            runOnUiThread {
                isScanning = false
                b.scanProgress.progress = total
                b.progressTitle.text = "全市場掃描完成"
                b.progressCount.text = "已處理 ${total} / ${total} 檔（100%）"
                b.etaText.text = "新觸發 ${newCount.get()} 檔"
                updateSummary(
                    total, copy, newCount.get(),
                    analyzed.get(), excluded.get(), insufficient.get(), failed.get()
                )
                render()
                handler.removeCallbacksAndMessages(null)
                if (rescanPending) {
                    rescanPending = false
                    startFullScan()
                } else handler.postDelayed({ startFullScan() }, 15 * 60 * 1000L)
            }
        }
    }

    private fun loadHistoryWithRetry(stock: com.rex.twboardingscanner.domain.MarketStock): List<DailyBar> {
        var bars: List<DailyBar> = emptyList()
        for (attempt in 0 until 3) {
            bars = runCatching { provider.loadHistory(stock) }.getOrDefault(emptyList())
            if (bars.isNotEmpty()) break
            Thread.sleep(300L * (attempt + 1))
        }
        return bars
    }

    private fun updateProgress(
        done:Int, total:Int, started:Long,
        analyzed:Int, excluded:Int, insufficient:Int, failed:Int
    ) {
        val elapsed = System.currentTimeMillis() - started
        val remain = if (done > 0) elapsed.toDouble() / done * (total - done) else 0.0
        val min = (remain / 60000).toInt()
        val sec = ((remain % 60000) / 1000).toInt()

        runOnUiThread {
            b.scanProgress.progress = done
            b.progressCount.text = "已處理 ${done} / ${total} 檔（${done * 100 / total}%）"
            b.etaText.text = if (done < 10) "正在估算…" else "剩餘約 ${min}分${sec}秒"
            b.progressStats.text = "已分析 ${analyzed}｜排除 ${excluded}｜資料不足 ${insufficient}｜失敗 ${failed}"
        }
    }

    private fun updateSummary(
        universe:Int,
        all:List<SignalResult>,
        newTriggers:Int,
        analyzed:Int,
        excluded:Int,
        insufficient:Int,
        failed:Int
    ) {
        val a = all.count { it.radarType == RadarType.A_EARLY_BREAKOUT && it.light != SignalLight.NONE }
        val bb = all.count { it.radarType == RadarType.B_DEEP_REVERSAL && it.light != SignalLight.NONE }
        val c = all.count { it.radarType == RadarType.C_LONG_RED_VOLUME && it.light != SignalLight.NONE }
        b.statusText.text =
            "全市場 ${universe}｜已分析 ${analyzed}｜排除 ${excluded}｜不足 ${insufficient}｜失敗 ${failed}\nA ${a}｜B ${bb}｜C ${c}｜新觸發 ${newTriggers}"
        b.progressStats.text = "已分析 ${analyzed}｜排除 ${excluded}｜資料不足 ${insufficient}｜失敗 ${failed}"
    }

    private fun render() {
        val filtered = when(selectedTab) {
            1 -> latest.filter { it.radarType == RadarType.A_EARLY_BREAKOUT && it.light != SignalLight.NONE }
            2 -> latest.filter { it.radarType == RadarType.B_DEEP_REVERSAL && it.light != SignalLight.NONE }
            3 -> latest.filter { it.radarType == RadarType.C_LONG_RED_VOLUME && it.light != SignalLight.NONE }
            4 -> latest.filter { r -> r.checks.any { it.selected && it.state == com.rex.twboardingscanner.domain.CheckState.PENDING } }
            5 -> latest.filter { r -> r.checks.any { it.selected && it.state == com.rex.twboardingscanner.domain.CheckState.FAIL } }
            else -> latest.filter { it.light != SignalLight.NONE }
        }.distinctBy { "${it.code}_${it.radarType}" }.sortedByDescending { it.score }

        b.summaryA.text = "A 起漲\n${latest.count { it.radarType == RadarType.A_EARLY_BREAKOUT && it.light != SignalLight.NONE }}"
        b.summaryB.text = "B 反轉\n${latest.count { it.radarType == RadarType.B_DEEP_REVERSAL && it.light != SignalLight.NONE }}"
        b.summaryC.text = "C 長紅爆量\n${latest.count { it.radarType == RadarType.C_LONG_RED_VOLUME && it.light != SignalLight.NONE }}"
        adapter.submit(filtered)
    }

    private fun loadScanSettings() {
        val saved = prefs.getStringSet("enabled_sectors", null)
        enabledSectors = if (saved == null) {
            StockSector.entries.toMutableSet()
        } else {
            saved.mapNotNull { runCatching { StockSector.valueOf(it) }.getOrNull() }.toMutableSet()
        }
        if (enabledSectors.isEmpty()) enabledSectors = StockSector.entries.toMutableSet()

        // New definitions start with every base rule selected; old rule IDs never migrate.
        prefs.edit().apply {
            RadarType.entries.forEach { remove("rules_${it.name}") }
            remove("avoid_hot"); remove("avoid_limit_up"); remove("avoid_negative_eps")
        }.apply()
        RadarType.entries.forEach { type ->
            val rules = ScanConditions.forRadar(type)
            val savedRules = prefs.getStringSet("rules_${ScanConditions.VERSION}_${type.name}", null)
            radarSelections[type] = savedRules?.intersect(rules.map { it.id }.toSet())
                ?: rules.filter { it.defaultEnabled }.map { it.id }.toSet()
        }
    }

    private fun saveScanSettings() {
        prefs.edit()
            .putStringSet("enabled_sectors", enabledSectors.map { it.name }.toSet())
            .apply()
    }

    private fun showScanConditionsDialog() {
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        scroll.addView(box)

        fun heading(text:String) {
            box.addView(TextView(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 17f
                setPadding(0, dp(10), 0, dp(4))
            })
        }

        heading("產業篩選")
        val sectorBoxes = linkedMapOf<StockSector, CheckBox>()
        StockSector.entries.forEach { sector ->
            val cb = CheckBox(this).apply {
                text = "${sector.label}（${sectorCounts[sector] ?: 0}）"
                setTextColor(Color.WHITE)
                isChecked = sector in enabledSectors
            }
            sectorBoxes[sector] = cb
            box.addView(cb)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("共同產業篩選")
            .setView(scroll)
            .setNeutralButton("產業全選") { _, _ ->
                enabledSectors = StockSector.entries.toMutableSet()
                saveScanSettings()
            }
            .setNegativeButton("取消", null)
            .setPositiveButton("套用並重新掃描") { _, _ ->
                val chosen = sectorBoxes.filterValues { it.isChecked }.keys.toMutableSet()
                enabledSectors = if (chosen.isEmpty()) StockSector.entries.toMutableSet() else chosen
                saveScanSettings()
                requestConfiguredScan()
            }
            .show()
    }

    private fun requestConfiguredScan() {
        if (isScanning) {
            rescanPending = true
            Toast.makeText(this, "條件已儲存；本輪完成後自動依新條件重掃", Toast.LENGTH_LONG).show()
        } else startFullScan()
    }

    private fun showRadarConditions(type: RadarType) {
        val title = when (type) {
            RadarType.A_EARLY_BREAKOUT -> "A｜起漲掃描條件"
            RadarType.B_DEEP_REVERSAL -> "B｜深跌反轉掃描條件"
            RadarType.C_LONG_RED_VOLUME -> "C｜長紅爆量掃描條件"
        }
        val rules = ScanConditions.forRadar(type)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        box.addView(TextView(this).apply {
            text = "勾選條件全部滿足才入選；未勾選不限制。基本條件預設全選，額外查核預設關閉。\nA/B 排除台灣當日日線；C 使用最新日 K。\nB 通過後若 RSI>50 且收盤>20日線，另標示技術升級；不辨識 W 底形狀。\n額外資料不足標示待查核，勾選後會阻擋入選。目前四季EPS與逐日法人尚未接入。分數為所選條件通過率。\n風險估算：前20日最低價為支撐，支撐下1%為失效價，前60日最高價為壓力；(壓力−現價)/(現價−失效價)≥2。\n漲停估算：前收盤×1.1，按台股跳動單位向下取整；非交易所公告漲停價。\nC 為觀察訊號，尚無回測證明隔天一定續漲。"
            setTextColor(Color.parseColor("#9FBAD0"))
        })
        var extraHeadingShown = false
        val checks = rules.associate { rule ->
            if (rule.extra && !extraHeadingShown) {
                extraHeadingShown = true
                box.addView(TextView(this).apply {
                    text = "額外查核（選填）"
                    textSize = 18f
                    setTextColor(Color.parseColor("#42BBFF"))
                    setPadding(0, dp(16), 0, dp(8))
                })
            }
            val cb = CheckBox(this).apply {
                text = rule.label
                setTextColor(Color.WHITE)
                isChecked = rule.id in radarSelections.getValue(type)
                minHeight = dp(48)
            }
            box.addView(cb)
            rule.id to cb
        }
        val scroll = ScrollView(this).apply { addView(box) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title).setView(scroll)
            .setNeutralButton("恢復預設", null)
            .setNegativeButton("取消", null)
            .setPositiveButton("儲存並掃描", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                rules.forEach { checks.getValue(it.id).isChecked = it.defaultEnabled }
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = checks.filterValues { it.isChecked }.keys.toSet()
                if (selected.isEmpty()) {
                    Toast.makeText(this, "請至少勾選一項條件", Toast.LENGTH_SHORT).show()
                } else {
                    radarSelections[type] = selected
                    prefs.edit().putStringSet("rules_${ScanConditions.VERSION}_${type.name}", selected).apply()
                    dialog.dismiss()
                    requestConfiguredScan()
                }
            }
        }
        dialog.show()
    }

    private fun showLogDatePicker() {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, y, m, d ->
                val date = String.format(Locale.TAIWAN, "%04d-%02d-%02d", y, m + 1, d)
                showHistoryCards("${date} 掃描日誌", history.queryByDate(date))
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun searchHistory() {
        val code = b.searchInput.text?.toString()?.trim().orEmpty()
        if (code.isBlank()) {
            Toast.makeText(this, "請輸入股號", Toast.LENGTH_SHORT).show()
            return
        }
        showHistoryCards("${code} 歷史掃描紀錄", history.queryByCode(code))
    }

    private fun showHistoryCards(title:String, rows:List<HistoryRow>) {
        if (rows.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage("沒有找到紀錄")
                .setPositiveButton("關閉", null)
                .show()
            return
        }

        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = HistoryAdapter { row -> openStockChart(row.code, row.name, bars = row.chartBars) }.apply { submit(rows) }
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(520)
            )
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(rv)
            .setPositiveButton("關閉", null)
            .show()
    }

    private fun openStockChart(code: String, name: String, market: com.rex.twboardingscanner.domain.Market? = null, bars: List<DailyBar> = emptyList()) {
        chartDialog?.dismiss()
        chartDialog = StockChartDialog(this, provider, code, name, market, bars).also { it.show() }
    }

    private fun notifyNewSignal(r:SignalResult) {
        val n = NotificationCompat.Builder(this, "signals")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("${r.code} ${r.name} 新觸發")
            .setContentText("${r.radarType.name}｜條件通過 ${r.score}%｜${r.reasons.take(2).joinToString("+")}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify((r.code + r.radarType.name + r.light.name).hashCode(), n)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel("signals", "上車雷達訊號", NotificationManager.IMPORTANCE_HIGH)
                )
        }
    }

    private fun askNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                100
            )
        }
    }

    private fun dp(v:Int):Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        chartDialog?.dismiss()
        chartDialog = null
        handler.removeCallbacksAndMessages(null)
        coordinator.shutdownNow()
        workers.shutdownNow()
        history.close()
        super.onDestroy()
    }
}
