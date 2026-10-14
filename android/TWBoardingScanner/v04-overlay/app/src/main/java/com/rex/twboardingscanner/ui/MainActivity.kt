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
import com.rex.twboardingscanner.domain.DPattern
import com.rex.twboardingscanner.domain.DPatternConfig
import com.rex.twboardingscanner.domain.ScanConditions
import com.rex.twboardingscanner.domain.ScoringEngine
import com.rex.twboardingscanner.domain.SignalLight
import com.rex.twboardingscanner.domain.SignalResult
import com.rex.twboardingscanner.domain.StockSector
import com.rex.twboardingscanner.domain.StockScope
import com.rex.twboardingscanner.domain.TechnicalCalculator
import java.util.Calendar
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class MainActivity: AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var paperLoop: com.rex.twboardingscanner.paper.PaperLoop
    private lateinit var trendHoldCheck:CheckBox
    private lateinit var posterExport: PosterExport
    private var chartDialog: StockChartDialog? = null
    private val adapter = SignalAdapter(onClick = { result ->
        ChipKLauncher.open(this@MainActivity,result.code,result.name)
    }, onFinancial = { result -> showFinancialReport(result) })
    private lateinit var financialProvider: com.rex.twboardingscanner.data.FinancialDataProvider
    private val auctionProvider = com.rex.twboardingscanner.data.ClosingAuctionProvider()
    private val engine = ScoringEngine()
    private val calculator = TechnicalCalculator()
    private lateinit var provider: MarketDataProvider
    private lateinit var history: SignalHistoryDb

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val coordinator = Executors.newSingleThreadExecutor()
    private val guardExecutor=Executors.newSingleThreadExecutor()
    private val guardHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private val guardBusy=java.util.concurrent.atomic.AtomicBoolean(false)
    private val guardPoll=object:Runnable{override fun run(){refreshMarketGuard();guardHandler.postDelayed(this,30000)}}
    private val workers = Executors.newFixedThreadPool(6)
    private val enrichmentWorkers = Executors.newSingleThreadExecutor()

    private var latest = listOf<SignalResult>()
    private var latestPaper = listOf<SignalResult>()
    private val scanRevision = AtomicLong(0)
    private var restartScan = false
    private var selectedTab = 6 // Show all real hits first; A may legitimately be empty.
    private var isScanning = false
    private var latestScanDate = ""
    private var latestScanAt = 0L

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
    private var radarEnabled:Set<RadarType> = com.rex.twboardingscanner.backtest.defaultActiveRadars()
    private var dConfig:DPatternConfig=DPatternConfig.DEFAULT
    private var scanExclusions:Map<RadarType,Set<String>> = com.rex.twboardingscanner.domain.ExclusionConditions.empty()
    private var eConfig:com.rex.twboardingscanner.domain.EPatternConfig=com.rex.twboardingscanner.domain.EPatternConfig.DEFAULT
    private var bConfig:com.rex.twboardingscanner.domain.BPatternConfig=com.rex.twboardingscanner.domain.BPatternConfig.DEFAULT
    private var fConfig:com.rex.twboardingscanner.domain.FPatternConfig=com.rex.twboardingscanner.domain.FPatternConfig.DEFAULT
    private val radarSwitches=mutableMapOf<RadarType,com.google.android.material.button.MaterialButton>()
    private fun refreshRadarSwitches(){
        radarSwitches.forEach{(type,button)->
            val on=type in radarEnabled
            NeonUi.radarSwitch(button,type,on)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.background=NeonUi.backdrop(this)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(b.root) { view,insets ->
            val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16)+bars.left,dp(12)+bars.top,dp(16)+bars.right,dp(6)+bars.bottom)
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

        b.marketGuardButton.setOnClickListener { showMarketGuard() }
        b.settingsButton.setOnClickListener { showSettings() }
        b.robotButton.setOnClickListener { startActivity(android.content.Intent(this,BtRobotActivity::class.java)) }
        b.resultFilters.onSelected = { index ->
            selectedTab=index
            render()
            b.recycler.scrollToPosition(0)
        }

        b.conditionAButton.setOnClickListener { showRadarConditions(RadarType.A_EARLY_BREAKOUT) }
        b.conditionBButton.setOnClickListener { showRadarConditions(RadarType.B_DEEP_REVERSAL) }
        b.conditionCButton.setOnClickListener { showRadarConditions(RadarType.C_LONG_RED_VOLUME) }
        b.conditionDButton.setOnClickListener { showRadarConditions(RadarType.D_MAIN_FORCE) }


        val originalRow=b.conditionAButton.parent as ViewGroup
        val editorIndex=b.settingsPanel.indexOfChild(originalRow)
        val editors=listOf(b.conditionAButton,b.conditionBButton,b.conditionCButton,b.conditionDButton,
            NeonUi.button(this,"E DIF金叉"){showRadarConditions(RadarType.E_DIF_TOUCH)}.apply{tag="scanner-e-conditions"},
            NeonUi.button(this,"F 縮量上漲"){showRadarConditions(RadarType.F_VOLUME_CONTRACTION)}.apply{tag="scanner-f-conditions"})
        editors.forEachIndexed{i,button->
            (button.parent as? ViewGroup)?.removeView(button)
            button.text="${RadarType.entries[i].label}\n設定條件"
            NeonUi.categoryStyle(button,RadarType.entries[i])
            button.minHeight=dp(56);button.textSize=13f
            button.icon=RadarGlyph(RadarType.entries[i]);button.iconSize=dp(20)
            button.iconTint=android.content.res.ColorStateList.valueOf(NeonUi.radarColor(RadarType.entries[i]))
        }
        b.settingsPanel.removeView(originalRow)
        val extraConditionRow=NeonUi.vertical(this).apply{
            addView(NeonUi.label(this@MainActivity,"② 設定條件 · 點擊編輯",15f,NeonUi.ink,true))
            editors.chunked(2).forEach{addView(NeonUi.row(this@MainActivity,it))}
        }
        b.settingsPanel.addView(extraConditionRow,editorIndex)
        // The six on/off buttons control computation, without deleting checked rules.
        val switchRow=NeonUi.vertical(this).apply{
            tag="scanner-radar-toggles"
            addView(NeonUi.label(this@MainActivity,"① 啟用類別 · 彩色已啟用／灰色已停用",12f,NeonUi.cyan,true))
            addView(NeonUi.radarRows(this@MainActivity,RadarType.entries.map{type->
                NeonUi.button(this@MainActivity,"",NeonUi.cyan){
                    radarEnabled=if(type in radarEnabled)radarEnabled-type else radarEnabled+type
                    saveScanSettings();refreshRadarSwitches();requestConfiguredScan()
                }.apply{tag="scanner-enabled-${type.name}";textSize=11f}.also{radarSwitches[type]=it}
            }))
        }
        b.settingsPanel.addView(switchRow,b.settingsPanel.indexOfChild(extraConditionRow))
        refreshRadarSwitches()
        b.scanConditionButton.setOnClickListener { showScanConditionsDialog() }
        posterExport=PosterExport(this)
        b.refreshButton.text="導出 ABCDEF 海報"
        b.refreshButton.setOnClickListener {
            val snapshot=admitted()
            posterExport.generate(isScanning,ScanPoster.collect(snapshot),b.scanProgress.progress,b.scanProgress.max)
        }
        b.logButton.text="掃描日誌資料夾"
        b.logButton.setOnClickListener { startActivity(android.content.Intent(this,ScanArchiveActivity::class.java)) }
        b.searchButton.setOnClickListener { StockToolsActivity.open(this,"SEARCH") }
        b.exitRulesButton.setOnClickListener {
            val store=com.rex.twboardingscanner.data.ExitRuleStore(this)
            ExitRulesEditor.show(this,"主篩查器",store.read(com.rex.twboardingscanner.domain.StockScope.SCANNER)){rules->
                store.save(com.rex.twboardingscanner.domain.StockScope.SCANNER,rules)
                b.exitRulesButton.text="下車賣出條件 · ${rules.count{it.active}} / 7 項"
            }
        }
        b.exitRulesButton.text="下車賣出條件 · ${com.rex.twboardingscanner.data.ExitRuleStore(this).read(com.rex.twboardingscanner.domain.StockScope.SCANNER).count{it.active}} / 7 項"
        trendHoldCheck=CheckBox(this).apply{
            tag="scanner-profit-trend-hold";textSize=13f;setTextColor(NeonUi.cyan)
            text="淨利達標 RSI(14)>50 且日K未跌破5MA 續抱；日K收盤跌破5MA才賣出"
            isChecked=com.rex.twboardingscanner.data.ExitRuleStore(this@MainActivity).readProfitTrendHold(StockScope.SCANNER)
            setOnCheckedChangeListener{_,enabled->
                runCatching{com.rex.twboardingscanner.data.ExitRuleStore(this@MainActivity).saveProfitTrendHold(StockScope.SCANNER,enabled)}
                    .onFailure{Toast.makeText(this@MainActivity,"規則保存失敗：${it.message}",Toast.LENGTH_LONG).show()}
            }
        }
        val exitSettingsRow=b.exitRulesButton.parent as android.view.View
        b.settingsPanel.addView(trendHoldCheck,b.settingsPanel.indexOfChild(exitSettingsRow)+1)
        b.banButton.setOnClickListener { StockToolsActivity.open(this,"BAN") }
        b.limitButton.setOnClickListener { StockToolsActivity.open(this,"LIMIT") }
        b.versionText.text="v${packageManager.getPackageInfo(packageName,0).versionName} · 作者：雷允澤"
        b.changelogButton.setOnClickListener {
            val text=NeonUi.label(this,assets.open("optimization-log.txt").bufferedReader().use{it.readText()},13f,NeonUi.ink).apply{setPadding(dp(16),dp(12),dp(16),dp(12))}
            MaterialAlertDialogBuilder(this).setTitle("優化日誌").setView(ScrollView(this).apply{addView(text)}).setPositiveButton("關閉",null).show()
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

    private fun refreshMarketGuard(force:Boolean=false){
        val source=com.rex.twboardingscanner.data.MarketIndexData(this)
        b.marketGuardButton.text=source.cached().title+"  ›"
        if(!guardBusy.compareAndSet(false,true))return
        guardExecutor.submit{val state=runCatching{source.live(force)}.getOrElse{com.rex.twboardingscanner.domain.MarketCrashGuard.unknown("大盤更新失敗，暫停買入")}
            guardBusy.set(false)
            runOnUiThread{if(!isFinishing&&!isDestroyed){b.marketGuardButton.text=state.title+"  ›";b.marketGuardButton.setTextColor(if(state.canBuy)NeonUi.cyan else NeonUi.amber)}}
        }
    }
    private fun showMarketGuard(){
        val state=com.rex.twboardingscanner.data.MarketIndexData(this).cached()
        MaterialAlertDialogBuilder(this).setTitle(state.title).setMessage(state.detail()+"\n\n"+com.rex.twboardingscanner.domain.MarketCrashGuard.SUMMARY+"\n\n以證交所收盤資料判斷；掃描結果保留觀察，模擬交易每次買入前檢查。")
            .setNegativeButton("關閉",null).setPositiveButton("重新檢查大盤"){_,_->refreshMarketGuard(true)}.show()
    }
    private fun showSettings() {
        val panel=b.settingsPanel
        (panel.parent as? ViewGroup)?.removeView(panel)
        panel.visibility=android.view.View.VISIBLE
        val body=NeonUi.vertical(this).apply{background=NeonUi.backdrop(this@MainActivity)}
        body.addView(NeonUi.label(this,"條件與工具",23f,NeonUi.ink,true).apply{setPadding(dp(16),dp(12),dp(16),dp(4))})
        body.addView(NeonUi.label(this,"入場、下車與股票管理集中設定",12f).apply{setPadding(dp(16),0,dp(16),dp(8))})
        body.addView(NeonUi.button(this,"匯入內／外盤成交量 CSV（主動買賣 ≥1.5 倍）",NeonUi.cyan){
            startActivity(android.content.Intent(this,TradeSideVolumeImportActivity::class.java))
        }.apply{tag="scan-import-trade-side"})
        body.addView(ScrollView(this).apply{addView(panel)},LinearLayout.LayoutParams(-1,0,1f))
        val dialog=com.google.android.material.bottomsheet.BottomSheetDialog(this)
        body.addView(NeonUi.button(this,"完成設定"){dialog.dismiss()}.apply{NeonUi.primaryStyle(this)},LinearLayout.LayoutParams(-1,-2).apply{setMargins(dp(16),dp(8),dp(16),dp(16))})
        dialog.setContentView(body)
        body.layoutParams.height=(resources.displayMetrics.heightPixels*.85).toInt()
        dialog.setOnDismissListener {
            (panel.parent as? ViewGroup)?.removeView(panel);panel.visibility=android.view.View.GONE;b.root.addView(panel)
        }
        dialog.show()
        dialog.behavior.state=com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
    }

    override fun onResume() { super.onResume();guardHandler.post(guardPoll);loadScanSettings();if(::trendHoldCheck.isInitialized)trendHoldCheck.isChecked=com.rex.twboardingscanner.data.ExitRuleStore(this).readProfitTrendHold(StockScope.SCANNER);b.exitRulesButton.text="下車賣出條件 · ${com.rex.twboardingscanner.data.ExitRuleStore(this).read(com.rex.twboardingscanner.domain.StockScope.SCANNER).count{it.active}} / 7 項";if(::paperLoop.isInitialized){paperLoop.start();render();if(latest.isNotEmpty())coordinator.submit{runCatching{publishLists()}}} }
    override fun onPause() { guardHandler.removeCallbacks(guardPoll);if(::paperLoop.isInitialized)paperLoop.stop();super.onPause() }

    private fun startFullScan() {
        if (isScanning) {
            Toast.makeText(this, "掃描進行中", Toast.LENGTH_SHORT).show()
            return
        }

        if(radarEnabled.isEmpty()) {
            latest=emptyList();latestPaper=emptyList();render()
            b.scanProgress.isIndeterminate=false;b.scanProgress.progress=0
            b.progressCount.text="未執行掃描";b.etaText.text="請選擇條件"
            b.progressTitle.text="未啟用任何入場條件"
            Toast.makeText(this,"請至少啟用 A～F 中的一個條件",Toast.LENGTH_LONG).show()
            return
        }
        val revision=scanRevision.get()
        fun current()=revision==scanRevision.get()
        fun finishCancelled() { runOnUiThread {
            if(isFinishing||isDestroyed)return@runOnUiThread
            isScanning=false
            b.startScanButton.isEnabled=true;b.startScanButton.text="開始掃描"
            if(restartScan){restartScan=false;startFullScan()}
        } }
        // Each new scan starts on the consolidated results view, not an empty A tab.
        selectedTab=6
        val scanDate = java.time.LocalDate.now(com.rex.twboardingscanner.domain.RuleMetrics.TAIPEI)
        latestScanDate=scanDate.toString()
        latestScanAt=System.currentTimeMillis()
        val scanRules = radarSelections.mapValues { it.value.toSet() }
        val scanEnabled=radarEnabled.toSet()
        val scanDConfig=dConfig
        val scanEConfig=eConfig
        val scanBConfig=bConfig
        val scanFConfig=fConfig
        val exclusionSnapshot=scanExclusions.mapValues{it.value.toSet()}
        val flowStore=com.rex.twboardingscanner.data.TradeSideVolumeStore(this)
        val flowRevision=flowStore.revision()
        val scanPolicy=com.rex.twboardingscanner.data.StockPolicyStore(this).read()
        val mainSectors = enabledSectors.toSet()
        // Paper mode owns independent ABC/sectors. Fetch the union so paper-only
        // candidates are not accidentally excluded by the main scanner view.
        val paperBook=runCatching{com.rex.twboardingscanner.paper.PaperRepository(this).read()}.getOrNull()
        val participation=com.rex.twboardingscanner.domain.ScanParticipation(scanEnabled,mainSectors,
            paperBook?.enabled==true,paperBook?.strategy)
        val paperSettings=participation.paperSettings
        val scanSectors = mainSectors+(paperSettings?.sectors?:emptySet())
        isScanning = true
        b.startScanButton.isEnabled=false;b.startScanButton.text="掃描中…"
        latest = emptyList()
        latestPaper=emptyList()
        b.scanProgress.progress = 0
        b.scanProgress.max = 0
        render()
        b.scanProgress.isIndeterminate = true
        b.progressTitle.text = "準備全市場資料…"
        b.progressCount.text = "正在取得上市櫃清單"
        b.etaText.text = "計算預估時間中"
        b.progressStats.text = "已分析 0｜排除 0｜資料不足 0｜失敗 0"

        coordinator.submit {
            if(!current()){finishCancelled();return@submit}
            val guard=runCatching{com.rex.twboardingscanner.data.MarketIndexData(this).live()}.getOrElse{com.rex.twboardingscanner.domain.MarketCrashGuard.unknown("大盤檢查失敗，暫停買入")}
            runOnUiThread{if(!current())return@runOnUiThread;b.marketGuardButton.text=guard.title+"  ›";b.marketGuardButton.setTextColor(if(guard.canBuy)NeonUi.cyan else NeonUi.amber)}
            val indexHistory=if(
                (RadarType.D_MAIN_FORCE in scanEnabled&&DPattern.RELATIVE in scanRules[RadarType.D_MAIN_FORCE].orEmpty())||
                (paperSettings?.enabledRadars?.contains(RadarType.D_MAIN_FORCE)==true&&
                    DPattern.RELATIVE in paperSettings.rules[RadarType.D_MAIN_FORCE].orEmpty())
            )runCatching{
                com.rex.twboardingscanner.data.MarketIndexData(this).load(scanDate.minusDays(160),scanDate)
                    .associate{it.date to it.close}
            }.getOrNull() else null
            val universe = provider.loadUniverse { msg ->
                runOnUiThread { if(current())b.progressTitle.text = msg }
            }

            if(!current()){finishCancelled();return@submit}
            if (universe.isEmpty()) {
                runOnUiThread {
                    if(!current()){finishCancelled();return@runOnUiThread}
                    isScanning = false
                    b.startScanButton.isEnabled=true;b.startScanButton.text="開始掃描"
                    b.scanProgress.isIndeterminate = false
                    b.progressCount.text = "無法取得上市櫃資料，請檢查網路"
                }
                return@submit
            }

            runCatching { com.rex.twboardingscanner.data.StockDirectory(this).remember(universe) }
            runOnUiThread { if(!current())return@runOnUiThread;sectorCounts = universe.groupingBy { it.sector }.eachCount(); refreshSectorCounts() }

            val total = universe.size
            val done = AtomicInteger(0)
            val analyzed = AtomicInteger(0)
            val excluded = AtomicInteger(0)
            val insufficient = AtomicInteger(0)
            val failed = AtomicInteger(0)
            val newCount = AtomicInteger(0)
            val financeQueries=AtomicInteger(0)
            val historyWaitMs=AtomicLong(0L)
            val financeWaitMs=AtomicLong(0L)
            val lastProgressMs=AtomicLong(0L)
            val lastRenderMs=AtomicLong(0L)
            provider.resetHistoryStats()
            val latch = CountDownLatch(total)
            val results = Collections.synchronizedList(mutableListOf<SignalResult>())
            val enrichment=com.rex.twboardingscanner.data.ScanEnrichmentQueue(enrichmentWorkers)
            val pricesDone=java.util.concurrent.atomic.AtomicBoolean(false)
            val started = System.currentTimeMillis()

            runOnUiThread {
                if(!current())return@runOnUiThread
                b.scanProgress.isIndeterminate = false
                b.scanProgress.max = total
                b.scanProgress.progress = 0
                val coverage=provider.universeCoverage
                b.progressTitle.text = if(coverage.complete)"全市場逐檔掃描中 · ${scanEnabled.joinToString("/"){it.name.take(1)}}${if(paperSettings!=null)"（模擬交易另行分析）" else ""}" else "部分市場資料缺漏（${coverage.description}）"
            }

            universe.forEach { stock ->
                workers.submit stockTask@{
                    try {
                        if(!current())return@stockTask
                        val userExcluded =
                            stock.sector !in scanSectors || stock.code in scanPolicy.banned

                        if (userExcluded) {
                            excluded.incrementAndGet()
                        } else {
                            val historyBegin=android.os.SystemClock.elapsedRealtime()
                            val bars = runCatching{provider.loadHistory(stock)}.getOrDefault(emptyList())
                            historyWaitMs.addAndGet(android.os.SystemClock.elapsedRealtime()-historyBegin)
                            if (bars.isEmpty()) {
                                failed.incrementAndGet()
                            } else if (bars.size < 20) {
                                insufficient.incrementAndGet()
                            } else {
                                run {
                                    analyzed.incrementAndGet()
                                    fun evaluate(stockToUse: com.rex.twboardingscanner.domain.MarketStock) = participation.evaluate(stockToUse.sector) { type ->
                                        if(!current())return@evaluate null
                                        val datedBars = calculator.barsForRadar(bars, type, scanDate)
                                        if (datedBars.isEmpty()) null else {
                                            val snap = calculator.build(stockToUse, datedBars)
                                                .copy(dConfig=scanDConfig,eConfig=scanEConfig,fConfig=scanFConfig,bConfig=scanBConfig,marketIndexCloses=indexHistory)
                                            when(type) {
                                                RadarType.A_EARLY_BREAKOUT -> engine.evaluateA(snap, scanRules.getValue(type),exclusionSnapshot.getValue(type))
                                                RadarType.B_DEEP_REVERSAL -> engine.evaluateB(snap, scanRules.getValue(type),exclusionSnapshot.getValue(type))
                                                RadarType.C_LONG_RED_VOLUME -> engine.evaluateC(snap, scanRules.getValue(type),exclusionSnapshot.getValue(type))
                                                RadarType.D_MAIN_FORCE -> engine.evaluateD(snap,scanRules.getValue(type),exclusionSnapshot.getValue(type))
                                                RadarType.F_VOLUME_CONTRACTION -> engine.evaluateF(snap,scanRules.getValue(type),exclusionSnapshot.getValue(type))
                                                RadarType.E_DIF_TOUCH -> engine.evaluateE(snap,scanRules.getValue(type),exclusionSnapshot.getValue(type))
                                            }
                                        }
                                    }
                                    val withFlow=stock.copy(tradeSideVolume=flowStore.read(stock.code,scanDate))
                                    val initial = evaluate(withFlow)
                                    // Never request three HiStock pages for technical-only conditions.
                                    // Paper robot/strategy checklists are independent of the main selected IDs.
                                    val neededTopics=buildSet<String>{
                                        initial.forEach{result->
                                            val mainIds=if(result.radarType in scanEnabled&&result.sector in mainSectors)
                                                scanRules[result.radarType].orEmpty() else emptySet()
                                            addAll(com.rex.twboardingscanner.data.FinancialDataProvider.neededTopics(
                                                result,mainIds,exclusionSnapshot[result.radarType].orEmpty()))
                                            val paperIds=if(result.radarType in (paperSettings?.enabledRadars?:emptySet())&&result.sector in paperSettings!!.sectors)
                                                paperSettings.rules[result.radarType].orEmpty() else emptySet()
                                            addAll(com.rex.twboardingscanner.data.FinancialDataProvider.neededTopics(
                                                result,paperIds,paperSettings?.exclusions?.get(result.radarType).orEmpty()))
                                        }
                                    }
                                    // Publish technical evidence immediately; financial gates stay PENDING.
                                    results.addAll(initial)
                                    val needsAuction=participation.mainResults(initial).any{it.light!=SignalLight.NONE}&&
                                        java.time.LocalTime.now(com.rex.twboardingscanner.domain.RuleMetrics.TAIPEI)>=java.time.LocalTime.of(13,33)
                                    if(neededTopics.isNotEmpty()||needsAuction)enrichment.submit followUp@{
                                        if(!current())return@followUp
                                        try {
                                            val report=if(neededTopics.isNotEmpty()){
                                                financeQueries.incrementAndGet()
                                                val begin=android.os.SystemClock.elapsedRealtime()
                                                try{financialProvider.load(stock.code,neededTopics)}
                                                finally{financeWaitMs.addAndGet(android.os.SystemClock.elapsedRealtime()-begin)}
                                            }else null
                                            val enriched=if(report==null)withFlow else withFlow.copy(
                                                financials=report,quarterlyEps=report.eps,epsTtm=report.ttm(scanDate))
                                            val evaluated=if(report==null)initial else evaluate(enriched)
                                            val positive=participation.mainResults(evaluated).any{it.light!=SignalLight.NONE}
                                            val finalResults=if(positive&&java.time.LocalTime.now(com.rex.twboardingscanner.domain.RuleMetrics.TAIPEI)>=java.time.LocalTime.of(13,33))
                                                evaluate(enriched.copy(closingAuction=auctionProvider.load(stock.code,stock.market))) else evaluated
                                            if(!current())return@followUp
                                            synchronized(results){results.removeAll{it.code==stock.code};results.addAll(finalResults)}
                                        }catch(_:Exception){failed.incrementAndGet()}
                                        finally {
                                            val remaining=(enrichment.pending-1).coerceAtLeast(0)
                                            runOnUiThread {
                                                if(current()&&pricesDone.get()){
                                                    b.progressTitle.text="行情掃描完成 · 正在完成財報／收盤查核"
                                                    b.etaText.text="尚待查核 $remaining 檔"
                                                    latest=participation.mainResults(synchronized(results){results.toList()});render()
                                                }
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
                        val now=android.os.SystemClock.elapsedRealtime()
                        val last=lastProgressMs.get()
                        if(current()&&(d==total||(now-last>=350L&&lastProgressMs.compareAndSet(last,now)))){
                            updateProgress(
                                d,total,started,analyzed.get(),excluded.get(),insufficient.get(),failed.get(),
                                provider.historyStats(),financeQueries.get(),revision,enrichment.pending
                            )
                        }

                        val rendered=lastRenderMs.get()
                        if(current()&&(d==total||(d%20==0&&now-rendered>=800L&&lastRenderMs.compareAndSet(rendered,now)))){
                            runOnUiThread{
                                if(current()){latest=participation.mainResults(synchronized(results){results.toList()});render()}
                            }
                        }
                        latch.countDown()
                    }
                }
            }

            latch.await()
            pricesDone.set(true)
            runOnUiThread{if(current()&&enrichment.pending>0){
                b.progressTitle.text="行情掃描完成 · 正在完成財報／收盤查核"
                b.etaText.text="尚待查核 ${enrichment.pending} 檔"
            }}
            enrichment.await()
            if(!current()){finishCancelled();return@submit}
            val allResults = synchronized(results) { results.toList() }
            val copy = participation.mainResults(allResults)
            latest = copy
            latestPaper = if(paperSettings==null)copy else participation.paperResults(allResults)
            val elapsedSec=(System.currentTimeMillis()-started).coerceAtLeast(0L)/1000L
            val workerHistorySec=historyWaitMs.get()/1000L
            val workerFinanceSec=financeWaitMs.get()/1000L
            android.util.Log.i("FullMarketScan",
                "processed=$total analyzed=${analyzed.get()} failures=${failed.get()} elapsed=${elapsedSec}s historyWorker=${workerHistorySec}s financialWorker=${workerFinanceSec}s cache=${provider.historyStats().cached} downloaded=${provider.historyStats().downloaded} rateLimited=${provider.historyStats().rateLimited}")
            getSharedPreferences("scanner-performance",MODE_PRIVATE).edit()
                .putLong("elapsedSec",elapsedSec)
                .putLong("historyWorkerSec",workerHistorySec)
                .putLong("financeWorkerSec",workerFinanceSec)
                .putInt("cacheHits",provider.historyStats().cached)
                .putInt("downloads",provider.historyStats().downloaded)
                .putInt("rateLimited",provider.historyStats().rateLimited)
                .putInt("financialChecked",financeQueries.get()).apply()
            // Save an IMMUTABLE folder for this exact scan, including every chosen
            // ABC predicate, selected sector, banned policy and ranked candidates.
            // Do not mix a newly imported flow CSV into an already-running scan.
            runCatching{
                check(flowStore.revision()==flowRevision){
                    "主動買／賣量資料於掃描中變更，請重新開始，避免同一次掃描條件不一致"
                }
                val snapshot=com.rex.twboardingscanner.data.ScanArchiveStore.snapshot(
                    scanDate.toString(),latestScanAt,scanRules,mainSectors,scanPolicy,
                    flowRevision,total,analyzed.get(),failed.get(),
                    enabledRadars=scanEnabled,dConfig=scanDConfig,eConfig=scanEConfig,fConfig=scanFConfig,bConfig=scanBConfig,exclusions=exclusionSnapshot)
                com.rex.twboardingscanner.data.ScanArchiveStore(this).use{store->
                    store.addCompleteScan(scanDate.toString(),latestScanAt,snapshot,copy)
                }
            }.onFailure{failure->runOnUiThread{
                Toast.makeText(this,"此次掃描未能存入獨立資料夾：${failure.message}",Toast.LENGTH_LONG).show()
            }}
            if(!current()){finishCancelled();return@submit}
            copy.filter { it.light != SignalLight.NONE && scanPolicy.decision(it.code,it.snapshot.sourceStock?.close?:it.snapshot.price,StockScope.SCANNER)==com.rex.twboardingscanner.domain.StockDecision.ALLOW }.forEach { sig ->
                if(current() && history.insertIfNew(sig)) {
                    newCount.incrementAndGet()
                    if((sig.light==SignalLight.RED||sig.light==SignalLight.ORANGE)&&com.rex.twboardingscanner.data.MarketIndexData(this).cached().canBuy)notifyNewSignal(sig)
                }
            }
            runCatching { publishLists() }
                .onFailure { runOnUiThread { Toast.makeText(this,"模擬候選清單儲存失敗，請檢查儲存空間",Toast.LENGTH_LONG).show() } }

            runOnUiThread {
                if(!current()){finishCancelled();return@runOnUiThread}
                isScanning = false
                b.startScanButton.isEnabled=true;b.startScanButton.text="開始掃描"
                b.scanProgress.progress = total
                val metrics=provider.historyStats()
                val coverage=provider.universeCoverage
                b.progressTitle.text = when{
                    !coverage.complete->"部分市場掃描完畢（${coverage.description}）"
                    metrics.rateLimited>0||failed.get()>0->"掃描結束 · 部分行情待重試"
                    else->"全市場掃描完成"
                }
                b.progressCount.text = "已處理 ${total} / ${total} 檔（100%）"
                b.etaText.text = if(metrics.rateLimited>0)"行情限流 · 耗時 ${elapsedSec/60}分${elapsedSec%60}秒" else "完成 · 耗時 ${elapsedSec/60}分${elapsedSec%60}秒"
                if(!coverage.complete)Toast.makeText(this,"上市／上櫃資料來源不完整，本輪僅分析已取得的股票；請稍後重掃。",Toast.LENGTH_LONG).show()
                b.progressStats.text = "已分析 ${analyzed.get()}｜排除 ${excluded.get()}｜資料不足 ${insufficient.get()}｜失敗 ${failed.get()}\n日K快取 ${metrics.cached}｜下載 ${metrics.downloaded}｜來源限流 ${metrics.rateLimited}｜財報查核 ${financeQueries.get()}"
                render()
                handler.removeCallbacksAndMessages(null)

            }
        }
    }

    private fun updateProgress(
        done:Int, total:Int, started:Long,
        analyzed:Int,excluded:Int,insufficient:Int,failed:Int,
        stats:MarketDataProvider.HistoryStats,financial:Int,revision:Long,pending:Int=0
    ) {
        val elapsed = System.currentTimeMillis() - started
        val remain = if (done > 0) elapsed.toDouble() / done * (total - done) else 0.0
        val min = (remain / 60000).toInt()
        val sec = ((remain % 60000) / 1000).toInt()

        runOnUiThread {
            if(revision!=scanRevision.get())return@runOnUiThread
            b.scanProgress.progress = done
            b.progressCount.text = "已處理 ${done} / ${total} 檔（${done * 100 / total}%）"
            b.etaText.text = if (done < 10) "正在估算…" else "行情約 ${min}分${sec}秒" + if(pending>0)" · 另待查核 $pending 檔" else ""
            b.progressStats.text = "已分析 ${analyzed}｜排除 ${excluded}｜資料不足 ${insufficient}｜失敗 ${failed}\n日K快取 ${stats.cached}｜下載 ${stats.downloaded}｜限流 ${stats.rateLimited}｜財報 ${financial}"
        }
    }

    private fun admitted():List<SignalResult>{
        val policy=com.rex.twboardingscanner.data.StockPolicyStore(this).read()
        return latest.filter{it.radarType in radarEnabled && it.sector in enabledSectors &&
            policy.decision(it.code,it.snapshot.sourceStock?.close?:it.snapshot.price,com.rex.twboardingscanner.domain.StockScope.SCANNER)==com.rex.twboardingscanner.domain.StockDecision.ALLOW}
    }
    private fun publishLists(){
        val store=com.rex.twboardingscanner.data.StockPolicyStore(this);val policy=store.read()
        val candidates=latest.filter{it.radarType in radarEnabled&&it.sector in enabledSectors&&it.light!=SignalLight.NONE&&it.checks.filter{c->c.selected}.all{c->c.state==com.rex.twboardingscanner.domain.CheckState.PASS}}
        val date=latestScanDate.ifBlank{java.time.LocalDate.now(com.rex.twboardingscanner.domain.RuleMetrics.TAIPEI).toString()}
        val rows=org.json.JSONArray(candidates.filter{policy.decision(it.code,it.snapshot.sourceStock?.close?:it.snapshot.price,com.rex.twboardingscanner.domain.StockScope.SCANNER)==com.rex.twboardingscanner.domain.StockDecision.LIMITED}.groupBy{it.code}.map{(code,all)->
            val r=all.first();org.json.JSONObject().put("date",date).put("code",code).put("name",r.name).put("price",r.snapshot.sourceStock?.close?:r.snapshot.price).put("radar",all.map{it.radarType.name.take(1)}.distinct().joinToString("/"))
        })
        val guard=com.rex.twboardingscanner.data.MarketIndexData(this).cached()
        val current=org.json.JSONObject().put("marketGuard",guard.json()).put("date",date).put("rows",org.json.JSONArray(admitted().map{r->org.json.JSONObject().put("code",r.code).put("name",r.name).put("radar",r.radarType.name.take(1)).put("price",r.snapshot.sourceStock?.close?:r.snapshot.price).put("state",if(r.light!=SignalLight.NONE&&!guard.canBuy)"僅觀察 · 大盤保護暫停買入" else if(r.light!=SignalLight.NONE)"入選" else if(r.checks.any{it.selected&&it.state==com.rex.twboardingscanner.domain.CheckState.PENDING})"待查核" else "未通過")}))
        val file=java.io.File(filesDir,"scanner-search.json");val temp=java.io.File(file.path+".tmp");temp.writeText(current.toString());check(temp.renameTo(file))
        store.candidates(com.rex.twboardingscanner.domain.StockScope.SCANNER,rows,"主頁掃描 · $date",policy)
        // Publish all evaluated stocks; PaperRepository applies its OWN saved rule set.
        com.rex.twboardingscanner.paper.PaperRepository(this).publish(latestPaper,latestScanAt)
    }
    private fun render() {
        val source=admitted()
        val groups=(0..8).map{ResultFilters.select(source,it)}
        b.resultFilters.update(groups.map{it.size},selectedTab)
        val selected=groups[selectedTab]
        adapter.submit(selected)
        b.emptyState.visibility=if(selected.isEmpty())android.view.View.VISIBLE else android.view.View.GONE
        (b.emptyState.getChildAt(0) as TextView).text=when{
            isScanning->"正在整理市場訊號"
            selectedTab==6&&latestScanAt>0->"本次沒有符合條件的股票"
            selectedTab==3&&RadarType.D_MAIN_FORCE !in radarEnabled->"D 主力進場尚未啟用"
            selectedTab==7&&RadarType.E_DIF_TOUCH !in radarEnabled->"E DIF金叉尚未啟用"
            latestScanAt>0->"此分類尚無入選股票"
            else->"等待市場訊號"
        }
        val available=RadarType.entries.mapIndexedNotNull{i,type->
            groups[if(type==RadarType.F_VOLUME_CONTRACTION)8 else if(type==RadarType.E_DIF_TOUCH)7 else i].size.takeIf{it>0}?.let{"${type.label} $it 筆"}
        }.joinToString("、")
        (b.emptyState.getChildAt(1) as TextView).text=when{
            isScanning->"已完成的股票會陸續出現在「全部入選」；可切換 A/B/C/D/E/F。"
            available.isNotBlank()->"目前已掃出 $available。請點「全部入選」直接查看，或切換有訊號的分類。"
            latestScanAt>0->"目前沒有符合已勾選條件的股票；可在條件與工具中調整。"
            else->"請開始掃描；預設「全部入選」顯示 ABCDEF 所有通過的股票。"
        }
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
        radarEnabled=prefs.getStringSet("enabled_radars",null)?.mapNotNull{
            runCatching{RadarType.valueOf(it)}.getOrNull()
        }?.toSet()?:com.rex.twboardingscanner.backtest.defaultActiveRadars()
        scanExclusions=com.rex.twboardingscanner.domain.ExclusionConditions.read(prefs.getString("scan_exclusions",null)?.let{org.json.JSONObject(it)})
        eConfig=runCatching{com.rex.twboardingscanner.domain.EPatternConfig.read(org.json.JSONObject(prefs.getString("e_pattern_config",null)?:"{}"))}.getOrDefault(com.rex.twboardingscanner.domain.EPatternConfig.DEFAULT)
        bConfig=runCatching{com.rex.twboardingscanner.domain.BPatternConfig.read(prefs.getString("b_pattern_config",null)?.let{org.json.JSONObject(it)})}.getOrDefault(com.rex.twboardingscanner.domain.BPatternConfig.DEFAULT)
        fConfig=runCatching{com.rex.twboardingscanner.domain.FPatternConfig.read(org.json.JSONObject(prefs.getString("f_pattern_config",null)?:"{}"))}.getOrDefault(com.rex.twboardingscanner.domain.FPatternConfig.DEFAULT)
        dConfig=runCatching{DPatternConfig.read(org.json.JSONObject(prefs.getString("d_pattern_config",null)?:"{}"))}
            .getOrDefault(DPatternConfig.DEFAULT)
        refreshRadarSwitches()
        RadarType.entries.forEach { type ->
            val rules = ScanConditions.forEditor(type)
            val savedRules = prefs.getStringSet("rules_${ScanConditions.VERSION}_${type.name}", null)
                ?:prefs.getStringSet("rules_${ScanConditions.PRE_E_VERSION}_${type.name}",null)
                ?:prefs.getStringSet("rules_${ScanConditions.PREVIOUS_VERSION}_${type.name}",null)
                ?:prefs.getStringSet("rules_${ScanConditions.PREVIOUS_D_VERSION}_${type.name}",null)
                ?:prefs.getStringSet("rules_${ScanConditions.PRE_D_VERSION}_${type.name}",null)
                ?:prefs.getStringSet("rules_${ScanConditions.LEGACY_VERSION}_${type.name}",null)
            var chosen=savedRules?.intersect(rules.map{it.id}.toSet())
                ?:rules.filter{it.defaultEnabled}.map{it.id}.toSet()
            // On first upgrade, retain the customer's six original D checks and
            // gently add five useful A guards. Never resurrect an intentionally
            // blank D selection or overwrite a saved D2 selection.
            if(type==RadarType.D_MAIN_FORCE&&savedRules!=null&&savedRules.isNotEmpty()&&
                // Only migrate v0.4.47 or older D settings. D2 users may have
                // intentionally deselected one of the original five guards.
                prefs.getStringSet("rules_${ScanConditions.PRE_E_VERSION}_${type.name}",null)==null&&
                prefs.getStringSet("rules_${ScanConditions.PREVIOUS_VERSION}_${type.name}",null)==null&&
                prefs.getStringSet("rules_${ScanConditions.VERSION}_${type.name}",null)==null&&
                !prefs.getBoolean("d_a_filters_migrated_v048",false)){
                chosen=chosen+ScanConditions.D_A_DEFAULT_IDS
                prefs.edit().putStringSet("rules_${ScanConditions.VERSION}_${type.name}",chosen)
                    .putBoolean("d_a_filters_migrated_v048",true).apply()
            }
            radarSelections[type]=chosen
        }
    }

    private fun saveScanSettings() {
        prefs.edit()
            .putStringSet("enabled_sectors", enabledSectors.map { it.name }.toSet())
            .putStringSet("enabled_radars",radarEnabled.map{it.name}.toSet())
            .putString("d_pattern_config",dConfig.json().toString()).putString("e_pattern_config",eConfig.json().toString()).putString("f_pattern_config",fConfig.json().toString()).putString("b_pattern_config",bConfig.json().toString()).putString("scan_exclusions",com.rex.twboardingscanner.domain.ExclusionConditions.json(scanExclusions).toString())
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
        scanRevision.incrementAndGet()
        latest=emptyList();latestPaper=emptyList();render()
        if(isScanning){
            restartScan=true
            b.progressTitle.text="條件已變更，正在停止舊掃描…"
            Toast.makeText(this,"停止舊掃描後，將套用最新條件重新掃描",Toast.LENGTH_SHORT).show()
        } else Toast.makeText(this,"條件已儲存；請按「開始掃描」套用新條件",Toast.LENGTH_SHORT).show()
    }

    private var financialDialog: FinancialDialog? = null
    private fun showFinancialReport(result: SignalResult) {
        financialDialog?.dismiss()
        financialDialog=FinancialDialog(this,financialProvider,result.code,result.name,result.snapshot.sourceStock?.financials).also { it.show() }
    }

    private fun showRadarConditions(type: RadarType) {
        val title = "${type.label} · 掃描條件"
        val numbers=RuleNumberFields(this,dConfig,fConfig,bConfig)
        val rules = ScanConditions.forEditor(type)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        box.addView(NeonUi.label(this,"勾選的條件須全部符合；未勾選不限制。",13f,NeonUi.ink))
        if(type==RadarType.E_DIF_TOUCH)box.addView(NeonUi.label(this,"要只找剛金叉：勾選『DIF金叉』，並於參數設定開啟『納入剛向上交叉』；仍須符合線距門檻。",12f,NeonUi.amber))
        box.addView(NeonUi.button(this,"查看完整判斷說明",NeonUi.muted){
            MaterialAlertDialogBuilder(this).setTitle("條件判斷說明").setMessage("勾選條件全部滿足才入選；未勾選不限制。B預設為早期起轉組合，其餘基本條件預設全選，額外查核預設關閉；E 預設僅勾 DIF 向上接觸，不強制 A 股價／量能。\nA/B/E/F 排除台灣當日日線；C/D 使用最新日 K。E 的 MACD 為 EMA9 訊號線，預設線距≤收盤價0.3%，DIF須上升且前日位於訊號線下方或相等；可調線距及是否納入剛向上交叉。\nB新增可調深跌／不破底／MACD連升／RSI回升／放量突破及任一反轉事件；早期與突破確認兩套預設可套用。舊已保存勾選不自動改寫；B通過後若RSI>50且收盤>20日線另標示技術升級；不辨識W底形狀。\n額外資料不足標示待查核，勾選後會阻擋入選。5日／10日線碰觸以差距1%內且前一日差距較大定義；主動買賣比需提供來源可靠的內外盤成交量CSV，不能使用五檔委買委賣掛單量。HiStock 財報會查核技術候選股，快取24小時；HTTP403/429、格式變更、資料過期均待查核。逐日法人尚未接入。分數為所選條件通過率，不是公司評級。\n財務條件共用於ABCDEF、可各自勾選；EPS為正不代表現金流為正。產業EPS範圍不是通用及格線，未設成硬門檻。\n現金流使用原站最新列示期間，不跨季加總；金融業現金流需另行解讀。營業利益率為正不能排除一次性業外收益。\n風險估算：前20日最低價為支撐，支撐下1%為失效價，前60日最高價為壓力；(壓力−現價)/(現價−失效價)≥2。\n漲停估算：前收盤×1.1，按台股跳動單位向下取整；非交易所公告漲停價。\nC 為觀察訊號，尚無回測證明隔天一定續漲。\n估算主力成本為近20日的日K典型價（高＋低＋收）／3依成交股數加權；非券商揭露的真正大戶持股均價，也不保證股價反彈。當日資料若未收盤，估值可在收盤後改變；不足20根有效日K則顯示待查核。").setPositiveButton("關閉",null).show()
        })
        if(type==RadarType.D_MAIN_FORCE){
            box.addView(NeonUi.button(this,"D 數值總覽（也可直接填寫下方欄位）"){
                numbers.editD()
            }.apply{tag="scanner-d-parameters"})
        }
        if(type==RadarType.E_DIF_TOUCH)box.addView(NeonUi.button(this,"E 線距門檻與剛交叉設定",NeonUi.pink){
            EPatternEditor.show(this,eConfig){chosen->eConfig=chosen;saveScanSettings();requestConfiguredScan()}
        }.apply{tag="scanner-e-parameters"})
        if(type==RadarType.F_VOLUME_CONTRACTION)box.addView(NeonUi.button(this,"F 縮量上漲 · 天數與門檻",NeonUi.pink){
            numbers.editF()
        }.apply{tag="scanner-f-parameters"})
        var extraHeadingShown = false
        val checks=rules.mapIndexed { index,rule ->
            val heading=when{
                type==RadarType.D_MAIN_FORCE&&index==0->"D 原有六項主力形態"
                type==RadarType.D_MAIN_FORCE&&index==6->"A 準備起漲同步條件（可獨立選擇）"
                type==RadarType.D_MAIN_FORCE&&index==17->"A 額外財報／風險查核（選填）"
                type!=RadarType.D_MAIN_FORCE&&rule.extra&&!extraHeadingShown->"額外查核（選填）"
                else->null
            }
            if(heading!=null){
                if(type!=RadarType.D_MAIN_FORCE)extraHeadingShown=true
                box.addView(TextView(this).apply{
                    text=heading;textSize=17f
                    setTextColor(Color.parseColor("#42BBFF"))
                    setPadding(0,dp(16),0,dp(8))
                })
            }
            val cb = CheckBox(this).apply {
                text = rule.label
                tag="scanner-rule-${type.name}-${rule.id}"
                setTextColor(Color.WHITE)
                isChecked = rule.id in radarSelections.getValue(type)
                minHeight = dp(48)
            }
            box.addView(cb);numbers.add(box,type,rule.id)
            rule.id to cb
        }.toMap()
        val readExclusions=ExclusionEditor.fields(this,box,type,scanExclusions.getValue(type))
        if(type==RadarType.D_MAIN_FORCE)box.addView(NeonUi.button(this,
            "同步 A 已勾選選項至 D（不變更原 D 六項）",NeonUi.cyan){
            val currentA=radarSelections[RadarType.A_EARLY_BREAKOUT].orEmpty()
            ScanConditions.A_RULE_IDS.forEach{id->checks[id]?.isChecked=id in currentA}
            Toast.makeText(this,"已將 A 的勾選帶到 D 編輯畫面；按儲存才套用",Toast.LENGTH_SHORT).show()
        }.apply{tag="scanner-d-copy-a"})
        if(type==RadarType.B_DEEP_REVERSAL){val presets=NeonUi.vertical(this);BReversalPresets.add(this,presets){ids->checks.forEach{(id,check)->check.isChecked=id in ids}};box.addView(presets,0)}
        val scroll = ScrollView(this).apply { addView(box) }
        val selectionView=if(type==RadarType.B_DEEP_REVERSAL)NeonUi.vertical(this).apply{
            addView(scroll,LinearLayout.LayoutParams(-1,(resources.displayMetrics.heightPixels*.58f).toInt()))
        } else scroll
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title).setView(selectionView)
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
                    val configs=runCatching{Triple(numbers.readD(),numbers.readF(),numbers.readB())}.getOrElse{Toast.makeText(this,it.message,Toast.LENGTH_LONG).show();return@setOnClickListener}
                    dConfig=configs.first;fConfig=configs.second;bConfig=configs.third
                    scanExclusions=scanExclusions+mapOf(type to readExclusions());saveScanSettings()
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
            adapter = HistoryAdapter { row -> ChipKLauncher.open(this@MainActivity,row.code,row.name) }.apply { submit(rows) }
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
            .setContentText("${r.radarType.label}｜條件通過 ${r.score}%｜${r.reasons.take(2).joinToString("+")}")
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
        restartScan=false;scanRevision.incrementAndGet()
        chartDialog?.dismiss()
        chartDialog = null
        handler.removeCallbacksAndMessages(null)
        guardHandler.removeCallbacksAndMessages(null);guardExecutor.shutdownNow()
        coordinator.shutdownNow()
        workers.shutdownNow()
        enrichmentWorkers.shutdownNow()
        financialDialog?.dismiss()
        posterExport.close()
        history.close()
        super.onDestroy()
    }
}
