package com.rex.twboardingscanner.backtest

import java.io.*
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Day-sized signal lists: even a whole-market empty-filter baseline stays bounded. */
internal class DiskSignals private constructor(private val directory:File,private val days:List<LocalDate>):AbstractMap<LocalDate,List<BtSignal>>() {
    private var cachedDay:LocalDate?=null
    private var cachedSignals:List<BtSignal>?=null
    override val entries:Set<Map.Entry<LocalDate,List<BtSignal>>> get()=object:AbstractSet<Map.Entry<LocalDate,List<BtSignal>>>(){
        override val size:Int get()=days.size
        override fun iterator()=days.asSequence().map{day->java.util.AbstractMap.SimpleImmutableEntry(day,get(day)!!)}.iterator()
    }
    @Synchronized override fun get(key:LocalDate):List<BtSignal>? {
        if(key !in days)return null
        if(cachedDay==key)return cachedSignals
        val result=mutableListOf<BtSignal>()
        DataInputStream(BufferedInputStream(GZIPInputStream(File(directory,"$key.gz").inputStream()))).use{input->
            while(true){
                val n=try{input.readInt()}catch(_:EOFException){break};require(n in 0..10000)
                repeat(n){val code=input.readUTF();val radar=input.readUTF();val epoch=input.readLong();val length=input.readInt();require(length in 0..1000000)
                    val evidence=ByteArray(length);input.readFully(evidence)
                    result+=BtSignal(code,radar,if(epoch==Long.MIN_VALUE)null else LocalDate.ofEpochDay(epoch),evidence.toString(Charsets.UTF_8))
                }
            }
        }
        cachedDay=key;cachedSignals=result;return result
    }
    fun dispose(){cachedSignals=null;cachedDay=null;directory.deleteRecursively()}
    class Builder(private val directory:File) {
        private val days=sortedSetOf<LocalDate>()
        init{check(directory.mkdirs()||directory.isDirectory)}
        fun append(signals:Map<LocalDate,List<BtSignal>>){
            signals.forEach{(day,items)->if(items.isNotEmpty()){
                DataOutputStream(BufferedOutputStream(GZIPOutputStream(FileOutputStream(File(directory,"$day.gz"),true)))).use{out->
                    out.writeInt(items.size);items.forEach{s->out.writeUTF(s.code);out.writeUTF(s.radar);out.writeLong(s.dataDate?.toEpochDay()?:Long.MIN_VALUE)
                        val bytes=s.dEvidence.toByteArray(Charsets.UTF_8);out.writeInt(bytes.size);out.write(bytes)}
                };days+=day
            }}
        }
        fun finish()=DiskSignals(directory,days.toList())
        fun abort(){directory.deleteRecursively()}
    }
}
