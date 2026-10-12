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
    private val guardExecutor=Executors.newSingleThreadExecutor()
    private val guardHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private val guardBusy=java.util.concurrent.atomic.AtomicBoolean(false)
    private val guardPoll=object:Runnable{override fun run(){refreshMarketGuard();guardHandler.postDelayed(this,30000)}}
    private val workers = Executors.newFixedThreadPool(6)

    private var latest = listOf<SignalResult>()
    private var selectedTab = 0
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
    private val radarSwitches=mutableMapOf<RadarType,com.google.android.material.button.MaterialButton>()
    private fun refreshRadarSwitches(){
        radarSwitches.forEach{(type,button)->
            val on=type in radarEnabled
            button.text="${type.name.take(1)} ${if(on)"● 分析" else "○ 停用"}"
            NeonUi.selected(button,on)
            button.contentDescription="${type.name.take(1)} 區${if(on)"已啟用，參與篩選" else "已停用，不參與篩選"}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
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
        b.resultFilters.onSelected = { index -> selectedTab = index; render() }

        b.conditionAButton.setOnClickListener { showRadarConditions(RadarType.A_EARLY_BREAKOUT) }
        b.conditionBButton.setOnClickListener { showRadarConditions(RadarType.B_DEEP_REVERSAL) }
        b.conditionCButton.setOnClickListener { showRadarConditions(RadarType.C_LONG_RED_VOLUME) }
        b.conditionDButton.setOnClickListener { showRadarConditions(RadarType.D_MAIN_FORCE) }
        // The four on/off buttons control computation, without deleting checked rules.
        val switchRow=NeonUi.vertical(this).apply{
            tag="scanner-radar-toggles"
            addView(NeonUi.label(this@MainActivity,"ABCD 參與分析：亮起才會跑該區條件",12f,NeonUi.cyan,true))
            addView(NeonUi.row(this@MainActivity,RadarType.entries.map{type->
                NeonUi.button(this@MainActivity,"",NeonUi.cyan){
                    radarEnabled=if(type in radarEnabled)radarEnabled-type else radarEnabled+type
                    saveScanSettings();refreshRadarSwitches();requestConfiguredScan()
                }.apply{tag="scanner-enabled-${type.name}";textSize=11f}.also{radarSwitches[type]=it}
            }))
        }
        val conditionRow=b.conditionAButton.parent as android.view.View
        b.settingsPanel.addView(switchRow,b.settingsPanel.indexOfChild(conditionRow)+1)
        refreshRadarSwitches()
        b.scanConditionButton.setOnClickListener { showScanConditionsDialog() }
        posterExport=PosterExport(this)
        b.refreshButton.text="導出 ABCD 海報"
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
                b.exitRulesButton.text="下車賣出條件 · ${rules.count{it.active}} / 5 組"
            }
        }
        b.exitRulesButton.text="下車賣出條件 · ${com.rex.twboardingscanner.data.ExitRuleStore(this).read(com.rex.twboardingscanner.domain.StockScope.SCANNER).count{it.active}} / 5 組"
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
        val body=NeonUi.vertical(this)
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

    override fun onResume() { super.onResume();guardHandler.post(guardPoll);loadScanSettings();if(::trendHoldCheck.isInitialized)trendHoldCheck.isChecked=com.rex.twboardingscanner.data.ExitRuleStore(this).readProfitTrendHold(StockScope.SCANNER);b.exitRulesButton.text="下車賣出條件 · ${com.rex.twboardingscanner.data.ExitRuleStore(this).read(com.rex.twboardingscanner.domain.StockScope.SCANNER).count{it.active}} / 5 組";if(::paperLoop.isInitialized){paperLoop.start();render();if(latest.isNotEmpty())coordinator.submit{runCatching{publishLists()}}} }
    override fun onPause() { guardHandler.removeCallbacks(guardPoll);if(::paperLoop.isInitialized)paperLoop.stop();super.onPause() }

    private fun startFullScan() {
        if (isScanning) {
            Toast.makeText(this, "掃描進行中", Toast.LENGTH_SHORT).show()
            return
        }

        val scanDate = java.time.LocalDate.now(com.rex.twboardingscanner.domain.RuleMetrics.TAIPEI)
        latestScanDate=scanDate.toString()
        latestScanAt=System.currentTimeMillis()
        val scanRules = radarSelections.mapValues { it.value.toSet() }
        val scanEnabled=radarEnabled.toSet()
        val scanDConfig=dConfig
        val flowStore=com.rex.twboardingscanner.data.TradeSideVolumeStore(this)
        val flowRevision=flowStore.revision()
        val scanPolicy=com.rex.twboardingscanner.data.StockPolicyStore(this).read()
        val mainSectors = enabledSectors.toSet()
        // Paper mode owns independent ABC/sectors. Fetch the union so paper-only
        // candidates are not accidentally excluded by the main scanner view.
        val paperSettings=runCatching{com.rex.twboardingscanner.paper.PaperRepository(this).read().strategy}.getOrNull()
        val scanSectors = mainSectors+(paperSettings?.sectors?:emptySet())
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
            val guard=runCatching{com.rex.twboardingscanner.data.MarketIndexData(this).live()}.getOrElse{com.rex.twboardingscanner.domain.MarketCrashGuard.unknown("大盤檢查失敗，暫停買入")}
            runOnUiThread{b.marketGuardButton.text=guard.title+"  ›";b.marketGuardButton.setTextColor(if(guard.canBuy)NeonUi.cyan else NeonUi.amber)}
            val indexHistory=if(
                (RadarType.D_MAIN_FORCE in scanEnabled&&DPattern.RELATIVE in scanRules[RadarType.D_MAIN_FORCE].orEmpty())||
                (paperSettings?.enabledRadars?.contains(RadarType.D_MAIN_FORCE)==true&&
                    DPattern.RELATIVE in paperSettings.rules[RadarType.D_MAIN_FORCE].orEmpty())
            )runCatching{
                com.rex.twboardingscanner.data.MarketIndexData(this).load(scanDate.minusDays(160),scanDate)
                    .associate{it.date to it.close}
            }.getOrNull() else null
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

            runCatching { com.rex.twboardingscanner.data.StockDirectory(this).remember(universe) }
            runOnUiThread { sectorCounts = universe.groupingBy { it.sector }.eachCount(); refreshSectorCounts() }

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
                                    fun evaluate(stockToUse: com.rex.twboardingscanner.domain.MarketStock) = RadarType.entries.mapNotNull { type ->
                                        if(type !in scanEnabled&&type !in (paperSettings?.enabledRadars?:emptySet()))return@mapNotNull null
                                        val datedBars = calculator.barsForRadar(bars, type, scanDate)
                                        if (datedBars.isEmpty()) null else {
                                            val snap = calculator.build(stockToUse, datedBars)
                                                .copy(dConfig=scanDConfig,marketIndexCloses=indexHistory)
                                            when(type) {
                                                RadarType.A_EARLY_BREAKOUT -> engine.evaluateA(snap, scanRules.getValue(type))
                                                RadarType.B_DEEP_REVERSAL -> engine.evaluateB(snap, scanRules.getValue(type))
                                                RadarType.C_LONG_RED_VOLUME -> engine.evaluateC(snap, scanRules.getValue(type))
                                                RadarType.D_MAIN_FORCE -> engine.evaluateD(snap,scanRules.getValue(type))
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
                                            val mainReady=mainIds.isNotEmpty()&&result.checks
                                                .filter{it.id in mainIds&&!it.extra}
                                                .all{it.state==com.rex.twboardingscanner.domain.CheckState.PASS}
                                            if(mainReady)addAll(com.rex.twboardingscanner.data.FinancialDataProvider.topicsForRuleIds(mainIds))
                                            val paperIds=if(result.radarType in (paperSettings?.enabledRadars?:emptySet())&&result.sector in paperSettings!!.sectors)
                                                paperSettings.rules[result.radarType].orEmpty() else emptySet()
                                            val paperReady=paperIds.isNotEmpty()&&paperIds
                                                .filter{id->com.rex.twboardingscanner.domain.ScanConditions.forRadar(result.radarType).any{x->x.id==id&&!x.extra}}
                                                .all{id->result.checks.any{it.id==id&&it.state==com.rex.twboardingscanner.domain.CheckState.PASS}}
                                            if(paperReady)addAll(com.rex.twboardingscanner.data.FinancialDataProvider.topicsForRuleIds(paperIds))
                                        }
                                    }
                                    val report=if(neededTopics.isNotEmpty()){
                                        financeQueries.incrementAndGet()
                                        val begin=android.os.SystemClock.elapsedRealtime()
                                        try{financialProvider.load(stock.code,neededTopics)}
                                        finally{financeWaitMs.addAndGet(android.os.SystemClock.elapsedRealtime()-begin)}
                                    }else null
                                    val enriched=if(report==null)withFlow else withFlow.copy(
                                        financials=report,quarterlyEps=report.eps,epsTtm=report.ttm(scanDate))
                                    val evaluated=if(report==null)initial else evaluate(enriched)
                                    // A 13:30 auction cannot exist at 10:00. Avoid a pointless
                                    // remote MIS/time-sales request during regular trading.
                                    val positive=evaluated.any{it.light!=SignalLight.NONE&&it.sector in mainSectors}
                                    val r=if(positive&&java.time.LocalTime.now(com.rex.twboardingscanner.domain.RuleMetrics.TAIPEI)>=java.time.LocalTime.of(13,33)){
                                        evaluate(enriched.copy(closingAuction=auctionProvider.load(stock.code,stock.market)))
                                    }else evaluated
                                    results.addAll(r)

                                    r.filter { it.sector in mainSectors && it.light != SignalLight.NONE && scanPolicy.decision(it.code,it.snapshot.sourceStock?.close?:it.snapshot.price,com.rex.twboardingscanner.domain.StockScope.SCANNER)==com.rex.twboardingscanner.domain.StockDecision.ALLOW }.forEach { sig ->
                                        if (history.insertIfNew(sig)) {
                                            newCount.incrementAndGet()
                                            if ((sig.light == SignalLight.RED || sig.light == SignalLight.ORANGE)&&com.rex.twboardingscanner.data.MarketIndexData(this).cached().canBuy) {
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
                        val now=android.os.SystemClock.elapsedRealtime()
                        val last=lastProgressMs.get()
                        if(d==total||(now-last>=350L&&lastProgressMs.compareAndSet(last,now))){
                            updateProgress(
                                d,total,started,analyzed.get(),excluded.get(),insufficient.get(),failed.get(),
                                provider.historyStats(),financeQueries.get()
                            )
                        }

                        val rendered=lastRenderMs.get()
                        if(d==total||(d%20==0&&now-rendered>=800L&&lastRenderMs.compareAndSet(rendered,now))){
                            val copy=synchronized(results){results.toList()}
                            runOnUiThread{
                                latest=copy
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
                    enabledRadars=scanEnabled,dConfig=scanDConfig)
                com.rex.twboardingscanner.data.ScanArchiveStore(this).use{store->
                    store.addCompleteScan(scanDate.toString(),latestScanAt,snapshot,copy)
                }
            }.onFailure{failure->runOnUiThread{
                Toast.makeText(this,"此次掃描未能存入獨立資料夾：${failure.message}",Toast.LENGTH_LONG).show()
            }}
            runCatching { publishLists() }
                .onFailure { runOnUiThread { Toast.makeText(this,"模擬候選清單儲存失敗，請檢查儲存空間",Toast.LENGTH_LONG).show() } }

            runOnUiThread {
                isScanning = false
                b.startScanButton.isEnabled=true;b.startScanButton.text="開始掃描"
                b.scanProgress.progress = total
                val metrics=provider.historyStats()
                b.progressTitle.text = if(metrics.rateLimited>0||failed.get()>0)"掃描結束 · 部分行情待重試" else "全市場掃描完成"
                b.progressCount.text = "已處理 ${total} / ${total} 檔（100%）"
                b.etaText.text = if(metrics.rateLimited>0)"行情來源限流 · 耗時 ${elapsedSec/60}分${elapsedSec%60}秒" else "完成 · 耗時 ${elapsedSec/60}分${elapsedSec%60}秒"
                b.progressStats.text = "已分析 ${analyzed.get()}｜排除 ${excluded.get()}｜資料不足 ${insufficient.get()}｜失敗 ${failed.get()}\n日K快取 ${metrics.cached}｜下載 ${metrics.downloaded}｜來源限流 ${metrics.rateLimited}｜財報查核 ${financeQueries.get()}"
                render()
                handler.removeCallbacksAndMessages(null)

            }
        }
    }

    private fun updateProgress(
        done:Int, total:Int, started:Long,
        analyzed:Int,excluded:Int,insufficient:Int,failed:Int,
        stats:MarketDataProvider.HistoryStats,financial:Int
    ) {
        val elapsed = System.currentTimeMillis() - started
        val remain = if (done > 0) elapsed.toDouble() / done * (total - done) else 0.0
        val min = (remain / 60000).toInt()
        val sec = ((remain % 60000) / 1000).toInt()

        runOnUiThread {
            b.scanProgress.progress = done
            b.progressCount.text = "已處理 ${done} / ${total} 檔（${done * 100 / total}%）"
            b.etaText.text = if (done < 10) "正在估算…" else "剩餘約 ${min}分${sec}秒"
            b.progressStats.text = "已分析 ${analyzed}｜排除 ${excluded}｜資料不足 ${insufficient}｜失敗 ${failed}\n日K快取 ${stats.cached}｜下載 ${stats.downloaded}｜限流 ${stats.rateLimited}｜財報 ${financial}"
        }
    }

    private fun admitted():List<SignalResult>{
        val policy=com.rex.twboardingscanner.data.StockPolicyStore(this).read()
        return latest.filter{it.sector in enabledSectors &&
            policy.decision(it.code,it.snapshot.sourceStock?.close?:it.snapshot.price,com.rex.twboardingscanner.domain.StockScope.SCANNER)==com.rex.twboardingscanner.domain.StockDecision.ALLOW}
    }
    private fun publishLists(){
        val store=com.rex.twboardingscanner.data.StockPolicyStore(this);val policy=store.read()
        val candidates=latest.filter{it.sector in enabledSectors&&it.light!=SignalLight.NONE&&it.checks.filter{c->c.selected}.all{c->c.state==com.rex.twboardingscanner.domain.CheckState.PASS}}
        val date=latestScanDate.ifBlank{java.time.LocalDate.now(com.rex.twboardingscanner.domain.RuleMetrics.TAIPEI).toString()}
        val rows=org.json.JSONArray(candidates.filter{policy.decision(it.code,it.snapshot.sourceStock?.close?:it.snapshot.price,com.rex.twboardingscanner.domain.StockScope.SCANNER)==com.rex.twboardingscanner.domain.StockDecision.LIMITED}.groupBy{it.code}.map{(code,all)->
            val r=all.first();org.json.JSONObject().put("date",date).put("code",code).put("name",r.name).put("price",r.snapshot.sourceStock?.close?:r.snapshot.price).put("radar",all.map{it.radarType.name.take(1)}.distinct().joinToString("/"))
        })
        val guard=com.rex.twboardingscanner.data.MarketIndexData(this).cached()
        val current=org.json.JSONObject().put("marketGuard",guard.json()).put("date",date).put("rows",org.json.JSONArray(admitted().map{r->org.json.JSONObject().put("code",r.code).put("name",r.name).put("radar",r.radarType.name.take(1)).put("price",r.snapshot.sourceStock?.close?:r.snapshot.price).put("state",if(r.light!=SignalLight.NONE&&!guard.canBuy)"僅觀察 · 大盤保護暫停買入" else if(r.light!=SignalLight.NONE)"入選" else if(r.checks.any{it.selected&&it.state==com.rex.twboardingscanner.domain.CheckState.PENDING})"待查核" else "未通過")}))
        val file=java.io.File(filesDir,"scanner-search.json");val temp=java.io.File(file.path+".tmp");temp.writeText(current.toString());check(temp.renameTo(file))
        store.candidates(com.rex.twboardingscanner.domain.StockScope.SCANNER,rows,"主頁掃描 · $date",policy)
        // Publish all evaluated stocks; PaperRepository applies its OWN saved rule set.
        com.rex.twboardingscanner.paper.PaperRepository(this).publish(latest,latestScanAt)
    }
    private fun render() {
        val groups = (0..5).map { ResultFilters.select(admitted(), it) }
        b.resultFilters.update(groups.map { it.size }, selectedTab)
        adapter.submit(groups[selectedTab])
        b.emptyState.visibility=if(groups[selectedTab].isEmpty())android.view.View.VISIBLE else android.view.View.GONE
        (b.emptyState.getChildAt(0) as TextView).text=if(isScanning)"正在整理市場訊號" else if(latestScanAt>0)"此分類暫無結果" else "等待市場訊號"
        (b.emptyState.getChildAt(1) as TextView).text=if(isScanning)"掃描結果會陸續顯示，請稍候。" else if(latestScanAt>0)"可切換上方分類，或調整條件重新掃描。" else "設定掃描條件，再開始全市場掃描。\n結果會依 A／B／C 分類呈現。"
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
        dConfig=runCatching{DPatternConfig.read(org.json.JSONObject(prefs.getString("d_pattern_config",null)?:"{}"))}
            .getOrDefault(DPatternConfig.DEFAULT)
        refreshRadarSwitches()
        RadarType.entries.forEach { type ->
            val rules = ScanConditions.forRadar(type)
            val savedRules = prefs.getStringSet("rules_${ScanConditions.VERSION}_${type.name}", null)
                ?:prefs.getStringSet("rules_${ScanConditions.PREVIOUS_VERSION}_${type.name}",null)
                ?:prefs.getStringSet("rules_${ScanConditions.LEGACY_VERSION}_${type.name}",null)
            radarSelections[type] = savedRules?.intersect(rules.map { it.id }.toSet())
                ?: rules.filter { it.defaultEnabled }.map { it.id }.toSet()
        }
    }

    private fun saveScanSettings() {
        prefs.edit()
            .putStringSet("enabled_sectors", enabledSectors.map { it.name }.toSet())
            .putStringSet("enabled_radars",radarEnabled.map{it.name}.toSet())
            .putString("d_pattern_config",dConfig.json().toString())
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
            RadarType.D_MAIN_FORCE -> "D｜主力進場六大條件"
        }
        val rules = ScanConditions.forRadar(type)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        box.addView(TextView(this).apply {
            text = "勾選條件全部滿足才入選；未勾選不限制。基本條件預設全選，額外查核預設關閉。\nA/B 排除台灣當日日線；C 使用最新日 K。\nB 通過後若 RSI>50 且收盤>20日線，另標示技術升級；不辨識 W 底形狀。\n額外資料不足標示待查核，勾選後會阻擋入選。5日／10日線碰觸以差距1%內且前一日差距較大定義；主動買賣比需提供來源可靠的內外盤成交量CSV，不能使用五檔委買委賣掛單量。HiStock 財報會查核技術候選股，快取24小時；HTTP403/429、格式變更、資料過期均待查核。逐日法人尚未接入。分數為所選條件通過率，不是公司評級。\n財務條件共用於ABC、可各自勾選；EPS為正不代表現金流為正。產業EPS範圍不是通用及格線，未設成硬門檻。\n現金流使用原站最新列示期間，不跨季加總；金融業現金流需另行解讀。營業利益率為正不能排除一次性業外收益。\n風險估算：前20日最低價為支撐，支撐下1%為失效價，前60日最高價為壓力；(壓力−現價)/(現價−失效價)≥2。\n漲停估算：前收盤×1.1，按台股跳動單位向下取整；非交易所公告漲停價。\nC 為觀察訊號，尚無回測證明隔天一定續漲。"
            setTextColor(Color.parseColor("#9FBAD0"))
        })
        if(type==RadarType.D_MAIN_FORCE){
            box.addView(NeonUi.button(this,"D 數值門檻 · 20日／1次漲停／4根陽線／3日2倍"){
                DPatternEditor.show(this,dConfig){chosen->
                    dConfig=chosen;saveScanSettings();requestConfiguredScan()
                }
            }.apply{tag="scanner-d-parameters"})
        }
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
        guardHandler.removeCallbacksAndMessages(null);guardExecutor.shutdownNow()
        coordinator.shutdownNow()
        workers.shutdownNow()
        financialDialog?.dismiss()
        posterExport.close()
        history.close()
        super.onDestroy()
    }
}
