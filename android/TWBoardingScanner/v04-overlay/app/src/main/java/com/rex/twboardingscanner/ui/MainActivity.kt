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
    private lateinit var paperLoop: com.rex.twboardingscanner.paper.PaperLoop
    private lateinit var posterExport: PosterExport
    private var chartDialog: StockChartDialog? = null
    private val adapter = SignalAdapter(onClick = { result ->
        openStockChart(result.code, result.name, result.snapshot.sourceStock?.market, result.snapshot.bars)
    }, onFinancial = { result -> showFinancialReport(result) })
    private lateinit var financialProvider: com.rex.twboardingscanner.data.FinancialDataProvider
    private val auctionProvider = com.rex.twboardingscanner.data.ClosingAuctionProvider()
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
    private var openSectorBoxes: Map<StockSector, CheckBox>? = null
    private var sectorCountNote: TextView? = null
    private fun refreshSectorCounts() {
        openSectorBoxes?.forEach { (sector,box) ->
            box.text="${sector.label}（${if(sectorCounts.isEmpty()) "載入中" else "${sectorCounts[sector] ?: 0} 檔"}）"
        }
        sectorCountNote?.text=if(sectorCounts.isEmpty()) "正在取得分類，尚未載入的股數不會顯示為 0。" else
            "本次可掃描 ${sectorCounts.values.sum()} 檔 · 已選 ${openSectorBoxes?.filterValues { it.isChecked }?.keys?.sumOf { sectorCounts[it] ?: 0 } ?: 0} 檔\n按官方產業別合併顯示；電腦／資訊／雲端不等同純 AI 題材。"
    }
    private val radarSelections = mutableMapOf<RadarType, Set<String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(b.root) { view,insets ->
            val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(10)+bars.left,dp(6)+bars.top,dp(10)+bars.right,dp(6)+bars.bottom)
            insets
        }

        provider = MarketDataProvider(this)
        financialProvider = com.rex.twboardingscanner.data.FinancialDataProvider(this)
        history = SignalHistoryDb(this)
        createNotificationChannel()
        askNotificationPermission()
        loadScanSettings()

        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        b.resultFilters.onSelected = { index -> selectedTab = index; render() }

        b.conditionAButton.setOnClickListener { showRadarConditions(RadarType.A_EARLY_BREAKOUT) }
        b.conditionBButton.setOnClickListener { showRadarConditions(RadarType.B_DEEP_REVERSAL) }
        b.conditionCButton.setOnClickListener { showRadarConditions(RadarType.C_LONG_RED_VOLUME) }
        b.scanConditionButton.setOnClickListener { showScanConditionsDialog() }
        posterExport=PosterExport(this)
        b.refreshButton.text="導出海報"
        b.refreshButton.setOnClickListener {
            val snapshot=latest.toList()
            posterExport.generate(isScanning,ScanPoster.collect(snapshot),b.scanProgress.progress,b.scanProgress.max)
        }
        b.logButton.setOnClickListener { showLogDatePicker() }
        b.searchButton.setOnClickListener { searchHistory() }
        b.searchInput.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { searchHistory(); true } else false
        }

        b.backtestButton.setOnClickListener { startActivity(android.content.Intent(this,BacktestActivity::class.java)) }
        b.paperButton.setOnClickListener { startActivity(android.content.Intent(this,PaperTradingActivity::class.java)) }
        paperLoop=com.rex.twboardingscanner.paper.PaperLoop(this)
        if(runCatching { com.rex.twboardingscanner.paper.PaperRepository(this).read().enabled }.getOrDefault(false))
            com.rex.twboardingscanner.paper.PaperWorker.schedule(this)
        b.startScanButton.setOnClickListener { startFullScan() }
        b.updateButton.setOnClickListener { startActivity(android.content.Intent(this,AppUpdateActivity::class.java).putExtra("startUpdate",true)) }
        b.progressTitle.text="尚未開始掃描"
        b.progressCount.text="設定好條件後，請按「開始掃描」"
        b.etaText.text="待命中"
        b.scanProgress.isIndeterminate=false
        render()
    }

    override fun onResume() { super.onResume();loadScanSettings();if(::paperLoop.isInitialized)paperLoop.start() }
    override fun onPause() { if(::paperLoop.isInitialized)paperLoop.stop();super.onPause() }

    private fun startFullScan() {
        if (isScanning) {
            Toast.makeText(this, "掃描進行中", Toast.LENGTH_SHORT).show()
            return
        }

        val scanDate = java.time.LocalDate.now(com.rex.twboardingscanner.domain.RuleMetrics.TAIPEI)
        val scanRules = radarSelections.mapValues { it.value.toSet() }
        val scanSectors = enabledSectors.toSet()
        isScanning = true
        b.startScanButton.isEnabled=false;b.startScanButton.text="掃描中…"
        latest = emptyList()
        b.scanProgress.progress = 0
        b.scanProgress.max = 0
        render()
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
                    b.startScanButton.isEnabled=true;b.startScanButton.text="開始掃描"
                    b.scanProgress.isIndeterminate = false
                    b.progressCount.text = "無法取得上市櫃資料，請檢查網路"
                }
                return@submit
            }

            runOnUiThread { sectorCounts = universe.groupingBy { it.sector }.eachCount(); refreshSectorCounts() }

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
                                    fun evaluate(stockToUse: com.rex.twboardingscanner.domain.MarketStock) = RadarType.entries.mapNotNull { type ->
                                        val datedBars = calculator.barsForRadar(bars, type, scanDate)
                                        if (datedBars.isEmpty()) null else {
                                            val snap = calculator.build(stockToUse, datedBars)
                                            when(type) {
                                                RadarType.A_EARLY_BREAKOUT -> engine.evaluateA(snap, scanRules.getValue(type))
                                                RadarType.B_DEEP_REVERSAL -> engine.evaluateB(snap, scanRules.getValue(type))
                                                RadarType.C_LONG_RED_VOLUME -> engine.evaluateC(snap, scanRules.getValue(type))
                                            }
                                        }
                                    }
                                    val initial = evaluate(stock)
                                    // Fetch financial tables only for candidates that pass selected technical rules.
                                    val candidate = initial.any { result -> result.checks.filter { it.selected && !it.extra }.all { it.state == com.rex.twboardingscanner.domain.CheckState.PASS } }
                                    val r = if (candidate) {
                                        val report = financialProvider.load(stock.code)
                                        evaluate(stock.copy(financials = report, quarterlyEps = report.eps,
                                            epsTtm = report.ttm(scanDate), closingAuction = auctionProvider.load(stock.code, stock.market)))
                                    } else initial
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
            runCatching { com.rex.twboardingscanner.paper.PaperRepository(this).publish(copy) }
                .onFailure { runOnUiThread { Toast.makeText(this,"模擬候選清單儲存失敗，請檢查儲存空間",Toast.LENGTH_LONG).show() } }

            runOnUiThread {
                isScanning = false
                b.startScanButton.isEnabled=true;b.startScanButton.text="開始掃描"
                b.scanProgress.progress = total
                b.progressTitle.text = "全市場掃描完成"
                b.progressCount.text = "已處理 ${total} / ${total} 檔（100%）"
                b.etaText.text = "掃描完成"
                b.progressStats.text = "已分析 ${analyzed.get()}｜排除 ${excluded.get()}｜資料不足 ${insufficient.get()}｜失敗 ${failed.get()}"
                render()
                handler.removeCallbacksAndMessages(null)

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

    private fun render() {
        val groups = (0..4).map { ResultFilters.select(latest, it) }
        b.resultFilters.update(groups.map { it.size }, selectedTab)
        adapter.submit(groups[selectedTab])
    }

    private fun loadScanSettings() {
        val saved = prefs.getStringSet("enabled_sectors", null)
        enabledSectors = if (saved == null) {
            StockSector.entries.toMutableSet()
        } else {
            saved.mapNotNull { runCatching { StockSector.valueOf(it) }.getOrNull() }.toMutableSet()
        }
        if(saved != null && StockSector.entries.filter { it != StockSector.UNKNOWN }.all { it.name in saved }) enabledSectors.add(StockSector.UNKNOWN)
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
        sectorCountNote=TextView(this).apply { setTextColor(Color.parseColor("#88AEC8"));textSize=12f }
        box.addView(sectorCountNote)
        val sectorBoxes = linkedMapOf<StockSector, CheckBox>()
        StockSector.entries.forEach { sector ->
            val cb = CheckBox(this).apply {
                text = sector.label
                setTextColor(Color.WHITE)
                isChecked = sector in enabledSectors
                setOnCheckedChangeListener { _,_ -> refreshSectorCounts() }
            }
            sectorBoxes[sector] = cb
            box.addView(cb)
        }

        openSectorBoxes=sectorBoxes
        refreshSectorCounts()
        val sectorDialog=MaterialAlertDialogBuilder(this)
            .setTitle("共同產業篩選")
            .setView(scroll)
            .setNeutralButton("產業全選",null)
            .setNegativeButton("取消", null)
            .setPositiveButton("儲存條件") { _, _ ->
                val chosen = sectorBoxes.filterValues { it.isChecked }.keys.toMutableSet()
                enabledSectors = if (chosen.isEmpty()) StockSector.entries.toMutableSet() else chosen
                saveScanSettings()
                requestConfiguredScan()
            }
            .create()
        sectorDialog.setOnDismissListener { openSectorBoxes=null;sectorCountNote=null }
        sectorDialog.setOnShowListener { sectorDialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { sectorBoxes.values.forEach { it.isChecked=true } } }
        sectorDialog.show()
        if(sectorCounts.isEmpty()&&!isScanning)coordinator.submit {
            val universe=provider.loadUniverse { }
            runOnUiThread { if(!isFinishing&&!isDestroyed){sectorCounts=universe.groupingBy { it.sector }.eachCount();refreshSectorCounts();if(universe.isEmpty())sectorCountNote?.text="分類暫時無法取得，請稍後重試"} }
        }
    }

    private fun requestConfiguredScan() {
        Toast.makeText(this, "條件已儲存；請按「開始掃描」套用新條件", Toast.LENGTH_LONG).show()
    }

    private var financialDialog: FinancialDialog? = null
    private fun showFinancialReport(result: SignalResult) {
        financialDialog?.dismiss()
        financialDialog=FinancialDialog(this,financialProvider,result.code,result.name,result.snapshot.sourceStock?.financials).also { it.show() }
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
            text = "勾選條件全部滿足才入選；未勾選不限制。基本條件預設全選，額外查核預設關閉。\nA/B 排除台灣當日日線；C 使用最新日 K。\nB 通過後若 RSI>50 且收盤>20日線，另標示技術升級；不辨識 W 底形狀。\n額外資料不足標示待查核，勾選後會阻擋入選。HiStock 財報會查核技術候選股，快取24小時；HTTP403/429、格式變更、資料過期均待查核。逐日法人尚未接入。分數為所選條件通過率，不是公司評級。\n財務條件共用於ABC、可各自勾選；EPS為正不代表現金流為正。產業EPS範圍不是通用及格線，未設成硬門檻。\n現金流使用原站最新列示期間，不跨季加總；金融業現金流需另行解讀。營業利益率為正不能排除一次性業外收益。\n風險估算：前20日最低價為支撐，支撐下1%為失效價，前60日最高價為壓力；(壓力−現價)/(現價−失效價)≥2。\n漲停估算：前收盤×1.1，按台股跳動單位向下取整；非交易所公告漲停價。\nC 為觀察訊號，尚無回測證明隔天一定續漲。"
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
            .setPositiveButton("儲存條件", null).create()
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
        financialDialog?.dismiss()
        posterExport.close()
        history.close()
        super.onDestroy()
    }
}
