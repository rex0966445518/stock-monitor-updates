package com.rex.twboardingscanner.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.rex.twboardingscanner.backtest.BtSettingsCodec
import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.paper.PaperRepository
import com.rex.twboardingscanner.ui.MainActivity
import java.time.LocalDate
import java.time.LocalTime
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the same ABCDEF/financial/paper scan as MainActivity, but owns its own
 * application-scoped data and threads. Switching apps or destroying an Activity
 * no longer shuts down the scan's executor or discards finished results.
 *
 * WorkManager persists the request and retries if Android kills the process.
 * Retried requests keep the original frozen rules; stale requests cannot
 * overwrite a newer batch's output.
 */
class FullMarketScanWorker(context:Context,params:WorkerParameters):Worker(context,params) {
    companion object {
        private const val NOTIFICATION_ID=4330
        private const val CHANNEL="background-market-scan"
        fun start(c:Context,config:org.json.JSONObject):String {
            val id=BackgroundScanState.begin(c,config)
            try {
                val request=OneTimeWorkRequestBuilder<FullMarketScanWorker>()
                    .setInputData(workDataOf("run" to id))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.LINEAR,30,TimeUnit.SECONDS)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
                WorkManager.getInstance(c).enqueueUniqueWork(BackgroundScanState.WORK,ExistingWorkPolicy.REPLACE,request)
            } catch(e:Exception) {
                BackgroundScanState.failure(c,id,"背景掃描排程失敗："+(e.message?:"未知錯誤"))
                throw e
            }
            return id
        }
        fun stop(c:Context) {
            val id=BackgroundScanState.read(c).id
            BackgroundScanState.cancel(c,id)
            WorkManager.getInstance(c).cancelUniqueWork(BackgroundScanState.WORK)
        }
    }

    private fun current(id:String)=!isStopped&&BackgroundScanState.current(applicationContext,id)
    private fun ensureActive(id:String)=check(current(id)){"背景掃描已暫停或取消"}
    private fun manager()=applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun progressNote(done:Int,total:Int,message:String):Notification {
        manager().createNotificationChannel(NotificationChannel(CHANNEL,"全市場背景掃描",NotificationManager.IMPORTANCE_LOW))
        val intent=PendingIntent.getActivity(applicationContext,4331,
            Intent(applicationContext,MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pct=if(total>0)done*100/total else 0
        return NotificationCompat.Builder(applicationContext,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("AI-離職神器 · 背景掃描中")
            .setContentText(if(total>0)"已掃描 "+done+" / "+total+" 檔（"+pct+"%）" else message)
            .setContentIntent(intent).setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(if(total>0)total else 0,done,total<=0)
            .build()
    }

    private fun foreground():ForegroundInfo {
        val notification=progressNote(0,0,"正在取得台股資料")
        return if(Build.VERSION.SDK_INT>=29)
            ForegroundInfo(NOTIFICATION_ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID,notification)
    }

    override fun doWork():Result {
        val id=inputData.getString("run")?:return Result.failure()
        if(!current(id))return Result.success()
        return try {
            try { setForegroundAsync(foreground()).get() }
            catch(e:Exception) {
                // Android 12+ may deny foreground promotion on a resumed worker.
                // Keep the durable WorkManager job; do not discard the batch.
                android.util.Log.w("BackgroundMarketScan","Foreground notification unavailable",e)
            }
            runScan(id)
            Result.success()
        } catch(e:Exception) {
            if(!BackgroundScanState.current(applicationContext,id))return Result.success()
            ScanDiagnostics.failure(applicationContext,e)
            if(isStopped||runAttemptCount<3) {
                BackgroundScanState.report(applicationContext,id,
                    "掃描中斷，系統將自動重試；已完成的掃描資料夾保留",state="RETRY")
                Result.retry()
            } else {
                BackgroundScanState.failure(applicationContext,id,
                    "背景掃描失敗："+e.javaClass.simpleName+" "+(e.message?:"").take(140))
                Result.failure()
            }
        }
    }

    override fun onStopped() {
        super.onStopped()
        inputData.getString("run")?.let{id->
            BackgroundScanState.report(applicationContext,id,
                "Android 暫停背景工作，等待系統自動接續（原掃描條件保留）",state="RETRY")
        }
    }

    private fun runScan(id:String) {
        val c=applicationContext
        ensureActive(id)
        val config=BackgroundScanState.settings(c,id)
        val date=LocalDate.parse(config.getString("date"))
        val created=config.getLong("created")
        val enabled=config.getJSONArray("enabledRadars").let{a->
            (0 until a.length()).map{RadarType.valueOf(a.getString(it))}.toSet()
        }
        require(enabled.isNotEmpty()){"沒有啟用任何 ABCDEF 入場條件"}
        val rules=config.getJSONObject("rules").let{obj->
            RadarType.entries.associateWith{type->
                obj.optJSONArray(type.name)?.let{a->(0 until a.length()).map{a.getString(it)}.toSet()}?:emptySet()
            }
        }
        val sectors=config.getJSONArray("sectors").let{a->
            (0 until a.length()).map{StockSector.valueOf(a.getString(it))}.toSet()
        }
        val policy=StockPolicy.read(config.getJSONObject("policy"))
        val exclusions=ExclusionConditions.read(config.optJSONObject("exclusions"))
        val dConfig=DPatternConfig.read(config.optJSONObject("dConfig"))
        val eConfig=EPatternConfig.read(config.optJSONObject("eConfig"))
        val fConfig=FPatternConfig.read(config.optJSONObject("fConfig"))
        val bConfig=BPatternConfig.read(config.optJSONObject("bConfig"))
        val flowRevision=config.getString("flowRevision")
        val paperSettings=if(config.optBoolean("paperEnabled")&&!config.isNull("paperStrategy"))
            BtSettingsCodec.decode(config.getJSONObject("paperStrategy")) else null
        val participation=ScanParticipation(enabled,sectors,paperSettings!=null,paperSettings)
        val allSectors=sectors+(paperSettings?.sectors?:emptySet())
        val provider=MarketDataProvider(c)
        val financial=FinancialDataProvider(c)
        val auction=ClosingAuctionProvider()
        val flows=TradeSideVolumeStore(c)
        val calc=TechnicalCalculator()
        val scoring=ScoringEngine()
        val pool=Executors.newFixedThreadPool(6)
        val financialPool=Executors.newSingleThreadExecutor()
        val started=System.currentTimeMillis()
        try {
            ScanDiagnostics.phase(c,"全市場背景掃描開始 "+enabled.joinToString{it.name.take(1)})
            BackgroundScanState.report(c,id,"讀取全市場股票清單",done=0,total=0)
            runCatching{MarketIndexData(c).live()}
            val indexHistory=if(
                (RadarType.D_MAIN_FORCE in enabled&&DPattern.RELATIVE in rules[RadarType.D_MAIN_FORCE].orEmpty())||
                (paperSettings?.enabledRadars?.contains(RadarType.D_MAIN_FORCE)==true&&
                    DPattern.RELATIVE in paperSettings.rules[RadarType.D_MAIN_FORCE].orEmpty())
            )runCatching{MarketIndexData(c).load(date.minusDays(160),date).associate{it.date to it.close}}.getOrNull()
            else null
            val universe=provider.loadUniverse{message->
                if(current(id))BackgroundScanState.report(c,id,message)
            }
            ensureActive(id)
            require(universe.isNotEmpty()){"無法取得上市櫃股票資料，請檢查網路"}
            runCatching{StockDirectory(c).remember(universe)}
            val total=universe.size
            val done=AtomicInteger()
            val analyzed=AtomicInteger()
            val excluded=AtomicInteger()
            val insufficient=AtomicInteger()
            val failed=AtomicInteger()
            val financeQueries=AtomicInteger()
            val results=Collections.synchronizedList(mutableListOf<SignalResult>())
            val enrichment=ScanEnrichmentQueue(financialPool)
            val latch=CountDownLatch(total)
            provider.resetHistoryStats()
            BackgroundScanState.report(c,id,"全市場背景掃描中 · "+enabled.joinToString("/"){it.name.take(1)},
                done=0,total=total)
            universe.forEach{stock->
                pool.execute {
                    try {
                        if(!current(id))return@execute
                        if(stock.sector !in allSectors||stock.code in policy.banned) {
                            excluded.incrementAndGet()
                            return@execute
                        }
                        val bars=runCatching{provider.loadHistory(stock)}.getOrDefault(emptyList())
                        if(bars.isEmpty()){failed.incrementAndGet();return@execute}
                        if(bars.size<20){insufficient.incrementAndGet();return@execute}
                        analyzed.incrementAndGet()
                        fun evaluate(s:MarketStock):List<SignalResult> =
                            participation.evaluate(s.sector){type->
                                if(!current(id))return@evaluate null
                                val dated=calc.barsForRadar(bars,type,date)
                                if(dated.isEmpty())null else {
                                    val snapshot=calc.build(s,dated).copy(
                                        dConfig=dConfig,eConfig=eConfig,fConfig=fConfig,bConfig=bConfig,
                                        marketIndexCloses=indexHistory)
                                    when(type){
                                        RadarType.A_EARLY_BREAKOUT->scoring.evaluateA(snapshot,rules.getValue(type),exclusions.getValue(type))
                                        RadarType.B_DEEP_REVERSAL->scoring.evaluateB(snapshot,rules.getValue(type),exclusions.getValue(type))
                                        RadarType.C_LONG_RED_VOLUME->scoring.evaluateC(snapshot,rules.getValue(type),exclusions.getValue(type))
                                        RadarType.D_MAIN_FORCE->scoring.evaluateD(snapshot,rules.getValue(type),exclusions.getValue(type))
                                        RadarType.E_DIF_TOUCH->scoring.evaluateE(snapshot,rules.getValue(type),exclusions.getValue(type))
                                        RadarType.F_VOLUME_CONTRACTION->scoring.evaluateF(snapshot,rules.getValue(type),exclusions.getValue(type))
                                    }
                                }
                            }
                        val withFlow=stock.copy(tradeSideVolume=flows.read(stock.code,date))
                        val first=evaluate(withFlow)
                        val topics=buildSet<String>{
                            first.forEach{signal->
                                val mainIds=if(signal.radarType in enabled&&signal.sector in sectors)
                                    rules[signal.radarType].orEmpty() else emptySet()
                                addAll(FinancialDataProvider.neededTopics(signal,mainIds,exclusions[signal.radarType].orEmpty()))
                                val paperIds=if(paperSettings!=null&&signal.radarType in paperSettings.enabledRadars&&signal.sector in paperSettings.sectors)
                                    paperSettings.rules[signal.radarType].orEmpty() else emptySet()
                                addAll(FinancialDataProvider.neededTopics(signal,paperIds,
                                    paperSettings?.exclusions?.get(signal.radarType).orEmpty()))
                            }
                        }
                        results.addAll(first)
                        val needsAuction=participation.mainResults(first).any{it.light!=SignalLight.NONE}&&
                            LocalTime.now(RuleMetrics.TAIPEI)>=LocalTime.of(13,33)
                        if(topics.isNotEmpty()||needsAuction)enrichment.submit {
                            if(!current(id))return@submit
                            try {
                                val report=if(topics.isNotEmpty()){
                                    financeQueries.incrementAndGet()
                                    financial.load(stock.code,topics)
                                }else null
                                val enriched=if(report==null)withFlow else withFlow.copy(
                                    financials=report,quarterlyEps=report.eps,epsTtm=report.ttm(date))
                                val recalculated=if(report==null)first else evaluate(enriched)
                                val positive=participation.mainResults(recalculated).any{it.light!=SignalLight.NONE}
                                val final=if(positive&&LocalTime.now(RuleMetrics.TAIPEI)>=LocalTime.of(13,33))
                                    evaluate(enriched.copy(closingAuction=auction.load(stock.code,stock.market)))
                                else recalculated
                                if(current(id))synchronized(results){
                                    results.removeAll{it.code==stock.code}
                                    results.addAll(final)
                                }
                            }catch(e:Exception){
                                if(current(id)){failed.incrementAndGet();android.util.Log.w("BackgroundMarketScan","Financial enrichment",e)}
                            }
                        }
                    }catch(e:Exception){
                        if(current(id)){failed.incrementAndGet();android.util.Log.w("BackgroundMarketScan","Stock calculation",e)}
                    }finally{done.incrementAndGet();latch.countDown()}
                }
            }
            var lastNote=0L
            while(!latch.await(800,TimeUnit.MILLISECONDS)){
                ensureActive(id)
                val n=done.get()
                val eta=if(n>0)(System.currentTimeMillis()-started)*(total-n)/n/1000 else -1
                BackgroundScanState.report(c,id,"全市場掃描中 · "+(if(eta>=0)"預估還需 "+eta+" 秒" else "正在估算"),
                    done=n,total=total,analyzed=analyzed.get(),excluded=excluded.get(),
                    insufficient=insufficient.get(),failed=failed.get(),pending=enrichment.pending)
                val now=SystemClock.elapsedRealtime()
                if(now-lastNote>=5000){
                    runCatching{manager().notify(NOTIFICATION_ID,progressNote(n,total,"正在掃描"))}
                    lastNote=now
                }
            }
            ensureActive(id)
            while(enrichment.pending>0){
                ensureActive(id)
                BackgroundScanState.report(c,id,"行情已完成 · 財報／收盤查核中",
                    done=total,total=total,analyzed=analyzed.get(),excluded=excluded.get(),
                    insufficient=insufficient.get(),failed=failed.get(),pending=enrichment.pending)
                Thread.sleep(750)
            }
            enrichment.await()
            ensureActive(id)
            check(flows.revision()==flowRevision){"盤中買賣量來源已改變，請重新執行以避免條件混用"}
            val all=synchronized(results){results.toList()}
            val main=participation.mainResults(all)
            val paper=if(paperSettings==null)main else participation.paperResults(all)
            val snapshot=config.put("requested",total).put("analyzed",analyzed.get()).put("failed",failed.get())
            BackgroundScanState.report(c,id,"保存掃描資料夾中…",done=total,total=total,pending=0)
            val folder=ScanArchiveStore(c).use{it.addCompleteScan(date.toString(),created,snapshot,main,paper)}
            ensureActive(id)
            SignalHistoryDb(c).use{history->
                main.filter{it.light!=SignalLight.NONE&&
                    policy.decision(it.code,it.snapshot.sourceStock?.close?:it.snapshot.price,StockScope.SCANNER)==StockDecision.ALLOW}
                    .forEach{signal->
                        if(!current(id))return@forEach
                        if(history.insertIfNew(signal)&&
                            (signal.light==SignalLight.RED||signal.light==SignalLight.ORANGE)&&
                            MarketIndexData(c).cached().canBuy)notifySignal(signal)
                    }
            }
            val limited=main.filter{it.radarType in enabled&&it.sector in sectors&&
                it.light!=SignalLight.NONE&&it.checks.filter{check->check.selected}.all{check->check.state==CheckState.PASS}}
                .filter{policy.decision(it.code,it.snapshot.sourceStock?.close?:it.snapshot.price,StockScope.SCANNER)==StockDecision.LIMITED}
                .groupBy{it.code}.map{(code,groups)->
                    val s=groups.first()
                    org.json.JSONObject().put("date",date.toString()).put("code",code).put("name",s.name)
                        .put("price",s.snapshot.sourceStock?.close?:s.snapshot.price)
                        .put("radar",groups.map{it.radarType.name.take(1)}.distinct().joinToString("/"))
                }
            val guard=MarketIndexData(c).cached()
            val list=org.json.JSONObject().put("marketGuard",guard.json()).put("date",date.toString())
                .put("rows",org.json.JSONArray(main.map{r->
                    org.json.JSONObject().put("code",r.code).put("name",r.name)
                        .put("radar",r.radarType.name.take(1))
                        .put("price",r.snapshot.sourceStock?.close?:r.snapshot.price)
                        .put("state",if(r.light!=SignalLight.NONE&&!guard.canBuy)"僅觀察 · 大盤保護暫停買入"
                            else if(r.light!=SignalLight.NONE)"入選"
                            else if(r.checks.any{it.selected&&it.state==CheckState.PENDING})"待查核" else "未通過")
                }))
            val file=java.io.File(c.filesDir,"scanner-search.json")
            val tmp=java.io.File(file.path+".tmp");tmp.writeText(list.toString())
            check(tmp.renameTo(file)){"無法儲存背景掃描候選清單"}
            StockPolicyStore(c).candidates(StockScope.SCANNER,org.json.JSONArray(limited),"主頁掃描 · "+date,policy)
            PaperRepository(c).publish(paper,created,folder.id)
            val message=if(provider.universeCoverage.complete&&provider.historyStats().rateLimited==0&&failed.get()==0)
                "全市場背景掃描完成，結果已存入掃描資料夾"
            else "背景掃描完成；部分行情待查核，請參閱掃描資料夾"
            BackgroundScanState.complete(c,id,folder.id,message)
            ScanDiagnostics.phase(c,"全市場背景掃描完成，結果已保存")
        }finally{
            pool.shutdownNow()
            financialPool.shutdownNow()
        }
    }

    private fun notifySignal(r:SignalResult) {
        runCatching {
            manager().createNotificationChannel(NotificationChannel("signals","上車雷達訊號",NotificationManager.IMPORTANCE_HIGH))
            val note=NotificationCompat.Builder(applicationContext,"signals")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(r.code+" "+r.name+" 新觸發")
                .setContentText(r.radarType.label+"｜條件通過 "+r.score+"%")
                .setAutoCancel(true).build()
            manager().notify((r.code+r.radarType.name+r.light.name).hashCode(),note)
        }
    }
}