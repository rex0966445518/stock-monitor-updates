package com.rex.twboardingscanner.backtest

import java.io.File
import java.time.LocalDate
import com.rex.twboardingscanner.domain.TradeSideVolume

/** Disk-backed facts: never keep all stocks' indicator records or evidence strings in the heap. */
internal class BtRobotIndex private constructor(private val series:List<BtSeries>,private val parts:File,
    private val fingerprint:String,private val chunkSize:Int,val days:List<LocalDate>) {
    private var activeSignals:DiskSignals?=null
    fun releasePrepared(){activeSignals?.dispose();activeSignals=null}
    private fun key(i:Int)="$fingerprint:chunk:$chunkSize:$i"
    private fun file(i:Int)=File(parts,"$i.bin.gz")
    private fun checkpointFolder(key:String):File {
        val bytes=java.security.MessageDigest.getInstance("SHA-256")
            .digest(("$fingerprint:$key").toByteArray(Charsets.UTF_8))
        val hash=bytes.take(16).joinToString(""){"%02x".format(it)}
        return File(parts,"prepared-$hash")
    }

    fun clearCheckpoint(key:String){
        checkpointFolder(key).deleteRecursively()
    }

    fun prepare(settings:BtSettings,cancel:()->Boolean={false},progress:(String)->Unit={},
                checkpointKey:String?=null):BtPrepared {
        releasePrepared()
        val folder=checkpointKey?.let{checkpointFolder(it)}
        folder?.mkdirs()
        val builder=DiskSignals.Builder(File(parts,"signals-${java.util.UUID.randomUUID()}"))
        var missing=0
        try{
            series.chunked(chunkSize).forEachIndexed{i,chunk->
                check(!cancel()){"已暫停測試"}
                val saved=folder?.let{File(it,"$i.bin.gz")}
                val fingerprintKey="$fingerprint:prepared:$checkpointKey:$chunkSize:$i"
                val cached=saved?.takeIf{it.isFile}?.let{
                    runCatching{BtRobotPreparedChunk.read(it,fingerprintKey)}.getOrNull()
                }
                val slice=cached?:BtConditionIndex.read(file(i),chunk,key(i)).prepare(settings,cancel)
                    .let{BtRobotPreparedChunk.Slice(it.signals,it.pendingChecks)}
                    .also{computed->
                        check(!cancel()){"已暫停測試"}
                        if(saved!=null)BtRobotPreparedChunk.save(saved,fingerprintKey,computed.signals,computed.missing)
                    }
                missing+=slice.missing
                builder.append(slice.signals)
                progress("分段套用條件 ${minOf((i+1)*chunkSize,series.size)}/${series.size} 檔 · "+
                    if(cached!=null)"恢復已保存分段" else "已完成並保存")
            }
            val signals=builder.finish()
            activeSignals=signals
            return BtPrepared(series,signals,days,missing)
        }catch(error:Throwable){
            builder.abort()
            throw error
        }
    }
    companion object {
        fun open(series:List<BtSeries>,settings:BtSettings,parts:File,fingerprint:String,
            progress:(String)->Unit={},cancel:()->Boolean={false},
            flowFor:(String,LocalDate)->TradeSideVolume?={_,_->null},benchmark:Map<LocalDate,Double>?=null,
            chunkSize:Int=8):BtRobotIndex {
            require(chunkSize in 1..64);parts.mkdirs()
            parts.listFiles()?.filter{it.isDirectory&&it.name.startsWith("signals-")}?.forEach{it.deleteRecursively()}
            val result=BtRobotIndex(series,parts,fingerprint,chunkSize,emptyList())
            val days=sortedSetOf<LocalDate>()
            series.chunked(chunkSize).forEachIndexed { i,chunk->
                check(!cancel()){"已暫停測試"}
                progress("歷史條件 ${i*chunkSize}/${series.size} 檔 · 讀取分段快取")
                val cached=if(result.file(i).exists())runCatching{BtConditionIndex.read(result.file(i),chunk,result.key(i))}.getOrNull() else null
                val part=cached?:BtConditionIndex.build(chunk,settings,progress={message->progress("歷史條件 ${i*chunkSize}/${series.size} 檔 · $message")},cancel=cancel,flowFor=flowFor,benchmark=benchmark).also{
                    check(!cancel()){"已暫停測試"};it.save(result.file(i),result.key(i))
                }
                days.addAll(part.days)
                progress("歷史條件 ${minOf((i+1)*chunkSize,series.size)}/${series.size} 檔 · ${if(cached==null)"已保存" else "沿用快取"}")
                // Only this small part remains reachable; the next stock chunk can reclaim it.
            }
            return BtRobotIndex(series,parts,fingerprint,chunkSize,days.toList())
        }
    }
}
