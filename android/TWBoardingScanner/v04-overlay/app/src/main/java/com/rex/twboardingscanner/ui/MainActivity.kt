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
    private val adapter = SignalAdapter()
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
    private var avoidHot = true
    private var avoidLimitUp = true
    private var avoidNegativeEps = true

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

        listOf("今日新觸發", "A｜起漲", "B｜深跌", "C｜長紅爆量", "等待區", "已失效").forEach {
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
                            stock.sector !in enabledSectors ||
                            (avoidLimitUp && stock.changePct >= 9.5) ||
                            (avoidNegativeEps && stock.epsTtm != null && stock.epsTtm < 0.0)

                        if (userExcluded) {
                            excluded.incrementAndGet()
                        } else {
                            val bars = loadHistoryWithRetry(stock)
                            if (bars.isEmpty()) {
                                failed.incrementAndGet()
                            } else if (bars.size < 20) {
                                insufficient.incrementAndGet()
                            } else {
                                val snap = calculator.build(stock, bars)
                                val hot = (snap.fiveDayGainPct ?: 0.0) >= 15.0 ||
                                    (snap.distanceFromMa20Pct ?: 0.0) >= 10.0 ||
                                    (snap.rsi ?: 0.0) >= 75.0

                                if (avoidHot && hot) {
                                    excluded.incrementAndGet()
                                } else {
                                    analyzed.incrementAndGet()
                                    val r = listOf(
                                        engine.evaluateA(snap),
                                        engine.evaluateB(snap),
                                        engine.evaluateC(snap)
                                    )
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
                handler.postDelayed({ startFullScan() }, 15 * 60 * 1000L)
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
            4 -> latest.filter { it.score in 65..84 }
            5 -> latest.filter { it.light == SignalLight.WEAKENING }
            else -> latest.filter { it.light == SignalLight.RED || it.light == SignalLight.ORANGE }
        }.distinctBy { "${it.code}_${it.radarType}" }.sortedByDescending { it.score }

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

        avoidHot = prefs.getBoolean("avoid_hot", true)
        avoidLimitUp = prefs.getBoolean("avoid_limit_up", true)
        avoidNegativeEps = prefs.getBoolean("avoid_negative_eps", true)
    }

    private fun saveScanSettings() {
        prefs.edit()
            .putStringSet("enabled_sectors", enabledSectors.map { it.name }.toSet())
            .putBoolean("avoid_hot", avoidHot)
            .putBoolean("avoid_limit_up", avoidLimitUp)
            .putBoolean("avoid_negative_eps", avoidNegativeEps)
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

        heading("排除條件")

        val hotBox = CheckBox(this).apply {
            text = "避開已經高漲的股"
            setTextColor(Color.WHITE)
            isChecked = avoidHot
        }
        val limitBox = CheckBox(this).apply {
            text = "避開已經漲停的股"
            setTextColor(Color.WHITE)
            isChecked = avoidLimitUp
        }
        val epsBox = CheckBox(this).apply {
            text = "避開 EPS 為負值的公司"
            setTextColor(Color.WHITE)
            isChecked = avoidNegativeEps
        }
        box.addView(hotBox)
        box.addView(limitBox)
        box.addView(epsBox)

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
            .setTitle("掃描條件")
            .setView(scroll)
            .setNeutralButton("產業全選") { _, _ ->
                enabledSectors = StockSector.entries.toMutableSet()
                avoidHot = hotBox.isChecked
                avoidLimitUp = limitBox.isChecked
                avoidNegativeEps = epsBox.isChecked
                saveScanSettings()
            }
            .setNegativeButton("取消", null)
            .setPositiveButton("套用並重新掃描") { _, _ ->
                val chosen = sectorBoxes.filterValues { it.isChecked }.keys.toMutableSet()
                enabledSectors = if (chosen.isEmpty()) StockSector.entries.toMutableSet() else chosen
                avoidHot = hotBox.isChecked
                avoidLimitUp = limitBox.isChecked
                avoidNegativeEps = epsBox.isChecked
                saveScanSettings()
                startFullScan()
            }
            .show()
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
            adapter = HistoryAdapter().apply { submit(rows) }
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

    private fun notifyNewSignal(r:SignalResult) {
        val n = NotificationCompat.Builder(this, "signals")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("${r.code} ${r.name} 新觸發")
            .setContentText("${r.radarType.name}｜${r.score}%｜${r.reasons.take(2).joinToString("+")}")
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
        handler.removeCallbacksAndMessages(null)
        coordinator.shutdownNow()
        workers.shutdownNow()
        history.close()
        super.onDestroy()
    }
}
