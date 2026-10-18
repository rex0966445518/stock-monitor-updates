package com.rex.twboardingscanner.backtest

import android.content.Context
import java.security.MessageDigest

/** One shared robot execution engine for foreground Service and WorkManager recovery.
 * Every completed trial and cursor is committed atomically in BtRobotStore.
 * In-progress stock chunks persist independently, so a restart reuses earlier work.
 */
internal object BtRobotRunner {
    enum class Outcome { CONTINUE, DONE, STOP, RETRY, FAILED }
    private data class Runtime(
        val id:String,val data:BacktestData.Loaded,val index:BtRobotIndex,
        val execution:BtExecutionIndex,
        val guards:Map<java.time.LocalDate,com.rex.twboardingscanner.domain.MarketGuardDecision>
    ){ var gridPrepared:BtPrepared?=null }
    private val runLock=Any()
    private var cached:Runtime?=null

    fun runSlice(context:Context,id:String,token:String,owner:String,
                 stopped:()->Boolean,onBest:(Long,Double)->Unit):Outcome = synchronized(runLock){
        val store=BtRobotStore(context)
        val policy=com.rex.twboardingscanner.data.StockPolicyStore(context)
        val frozenSession=store.session(id)
        val frozen=frozenSession?.let{BtSettingsCodec.decode(it.getJSONObject("settings")).stockPolicy}
        val flowSource=com.rex.twboardingscanner.data.TradeSideVolumeStore(context)
        val flowRevision=frozenSession?.optString("flowRevision",flowSource.revision())?:flowSource.revision()
        fun status(message:String,state:String="RUNNING")=store.status(id,token,message,state,owner)
        val stagePattern=Regex("分段套用條件 (\\d+)/(\\d+) 檔")
        val cancellation=RobotCancellation(stopped,{!store.owns(id,token,owner)||policy.read()!=frozen||flowSource.revision()!=flowRevision})
        val cancel={cancellation.check()}
        if(cancel())return@synchronized Outcome.STOP
        val sliceStarted=android.os.SystemClock.elapsedRealtime()
        val expired={RobotWorkPolicy.expired(sliceStarted,android.os.SystemClock.elapsedRealtime())}
        val interrupted={cancel()||expired()}
        try{
            val session=store.session(id)!!
            require(BtRobotCheckpoint.compatible(session)){"回測引擎版本不相容；原紀錄可檢閱，請建立新測試"}
            require(session.optInt("analysisVersion")>=2){"條件快取檢核已更新；請按開始建立新批次，舊績效保留"}
            val base=BtSettingsCodec.decode(session.getJSONObject("settings"));BtRobotSpace.validate(base)
            val sweep=BtRobotSweep.read(session.optJSONObject("sweep"))
            require(base.marketGuardVersion>0){"舊批次未啟用大盤保護；按開始建立新批次，原紀錄保留"}
            var lastProgress=0L
            val progress={msg:String->
                check(!interrupted()){"已暫停測試"}
                val now=System.currentTimeMillis()
                if(now-lastProgress>=500){
                    val slice=stagePattern.find(msg)
                    if(slice!=null)store.stage(id,token,owner,msg,slice.groupValues[1].toInt(),slice.groupValues[2].toInt())
                    else status(msg)
                    lastProgress=now
                }
            }
            if(cached?.id!=id){cached?.index?.releasePrepared();cached=null;DiskDailyBars.clearCache()} // Release the previous session before reading another market snapshot.
            val runtime=cached?.takeIf{it.id==id}?:run{
                val file=store.dataset(id)
                val data=if(file.exists()){
                    progress("讀取此測試的固定歷史資料…")
                    BtRobotDataset.read(file,progress,interrupted,diskBacked=true)
                }else{
                    require(session.optLong("tested")==0L){"原歷史資料已遺失，不能接續比較；請建立新測試"}
                    val loaded=BacktestData(context).load(base,progress,interrupted,barDirectory=java.io.File(file.path+".bars"))
                    check(!cancel()){ "已暫停測試" };BtRobotDataset.write(file,loaded);loaded
                }
                require(data.market.isNotEmpty()){"缺少此批次的加權指數快照，請建立新測試"}
                progress("校驗固定歷史資料…")
                val dataDigest=digest(file,interrupted)
                val expected=session.optString("datasetSha256")
                require(expected.isEmpty()||dataDigest==expected){"資料快照校驗失敗；請建立新測試，避免混用行情"}
                store.coverage(id,token,data.series.size,data.requested,dataDigest,owner)
                val indexFile=store.conditionFile(id)
                val fingerprint=BtConditionIndex.fingerprint(dataDigest,base,flowRevision)
                var lastCode=""
                var samples:Map<java.time.LocalDate,com.rex.twboardingscanner.domain.TradeSideVolume> = emptyMap()
                val index=BtRobotIndex.open(data.series,base,java.io.File(indexFile.path+".parts"),fingerprint,progress,interrupted,
                    flowFor={code,day->
                        if(lastCode!=code){lastCode=code;samples=flowSource.forCode(code,base.start,base.end,completed=true)}
                        samples[day]
                    },benchmark=data.market.associate{it.date to it.close})
                progress("歷史條件已備妥，準備交易測算…")
                Runtime(id,data,index,BacktestEngine.executionIndex(data.series,base.start,base.end),com.rex.twboardingscanner.domain.MarketCrashGuard.forDays(data.market,index.days)).also{cached=it}
            }
            val batchStart=System.currentTimeMillis();var completed=0
            while(!interrupted()&&completed<30&&(completed==0||System.currentTimeMillis()-batchStart<120000)){
                require(context.filesDir.usableSpace>128L*1024*1024){"儲存空間不足 128 MB，請先釋出空間"}
                val candidate=store.next(id)
                if(candidate==null){
                    val end=store.session(id)!!
                    val message=if(BtRobotSingleOption.active(session))"所有留空選項已逐項測試完成；每輪只新增一項條件"
                        else if(sweep==null)"所有 ${base.enabledRadars.sortedBy{it.ordinal}.joinToString(""){it.name.take(1)}} 組合測試完成；最佳收益僅限本次歷史樣本"
                        else "參數範圍 ${sweep.total} 組全部完成；已測最佳 ${end.optString("bestKey").ifBlank{"無"}}，可於結果榜檢閱"
                    status(message,"DONE");runtime.index.releasePrepared();runtime.index.clearCheckpoint("sweep-base");cached=null;DiskDailyBars.clearCache();return@synchronized Outcome.DONE
                }
                val settings=if(sweep==null)BtRobotSingleOption.settings(base,candidate.key,BtRobotSingleOption.baseline(session,candidate.key)) else sweep.settings(base,candidate.key)
                val started=System.currentTimeMillis()
                val seq=store.session(id)!!.optLong("tested")+1
                status(if(BtRobotSingleOption.active(session))"正在逐項測試第 $seq 組 · ${BtRobotSingleOption.label(session.optString("required","0"),candidate.key,base.enabledRadars)}"
                    else if(sweep==null)"正在測試第 $seq 組 · ${candidate.key}"
                    else "正在測試第 $seq／${sweep.total} 組 · ${sweep.parseKey(candidate.key).label}")
                val prepared=if(sweep==null)runtime.index.prepare(settings,interrupted,progress,checkpointKey=candidate.key).copy(marketGuards=runtime.guards)
                    else runtime.gridPrepared?:runtime.index.prepare(base,interrupted,progress,checkpointKey="sweep-base").copy(marketGuards=runtime.guards)
                        .also{runtime.gridPrepared=it}
                val signalCounts=base.enabledRadars.associateWith{0}.toMutableMap()
                prepared.signals.values.forEach{items->items.forEach{signal->base.enabledRadars.forEach{type->
                    if(type.name.take(1) in signal.radar.split("/"))signalCounts[type]=signalCounts.getValue(type)+1
                }}}
                val feedback=signalCounts.entries.joinToString(" · "){"${it.key.name.take(1)} 符合 ${it.value} 筆"}
                val run=BacktestEngine.run(prepared,settings,progress=progress,cancel=interrupted,execution=runtime.execution,skippedDetailLimit=5000)
                check(!cancellation.check(force=true)){ "已暫停測試" }
                val note="省記憶體模式：日K與候選訊號按需讀取；未買明細最多保留5000筆，未買總數與每日統計完整保留，買賣明細完整保留。\n"+"條件掃描回饋：$feedback；待查核 ${prepared.pendingChecks} 項。\n"+NOTE+if(sweep!=null)"本批次固定 ABCDEF 條件，逐一測試持倉 ${sweep.holdingFrom}～${sweep.holdingTo} 檔 × 扣費稅淨利 ${sweep.profitFrom}%～${sweep.profitTo}% 的 ${sweep.total} 組有限範圍；每組使用相同歷史樣本，沒有再搜尋 ABCDEF。" else ""
                val better=store.commit(id,token,candidate,BtResult(settings,run,runtime.data.requested,runtime.data.series.size,runtime.data.excluded,note,prepared.pendingChecks),started,signalCounts,owner)
                if(sweep==null){runtime.index.releasePrepared();if(store.seen(id,candidate.key))runtime.index.clearCheckpoint(candidate.key)}
                if(better)onBest(seq,run.profit)
                completed++
            }
            if(cancel())return@synchronized Outcome.STOP
            // Bounded batches yield to Android scheduling. A stopped batch commits only completed trials.
            Outcome.CONTINUE
        }catch(e:OutOfMemoryError){
            cached?.index?.releasePrepared();cached=null;DiskDailyBars.clearCache()
            status("記憶體不足，本輪未提交；歷史資料與已完成結果保留，可按開始／繼續","ERROR")
            Outcome.FAILED
        }catch(e:Exception){
            if(cancel())return@synchronized Outcome.STOP
            if(RobotWorkPolicy.foregroundDenied(e)){
                status("Android限制背景啟動；進度已保存，請返回機器人頁面按開始／繼續","ERROR")
                return@synchronized Outcome.FAILED
            }
            if(expired()){
                status("本段運算已保存，等待系統安排下一段接續")
                return@synchronized Outcome.CONTINUE
            }
            if(e is IllegalArgumentException){
                status("需處理：${e.message?.take(180)}；完成紀錄保留，可重試","ERROR")
                return@synchronized Outcome.FAILED
            }
            status("中斷：${e.message?.take(120)}；10 秒後自動接續","RETRY")
            Outcome.RETRY
        }
    }

    private fun digest(file:java.io.File,cancel:()->Boolean={false}):String {
        val hash=MessageDigest.getInstance("SHA-256")
        file.inputStream().use{input->
            val b=ByteArray(65536)
            while(true){
                check(!cancel()){"已暫停測試"}
                val n=input.read(b)
                if(n<0)break
                hash.update(b,0,n)
            }
        }
        return hash.digest().joinToString(""){"%02x".format(it)}
    }
}
