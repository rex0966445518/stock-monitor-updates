package com.rex.twboardingscanner.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.rex.twboardingscanner.data.HistoryRow
import com.rex.twboardingscanner.data.MarketDataProvider
import com.rex.twboardingscanner.data.SignalHistoryDb
import com.rex.twboardingscanner.databinding.ActivityMainBinding
import com.rex.twboardingscanner.domain.*
import java.text.SimpleDateFormat
import java.util.*
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
    private var sectorCounts: Map<StockSector,Int> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater); setContentView(b.root)
        provider = MarketDataProvider(this); history = SignalHistoryDb(this)
        createNotificationChannel(); askNotificationPermission(); loadIndustryFilter()
        b.recycler.layoutManager = LinearLayoutManager(this); b.recycler.adapter = adapter
        listOf("今日新觸發", "A｜起漲", "B｜深跌", "C｜長紅爆量", "等待區", "已失效").forEach { b.tabLayout.addTab(b.tabLayout.newTab().setText(it)) }
        b.tabLayout.addOnTabSelectedListener(object: TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) { selectedTab=tab?.position?:0; render() }
            override fun onTabUnselected(tab: TabLayout.Tab?){}; override fun onTabReselected(tab: TabLayout.Tab?){}
        })
        b.industryFilterButton.setOnClickListener { showIndustryFilterDialog() }
        b.refreshButton.setOnClickListener { startFullScan() }
        b.logButton.setOnClickListener { showLogDatePicker() }
        b.searchButton.setOnClickListener { searchHistory() }
        buildSectorChips()
        startFullScan()
    }

    private fun startFullScan() {
        if (isScanning) { Toast.makeText(this,"掃描進行中",Toast.LENGTH_SHORT).show(); return }
        isScanning=true; b.scanProgress.isIndeterminate=true; b.progressCount.text="準備全市場資料…"; b.etaText.text="計算預估時間中"
        coordinator.submit {
            val universe = provider.loadUniverse { msg -> runOnUiThread { b.progressTitle.text=msg } }
            if (universe.isEmpty()) {
                runOnUiThread { isScanning=false; b.scanProgress.isIndeterminate=false; b.progressCount.text="無法取得上市櫃資料，請檢查網路" }
                return@submit
            }
            sectorCounts = universe.groupingBy { it.sector }.eachCount()
            runOnUiThread { buildSectorChips() }
            val selected = universe.filter { it.sector in enabledSectors && it.volumeLots >= 500 }
            if (selected.isEmpty()) { runOnUiThread { isScanning=false; b.progressCount.text="目前篩選條件沒有可掃描股票" }; return@submit }
            val total=selected.size; val done=AtomicInteger(0); val newCount=AtomicInteger(0); val latch=CountDownLatch(total)
            val results=Collections.synchronizedList(mutableListOf<SignalResult>())
            val started=System.currentTimeMillis()
            runOnUiThread { b.scanProgress.isIndeterminate=false; b.scanProgress.max=total; b.scanProgress.progress=0; b.progressTitle.text="全市場掃描中" }
            selected.forEach { stock ->
                workers.submit {
                    try {
                        val bars=provider.loadHistory(stock)
                        val snap=calculator.build(stock,bars)
                        val r=listOf(engine.evaluateA(snap),engine.evaluateB(snap),engine.evaluateC(snap))
                        results.addAll(r)
                        r.filter{it.light!=SignalLight.NONE}.forEach { sig ->
                            if(history.insertIfNew(sig)) { newCount.incrementAndGet(); if(sig.light==SignalLight.RED || sig.light==SignalLight.ORANGE) notifyNewSignal(sig) }
                        }
                    } finally {
                        val d=done.incrementAndGet(); updateProgress(d,total,started)
                        if(d%20==0 || d==total){
                            val copy=synchronized(results){results.toList()}; runOnUiThread { latest=copy; render() }
                        }
                        latch.countDown()
                    }
                }
            }
            latch.await()
            val copy=synchronized(results){results.toList()}; latest=copy
            runOnUiThread {
                isScanning=false; b.scanProgress.progress=total; b.progressTitle.text="掃描完成"
                b.progressCount.text="已掃描 $total / $total 檔"; b.etaText.text="本輪新觸發 ${newCount.get()} 檔"
                updateSummary(universe.size, selected.size, copy, newCount.get()); render()
                handler.postDelayed({ startFullScan() },15*60*1000L)
            }
        }
    }

    private fun updateProgress(done:Int,total:Int,started:Long){
        val elapsed=System.currentTimeMillis()-started
        val remain=if(done>0) elapsed.toDouble()/done*(total-done) else 0.0
        val min=(remain/60000).toInt(); val sec=((remain%60000)/1000).toInt()
        runOnUiThread {
            b.scanProgress.progress=done; b.progressCount.text="已掃描 $done / $total 檔（${done*100/total}%）"
            b.etaText.text=if(done<5) "正在估算…" else "預估剩餘 ${min}分${sec}秒"
        }
    }

    private fun updateSummary(universe:Int,selected:Int,all:List<SignalResult>,newTriggers:Int){
        val a=all.count{it.radarType==RadarType.A_EARLY_BREAKOUT && it.light!=SignalLight.NONE}
        val bb=all.count{it.radarType==RadarType.B_DEEP_REVERSAL && it.light!=SignalLight.NONE}
        val c=all.count{it.radarType==RadarType.C_LONG_RED_VOLUME && it.light!=SignalLight.NONE}
        b.statusText.text="全市場 $universe 檔｜本次掃描 $selected 檔｜A $a｜B $bb｜C $c｜新觸發 $newTriggers"
    }

    private fun render(){
        val filtered=when(selectedTab){
            1->latest.filter{it.radarType==RadarType.A_EARLY_BREAKOUT && it.light!=SignalLight.NONE}
            2->latest.filter{it.radarType==RadarType.B_DEEP_REVERSAL && it.light!=SignalLight.NONE}
            3->latest.filter{it.radarType==RadarType.C_LONG_RED_VOLUME && it.light!=SignalLight.NONE}
            4->latest.filter{it.score in 65..84}
            5->latest.filter{it.light==SignalLight.WEAKENING}
            else->latest.filter{it.light==SignalLight.RED || it.light==SignalLight.ORANGE}
        }.distinctBy{"${it.code}_${it.radarType}"}.sortedByDescending{it.score}
        adapter.submit(filtered)
    }

    private fun buildSectorChips(){
        b.sectorChipGroup.removeAllViews()
        StockSector.entries.forEach { sector ->
            val chip=Chip(this).apply {
                isCheckable=true; isChecked=sector in enabledSectors
                text="${sector.label} ${sectorCounts[sector] ?: ""}".trim(); setTextColor(android.graphics.Color.WHITE)
                setOnCheckedChangeListener { _,checked ->
                    if(checked) enabledSectors.add(sector) else if(enabledSectors.size>1) enabledSectors.remove(sector) else isChecked=true
                    saveIndustryFilter(); updateIndustryFilterButton()
                }
            }
            b.sectorChipGroup.addView(chip)
        }
        updateIndustryFilterButton()
    }

    private fun loadIndustryFilter(){
        val saved=prefs.getStringSet("enabled_sectors",null)
        enabledSectors=if(saved==null) StockSector.entries.toMutableSet() else saved.mapNotNull{runCatching{StockSector.valueOf(it)}.getOrNull()}.toMutableSet()
        if(enabledSectors.isEmpty()) enabledSectors=StockSector.entries.toMutableSet()
    }
    private fun saveIndustryFilter(){prefs.edit().putStringSet("enabled_sectors",enabledSectors.map{it.name}.toSet()).apply()}
    private fun updateIndustryFilterButton(){b.industryFilterButton.text="產業篩選 ${enabledSectors.size}/${StockSector.entries.size}"}

    private fun showIndustryFilterDialog(){
        val sectors=StockSector.entries; val labels=sectors.map{"${it.label} (${sectorCounts[it]?:0})"}.toTypedArray(); val checked=BooleanArray(sectors.size){sectors[it] in enabledSectors}
        MaterialAlertDialogBuilder(this).setTitle("選擇要掃描的產業").setMultiChoiceItems(labels,checked){_,i,v->checked[i]=v}
            .setNeutralButton("全選"){_,_->enabledSectors=sectors.toMutableSet();saveIndustryFilter();buildSectorChips()}
            .setNegativeButton("取消",null).setPositiveButton("套用"){_,_->
                val chosen=sectors.filterIndexed{i,_->checked[i]}.toMutableSet(); if(chosen.isNotEmpty()) enabledSectors=chosen
                saveIndustryFilter();buildSectorChips();startFullScan()
            }.show()
    }

    private fun showLogDatePicker(){
        val cal=Calendar.getInstance(); DatePickerDialog(this,{_,y,m,d->
            val date=String.format(Locale.TAIWAN,"%04d-%02d-%02d",y,m+1,d); showHistoryRows("$date 掃描日誌",history.queryByDate(date))
        },cal.get(Calendar.YEAR),cal.get(Calendar.MONTH),cal.get(Calendar.DAY_OF_MONTH)).show()
    }
    private fun searchHistory(){
        val code=b.searchInput.text?.toString()?.trim().orEmpty(); if(code.isBlank()){Toast.makeText(this,"請輸入股號",Toast.LENGTH_SHORT).show();return}
        showHistoryRows("$code 歷史掃描紀錄",history.queryByCode(code))
    }
    private fun showHistoryRows(title:String,rows:List<HistoryRow>){
        val items=if(rows.isEmpty()) arrayOf("沒有找到紀錄") else rows.map{
            val time=SimpleDateFormat("MM/dd HH:mm",Locale.TAIWAN).format(Date(it.ts)); "$time｜${it.code} ${it.name}｜${it.radar.replace("_"," ")}｜${it.score}%\n${it.reasons}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle(title).setItems(items,null).setPositiveButton("關閉",null).show()
    }

    private fun notifyNewSignal(r:SignalResult){
        val n=NotificationCompat.Builder(this,"signals").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("${r.code} ${r.name} 新觸發").setContentText("${r.radarType.name}｜${r.score}%｜${r.reasons.take(2).joinToString("+")}")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify((r.code+r.radarType.name+r.light.name).hashCode(),n)
    }
    private fun createNotificationChannel(){if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("signals","上車雷達訊號",NotificationManager.IMPORTANCE_HIGH))}
    private fun askNotificationPermission(){if(Build.VERSION.SDK_INT>=33 && ActivityCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.POST_NOTIFICATIONS),100)}
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);coordinator.shutdownNow();workers.shutdownNow();history.close();super.onDestroy()}
}
