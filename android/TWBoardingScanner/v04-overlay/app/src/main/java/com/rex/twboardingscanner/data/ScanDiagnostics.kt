package com.rex.twboardingscanner.data

import android.content.Context
import java.io.File
import java.io.PrintWriter

/** Local crash/phase evidence. Copied only when the user chooses the tools action. */
object ScanDiagnostics {
    private val lock=Any()
    private fun file(context:Context)=File(context.filesDir,"main-scan-diagnostics.txt")
    fun phase(context:Context,message:String)=synchronized(lock){
        runCatching{
            val f=file(context)
            if(f.length()>32768)f.writeText("（早期診斷已輪替）\n")
            val runtime=Runtime.getRuntime()
            f.appendText("${java.time.Instant.now()} ${message.take(300)} heap=${(runtime.totalMemory()-runtime.freeMemory())/1048576}/${runtime.maxMemory()/1048576}MB\n")
        };Unit
    }
    fun failure(context:Context,error:Throwable)=synchronized(lock){
        runCatching{PrintWriter(java.io.FileOutputStream(file(context),true).buffered()).use{writer->
            writer.println("${java.time.Instant.now()} ${error.javaClass.name}: ${error.message?.take(300)}")
            if(error !is OutOfMemoryError)error.printStackTrace(writer)
        }};Unit
    }
    fun read(context:Context):String=runCatching{
        file(context).takeIf{it.exists()}?.bufferedReader()?.use{it.readText().takeLast(16000)}
            ?:"尚無掃描錯誤紀錄"
    }.getOrDefault("診斷資料讀取失敗")
    internal class CrashHandler(private val context:Context,val downstream:Thread.UncaughtExceptionHandler?):Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread:Thread,error:Throwable){
            failure(context,error)
            downstream?.uncaughtException(thread,error)
        }
    }
    fun install(context:Context){
        val previous=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context.applicationContext,
            if(previous is CrashHandler)previous.downstream else previous))
    }
}
