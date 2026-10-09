package com.rex.twboardingscanner.ui

import android.graphics.*
import com.rex.twboardingscanner.domain.*
import java.io.*
import java.time.*
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

internal data class PosterStock(val code:String,val name:String,val sector:String,val radar:RadarType,
    val price:Double,val change:Double,val timestamp:Long,val bars:List<DailyBar>,
    val score:Int=0,val checks:List<ConditionCheck> = emptyList())
internal object ScanPoster {
    // The live list is the only source. Never merge persisted scan history into an export.
    fun collect(live:List<SignalResult>):Map<RadarType,List<PosterStock>> {
        val current=live.sortedByDescending { it.snapshot.timestamp }.distinctBy { it.radarType to it.code }
            .filter { it.light!=SignalLight.NONE }.sortedByDescending { it.score }
            .map { PosterStock(it.code,it.name,it.sector.label,it.radarType,it.snapshot.price,it.snapshot.changePct,
                it.snapshot.timestamp,it.snapshot.bars.toList(),it.score,it.checks.toList()) }
        return RadarType.entries.associateWith { radar -> current.filter { it.radar==radar } }
    }
    // PNG rows are streamed from small bands, so a large result set does not allocate a giant bitmap.
    fun write(file:File, radar:RadarType, stocks:List<PosterStock>, day:String, time:String, scanning:Boolean, counts:List<Int> = listOf(stocks.size,0,0), done:Int=0,total:Int=0) {
        val design=NeonPoster(radar,stocks,day,time,scanning,counts,done,total)
        val height=design.height
        val bitmap=Bitmap.createBitmap(1080,256,Bitmap.Config.ARGB_8888)
        val partial=File(file.path+".part")
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(partial))).use { out ->
                out.write(byteArrayOf(-119,80,78,71,13,10,26,10))
                fun chunk(type:String,data:ByteArray) {val t=type.toByteArray(Charsets.US_ASCII);out.writeInt(data.size);out.write(t);out.write(data);val crc=CRC32();crc.update(t);crc.update(data);out.writeInt(crc.value.toInt())}
                val header=ByteArrayOutputStream();DataOutputStream(header).apply {writeInt(1080);writeInt(height);writeByte(8);writeByte(2);writeByte(0);writeByte(0);writeByte(0)}
                chunk("IHDR",header.toByteArray())
                val sink=object:OutputStream(){override fun write(b:Int){write(byteArrayOf(b.toByte()))};override fun write(b:ByteArray,off:Int,len:Int){chunk("IDAT",b.copyOfRange(off,off+len))}}
                DeflaterOutputStream(sink).use { zip ->
                    val pixels=IntArray(1080);val row=ByteArray(3241)
                    for(top in 0 until height step 256) {
                        if(Thread.currentThread().isInterrupted) throw InterruptedIOException()
                        val c=Canvas(bitmap);c.drawColor(Color.rgb(3,15,29));c.save();c.translate(0f,-top.toFloat())
                        design.draw(c,top,256)
                        c.restore()
                        val rows=minOf(256,height-top)
                        for(yy in 0 until rows){bitmap.getPixels(pixels,0,1080,0,yy,1080,1);row[0]=0;for(x in pixels.indices){val v=pixels[x];row[1+x*3]=(v shr 16).toByte();row[2+x*3]=(v shr 8).toByte();row[3+x*3]=v.toByte()};zip.write(row)}
                    }
                }
                chunk("IEND",byteArrayOf())
            }
            check(partial.renameTo(file)) { "海報檔案無法完成" }
        } finally {bitmap.recycle();partial.delete()}
    }
}
