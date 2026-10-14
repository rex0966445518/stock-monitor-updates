package com.rex.twboardingscanner.data

import com.google.gson.*
import com.rex.twboardingscanner.domain.*
import java.io.*
import java.time.LocalDate
import java.time.LocalTime
import java.util.zip.*

/** Frozen evidence, including rejected/pending cards and the separate paper strategy pool. */
data class ScanSavedResults(
    val id:String, val date:String, val created:Long,
    val main:List<SignalResult>, val paper:List<SignalResult>,
    val radars:Set<RadarType>, val sectors:Set<StockSector>, val policy:StockPolicy,
    val requested:Int, val analyzed:Int, val failed:Int
)
internal object ScanSavedCodec {
    private fun <T> dateAdapter(parse:(String)->T)=object:TypeAdapter<T>() {
        override fun write(out:com.google.gson.stream.JsonWriter,value:T?){
            if(value==null)out.nullValue() else out.value(value.toString())
        }
        override fun read(input:com.google.gson.stream.JsonReader):T? {
            if(input.peek()==com.google.gson.stream.JsonToken.NULL){input.nextNull();return null}
            return parse(input.nextString())
        }
    }
    private val gson=GsonBuilder()
        .registerTypeAdapter(LocalDate::class.java,dateAdapter(LocalDate::parse))
        .registerTypeAdapter(LocalTime::class.java,dateAdapter(LocalTime::parse))
        .serializeSpecialFloatingPointValues()
        .create()
    fun encode(value:ScanSavedResults):ByteArray=ByteArrayOutputStream().let{buffer->
        GZIPOutputStream(buffer).bufferedWriter(Charsets.UTF_8).use{gson.toJson(value,it)}
        buffer.toByteArray()
    }
    fun decode(bytes:ByteArray):ScanSavedResults=GZIPInputStream(ByteArrayInputStream(bytes))
        .bufferedReader(Charsets.UTF_8).use{gson.fromJson(it,ScanSavedResults::class.java)}
}
