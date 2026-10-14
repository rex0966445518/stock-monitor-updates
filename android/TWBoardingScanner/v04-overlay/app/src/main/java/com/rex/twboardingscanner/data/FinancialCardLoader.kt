package com.rex.twboardingscanner.data

import com.rex.twboardingscanner.domain.*
import java.time.LocalDate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Fetch displayed cards only, coalesce duplicate A-F cards, publish cache and each completed topic. */
class FinancialCardLoader(
    private val cached:(String)->FinancialReport?,
    private val load:(String,(FinancialReport)->Unit)->FinancialReport,
    private val update:(String,FinancialReport?,Boolean)->Unit,
    private val executor:ExecutorService=Executors.newSingleThreadExecutor(),
    private val clock:()->Long=System::currentTimeMillis,
    private val cacheExecutor:ExecutorService=Executors.newFixedThreadPool(2)
):AutoCloseable {
    private val pending=mutableSetOf<String>()
    private val checked=mutableMapOf<String,Long>()
    @Volatile private var closed=false
    fun request(code:String,initial:FinancialReport?=null){
        synchronized(this){
            if(closed||code in pending||checked[code]?.let{clock()-it<300_000L}==true)return
            pending+=code
        }
        update(code,initial,true)
        cacheExecutor.submit cacheTask@{
            var current=initial
            try{cached(code)?.let{current=merge(current,it)}}catch(_:Exception){}
            if(closed)return@cacheTask
            if(complete(current,clock())){
                synchronized(this){pending-=code;checked[code]=clock()}
                update(code,current,false);return@cacheTask
            }
            update(code,current,true)
            executor.submit{
                try{
                    val report=load(code){part->current=merge(current,part);if(!closed)update(code,current,true)}
                    current=merge(current,report)
                }catch(e:Exception){
                    current=merge(current,FinancialReport(fetchedAt=clock(),messages=listOf("基本面讀取失敗：${e.message.orEmpty().take(120)}")))
                }finally{
                    synchronized(this){pending-=code;checked[code]=clock()}
                    if(!closed)update(code,current,false)
                }
            }
        }
    }
    override fun close(){closed=true;cacheExecutor.shutdownNow();executor.shutdownNow()}
    companion object {
        private fun complete(report:FinancialReport?,now:Long):Boolean {
            if(report==null||now-report.fetchedAt !in 0 until 86_400_000L)return false
            val today=LocalDate.now(RuleMetrics.TAIPEI)
            return report.ttm(today)!=null&&report.latest(report.operatingCash,today)!=null&&report.latest(report.operatingMargin,today)!=null
        }
        fun merge(old:FinancialReport?,new:FinancialReport)=FinancialReport(
            new.eps.ifEmpty{old?.eps.orEmpty()},new.annualEps.ifEmpty{old?.annualEps.orEmpty()},
            new.operatingCash.ifEmpty{old?.operatingCash.orEmpty()},new.operatingMargin.ifEmpty{old?.operatingMargin.orEmpty()},
            listOfNotNull(old?.fetchedAt?.takeIf{it>0},new.fetchedAt.takeIf{it>0}).minOrNull()?:0L,new.messages)
    }
}
