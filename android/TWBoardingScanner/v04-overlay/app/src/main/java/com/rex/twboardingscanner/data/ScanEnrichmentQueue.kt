package com.rex.twboardingscanner.data

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/** Producers finish their daily-price tasks before await() drains all follow-up work. */
class ScanEnrichmentQueue(private val executor:ExecutorService) {
    private val futures=ConcurrentLinkedQueue<Future<*>>()
    private val waiting=AtomicInteger()
    val pending:Int get()=waiting.get()
    fun submit(task:()->Unit) {
        waiting.incrementAndGet()
        try { futures.add(executor.submit{try{task()}finally{waiting.decrementAndGet()}}) }
        catch(e:Exception){waiting.decrementAndGet();throw e}
    }
    fun await() {
        while(true){val future=futures.poll()?:return;future.get()}
    }
}
