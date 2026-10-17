package com.rex.twboardingscanner.backtest

import com.rex.twboardingscanner.domain.DailyBar
import com.rex.twboardingscanner.domain.RuleMetrics
import java.io.*
import java.nio.ByteBuffer
import java.time.LocalDate

/** Immutable OHLCV on disk; a process-wide LRU bounds live raw buffers to 96 KiB.
 * Never mmap/retain the entire market, or hold thousands of file descriptors.
 */
internal class DiskDailyBars private constructor(private val file:File,override val size:Int):AbstractList<DailyBar>(),java.util.RandomAccess {
    override fun get(index:Int):DailyBar {
        if(index !in 0 until size)throw IndexOutOfBoundsException("$index/$size")
        val block=index/BLOCK_ROWS
        val bytes=synchronized(cache){
            val key=file.absolutePath to block
            cache[key]?:RandomAccessFile(file,"r").use{input->
                input.seek(4L+block*BLOCK_ROWS*ROW_BYTES)
                ByteArray(minOf(BLOCK_ROWS,size-block*BLOCK_ROWS)*ROW_BYTES).also{input.readFully(it);cache[key]=it}
            }
        }
        val b=ByteBuffer.wrap(bytes,(index%BLOCK_ROWS)*ROW_BYTES,ROW_BYTES)
        return DailyBar(b.long,b.double,b.double,b.double,b.double,b.long)
    }
    fun lowerBound(day:LocalDate):Int {
        var low=0;var high=size
        while(low<high){val mid=(low+high) ushr 1;if(RuleMetrics.tradingDate(get(mid).time)<day)low=mid+1 else high=mid}
        return low
    }
    companion object {
        private const val ROW_BYTES=48
        private const val BLOCK_ROWS=256
        private val cache=object:LinkedHashMap<Pair<String,Int>,ByteArray>(8,.75f,true){
            override fun removeEldestEntry(e:MutableMap.MutableEntry<Pair<String,Int>,ByteArray>?)=size>8
        }
        internal fun cachedBytes():Int=synchronized(cache){cache.values.sumOf{it.size}}
        fun clearCache()=synchronized(cache){cache.clear()}
        private fun invalidate(file:File)=synchronized(cache){cache.keys.removeAll{it.first==file.absolutePath}}
        private fun commit(file:File,temp:File,count:Int):DiskDailyBars {
            RandomAccessFile(temp,"rw").use{it.fd.sync()}
            check(temp.renameTo(file)){"歷史日K分段無法保存"};invalidate(file)
            return DiskDailyBars(file,count)
        }
        fun write(file:File,bars:List<DailyBar>):DiskDailyBars {
            file.parentFile?.mkdirs();val temp=File(file.path+".tmp")
            try {
                DataOutputStream(BufferedOutputStream(temp.outputStream())).use{out->
                    out.writeInt(bars.size);bars.forEach{b->out.writeLong(b.time);out.writeDouble(b.open);out.writeDouble(b.high);out.writeDouble(b.low);out.writeDouble(b.close);out.writeLong(b.volumeShares)}
                }
                return commit(file,temp,bars.size)
            } finally {temp.delete()}
        }
        fun readFrom(file:File,input:DataInputStream,count:Int,cancel:()->Boolean):DiskDailyBars {
            require(count in 0..5000);file.parentFile?.mkdirs();val temp=File(file.path+".tmp")
            try {
                DataOutputStream(BufferedOutputStream(temp.outputStream())).use{out->
                    out.writeInt(count);val buffer=ByteArray(BLOCK_ROWS*ROW_BYTES);var left=count*ROW_BYTES
                    while(left>0){check(!cancel()){"已暫停測試"};val n=minOf(left,buffer.size);input.readFully(buffer,0,n);out.write(buffer,0,n);left-=n}
                }
                return commit(file,temp,count)
            } finally {temp.delete()}
        }
    }
}

/** Trading lookups use binary search over disk bars, not another market-sized map. */
internal class DiskBarDateMap<V>(private val bars:DiskDailyBars,start:LocalDate,end:LocalDate,
    previous:Boolean=false,private val value:(Int)->V):AbstractMap<LocalDate,V>() {
    private val from=maxOf(if(previous)1 else 0,bars.lowerBound(start))
    private val until=bars.lowerBound(end.plusDays(1))
    override val entries:Set<Map.Entry<LocalDate,V>> get()=object:AbstractSet<Map.Entry<LocalDate,V>>(){
        override val size:Int get()=(until-from).coerceAtLeast(0)
        override fun iterator():Iterator<Map.Entry<LocalDate,V>> = object:Iterator<Map.Entry<LocalDate,V>> {
            var index=from
            override fun hasNext()=index<until
            override fun next():Map.Entry<LocalDate,V> {
                if(!hasNext())throw NoSuchElementException()
                val i=index++;return java.util.AbstractMap.SimpleImmutableEntry(RuleMetrics.tradingDate(bars[i].time),value(i))
            }
        }
    }
    override fun get(key:LocalDate):V? {
        val i=bars.lowerBound(key)
        return if(i in from until until&&RuleMetrics.tradingDate(bars[i].time)==key)value(i) else null
    }
}
