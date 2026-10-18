package com.rex.twboardingscanner.backtest

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Crash-consistent checkpoint for just one small prepared-stock chunk.
 *
 * Each chunk is written atomically before it is appended to the temporary
 * daily-signal stream. A killed process can replay previously completed chunks
 * without repeating the costly rule evaluation for all stocks.
 */
internal object BtRobotPreparedChunk {
    private const val VERSION="BT_PREPARED_CHUNK_1"
    data class Slice(val signals:Map<LocalDate,List<BtSignal>>,val missing:Int)

    fun save(file:File,key:String,signals:Map<LocalDate,List<BtSignal>>,missing:Int) {
        require(key.isNotBlank()&&missing>=0)
        file.parentFile?.mkdirs()
        val tmp=File(file.path+".tmp")
        try{
            DataOutputStream(BufferedOutputStream(GZIPOutputStream(BufferedOutputStream(FileOutputStream(tmp)),65536))).use{out->
                out.writeUTF(VERSION)
                out.writeUTF(key)
                out.writeInt(missing)
                val days=signals.toSortedMap()
                out.writeInt(days.size)
                days.forEach{(day,items)->
                    out.writeLong(day.toEpochDay())
                    out.writeInt(items.size)
                    items.forEach{s->
                        out.writeUTF(s.code)
                        out.writeUTF(s.radar)
                        out.writeLong(s.dataDate?.toEpochDay()?:Long.MIN_VALUE)
                        val bytes=s.dEvidence.toByteArray(Charsets.UTF_8)
                        out.writeInt(bytes.size)
                        out.write(bytes)
                    }
                }
            }
            RandomAccessFile(tmp,"rw").use{it.fd.sync()}
            check(tmp.renameTo(file)){"保存機器人條件分段失敗"}
        }catch(error:Throwable){
            tmp.delete()
            throw error
        }
    }

    fun read(file:File,key:String):Slice=
        DataInputStream(BufferedInputStream(GZIPInputStream(BufferedInputStream(file.inputStream(),65536)),65536)).use{input->
            require(input.readUTF()==VERSION&&input.readUTF()==key){"機器人條件分段版本不符"}
            val missing=input.readInt()
            require(missing in 0..100_000_000)
            val count=input.readInt()
            require(count in 0..2000)
            val results=linkedMapOf<LocalDate,List<BtSignal>>()
            repeat(count){
                val day=LocalDate.ofEpochDay(input.readLong())
                val n=input.readInt()
                require(n in 0..10000)
                val items=List(n){
                    val code=input.readUTF()
                    val radar=input.readUTF()
                    val ts=input.readLong()
                    val size=input.readInt()
                    require(size in 0..1_000_000)
                    val bytes=ByteArray(size)
                    input.readFully(bytes)
                    BtSignal(code,radar,if(ts==Long.MIN_VALUE)null else LocalDate.ofEpochDay(ts),
                        bytes.toString(Charsets.UTF_8))
                }
                require(day !in results){"條件分段交易日重複"}
                results[day]=items
            }
            require(input.read()==-1){"機器人條件分段檔案不完整"}
            Slice(results,missing)
        }
}
