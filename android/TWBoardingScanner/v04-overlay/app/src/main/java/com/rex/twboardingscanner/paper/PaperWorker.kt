package com.rex.twboardingscanner.paper

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.work.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PaperWorker(context:Context,params:WorkerParameters):Worker(context,params) {
    override fun doWork():Result=try {PaperRepository(applicationContext).tick();Result.success()}catch(_:Exception){Result.failure()}
    companion object {
        fun schedule(context:Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("paper-market-v1",ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PaperWorker>(15,TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
        fun cancel(context:Context){WorkManager.getInstance(context).cancelUniqueWork("paper-market-v1")}
    }
}
/** One shared foreground runner: concurrent screens/workers never duplicate fills. */
class PaperLoop(context:Context,private val onUpdate:(String?)->Unit={}) {
    private val app=context.applicationContext
    private val handler=Handler(Looper.getMainLooper())
    private var running=false
    companion object {private val executor=Executors.newSingleThreadExecutor();private val busy=AtomicBoolean(false)}
    private val task=object:Runnable{override fun run(){
        if(!running)return
        if(busy.compareAndSet(false,true))executor.execute {
            val error=runCatching{val repo=PaperRepository(app);if(repo.read().enabled)repo.tick()}.exceptionOrNull()?.message
            busy.set(false);handler.post{if(running)onUpdate(error)}
        }
        handler.postDelayed(this,30000)
    }}
    fun start(){if(!running){running=true;handler.post(task)}}
    fun stop(){running=false;handler.removeCallbacks(task)}
}
