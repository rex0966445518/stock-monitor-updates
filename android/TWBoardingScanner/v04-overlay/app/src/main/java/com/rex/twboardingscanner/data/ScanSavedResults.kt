package com.rex.twboardingscanner.data

import com.google.gson.*
import com.google.gson.stream.*
import com.rex.twboardingscanner.domain.*
import java.io.*
import java.time.LocalDate
import java.time.LocalTime
import java.util.IdentityHashMap
import java.util.zip.*

/** Frozen evidence, including rejected/pending cards and the separate paper strategy pool. */
data class ScanSavedResults(
    val id:String, val date:String, val created:Long,
    val main:List<SignalResult>, val paper:List<SignalResult>,
    val radars:Set<RadarType>, val sectors:Set<StockSector>, val policy:StockPolicy,
    val requested:Int, val analyzed:Int, val failed:Int
)
internal object ScanSavedCodec {
    private data class WireSignal(val result:SignalResult,val series:Int)
    private data class Wire(val scan:ScanSavedResults,val bars:List<List<DailyBar>>,
        val signals:List<WireSignal>,val main:IntArray,val paper:IntArray)
    private fun <T> dateAdapter(parse:(String)->T)=object:TypeAdapter<T>() {
        override fun write(out:JsonWriter,value:T?){if(value==null)out.nullValue() else out.value(value.toString())}
        override fun read(input:JsonReader):T? {
            if(input.peek()==JsonToken.NULL){input.nextNull();return null}
            return parse(input.nextString())
        }
    }
    private fun builder()=GsonBuilder()
        .registerTypeAdapter(LocalDate::class.java,dateAdapter(LocalDate::parse))
        .registerTypeAdapter(LocalTime::class.java,dateAdapter(LocalTime::parse))
        .serializeSpecialFloatingPointValues()
    private val gson=builder().create()
    fun encode(value:ScanSavedResults):ByteArray=ByteArrayOutputStream().let{buffer->
        write(value,buffer);buffer.toByteArray()
    }
    fun write(value:ScanSavedResults,output:OutputStream){
        // Six radars and the paper pool share daily evidence. Store each series once,
        // rather than recreating millions of identical DailyBar objects on restore.
        val series=mutableListOf<List<DailyBar>>()
        val seriesIds=HashMap<List<DailyBar>,Int>()
        val signals=mutableListOf<WireSignal>()
        val ids=IdentityHashMap<SignalResult,Int>()
        fun index(result:SignalResult):Int=ids[result]?:signals.size.also{id->
            ids[result]=id
            val bars=result.snapshot.bars
            val seriesId=seriesIds.getOrPut(bars){series.size.also{series.add(bars)}}
            signals.add(WireSignal(result.copy(snapshot=result.snapshot.copy(bars=emptyList())),seriesId))
        }
        val main=value.main.map(::index).toIntArray()
        val paper=value.paper.map(::index).toIntArray()
        val wire=Wire(value.copy(main=emptyList(),paper=emptyList()),series,signals,main,paper)
        GZIPOutputStream(output).bufferedWriter(Charsets.UTF_8).use{writer->
            writer.write("{\"schema\":2,\"payload\":")
            gson.toJson(wire,writer)
            writer.write("}")
        }
    }
    fun decode(bytes:ByteArray):ScanSavedResults=decode(ByteArrayInputStream(bytes))
    fun decode(input:InputStream):ScanSavedResults {
        // Also share bars while reading v0.4.92's unpooled format. Never build a
        // giant JSONObject or a second full uncompressed JSON string in memory.
        val bars=HashMap<DailyBar,DailyBar>()
        val readerGson=builder().registerTypeAdapter(DailyBar::class.java,object:TypeAdapter<DailyBar>() {
            override fun write(out:JsonWriter,value:DailyBar){error("Read-only adapter")}
            override fun read(input:JsonReader):DailyBar {
                var time=0L;var open=0.0;var high=0.0;var low=0.0;var close=0.0;var volume=0L
                input.beginObject()
                while(input.hasNext())when(input.nextName()){
                    "time"->time=input.nextLong();"open"->open=input.nextDouble()
                    "high"->high=input.nextDouble();"low"->low=input.nextDouble()
                    "close"->close=input.nextDouble();"volumeShares"->volume=input.nextLong()
                    else->input.skipValue()
                }
                input.endObject()
                val bar=DailyBar(time,open,high,low,close,volume)
                return bars.getOrPut(bar){bar}
            }
        }).create()
        try{return GZIPInputStream(input).bufferedReader(Charsets.UTF_8).use{reader->
            reader.mark(64)
            val prefix=CharArray(32);val length=reader.read(prefix);reader.reset()
            if(length>0&&String(prefix,0,length).startsWith("{\"schema\":2,")){
                JsonReader(reader).use{json->
                    json.beginObject();check(json.nextName()=="schema"&&json.nextInt()==2)
                    check(json.nextName()=="payload")
                    val wire=readerGson.fromJson<Wire>(json,Wire::class.java)
                    json.endObject()
                    val restored=wire.signals.map{row->
                        require(row.series in wire.bars.indices){"掃描K線索引異常"}
                        row.result.copy(snapshot=row.result.snapshot.copy(bars=wire.bars[row.series]))
                    }
                    fun restore(ids:IntArray)=ids.map{id->
                        require(id in restored.indices){"掃描結果索引異常"};restored[id]
                    }
                    wire.scan.copy(main=restore(wire.main),paper=restore(wire.paper))
                }
            }else readerGson.fromJson(reader,ScanSavedResults::class.java)
        }}finally{bars.clear()}
    }
}
