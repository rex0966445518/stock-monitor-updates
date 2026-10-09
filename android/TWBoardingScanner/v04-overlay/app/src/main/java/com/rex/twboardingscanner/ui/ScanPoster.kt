package com.rex.twboardingscanner.ui

import android.graphics.*
import com.rex.twboardingscanner.data.HistoryRow
import com.rex.twboardingscanner.domain.*
import java.io.*
import java.time.*
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

internal data class PosterStock(val code:String,val name:String,val sector:String,val radar:RadarType,
    val price:Double,val change:Double,val timestamp:Long,val bars:List<DailyBar>)
internal object ScanPoster {
    fun collect(day:String, history:List<HistoryRow>, live:List<SignalResult>):Map<RadarType,List<PosterStock>> {
        val old=history.filter { it.scanDate==day && it.light!="NONE" }.mapNotNull { r ->
            val radar=runCatching { RadarType.valueOf(r.radar) }.getOrNull() ?: return@mapNotNull null
            PosterStock(r.code,r.name,r.sector,radar,r.price,r.changePct,r.ts,r.chartBars)
        }
        val fresh=live.filter { it.light!=SignalLight.NONE && Instant.ofEpochMilli(it.snapshot.timestamp).atZone(RuleMetrics.TAIPEI).toLocalDate().toString()==day }
            .map { PosterStock(it.code,it.name,it.sector.label,it.radarType,it.snapshot.price,it.snapshot.changePct,it.snapshot.timestamp,it.snapshot.bars) }
        val unique=(old+fresh).sortedByDescending { it.timestamp }.distinctBy { it.radar to it.code }
        return RadarType.entries.associateWith { radar -> unique.filter { it.radar==radar }.sortedBy { it.code } }
    }
    // PNG rows are streamed from small bands, so a large result set does not allocate a giant bitmap.
    fun write(file:File, radar:RadarType, stocks:List<PosterStock>, day:String, time:String, scanning:Boolean) {
        val accent=Color.parseColor(when(radar){ RadarType.A_EARLY_BREAKOUT->"#FF527C";RadarType.B_DEEP_REVERSAL->"#FFBE5C";else->"#45C8FF" })
        val title=when(radar){RadarType.A_EARLY_BREAKOUT->"A  早期起漲";RadarType.B_DEEP_REVERSAL->"B  深跌反轉";else->"C  長紅爆量"}
        val height=if(stocks.isEmpty()) 1350 else 650+stocks.size*250
        val bitmap=Bitmap.createBitmap(1080,256,Bitmap.Config.ARGB_8888)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        fun text(c:Canvas,s:String,x:Float,y:Float,size:Float,color:Int,bold:Boolean=false,max:Float=960f) {
            paint.color=color;paint.textSize=size;paint.typeface=if(bold) Typeface.create("sans-serif",Typeface.BOLD) else Typeface.create("sans-serif",Typeface.NORMAL)
            var value=s
            while(paint.measureText(value)>max && value.length>1) value=value.dropLast(1)
            if(value!=s) value=value.dropLast(1)+"…"
            c.drawText(value,x,y,paint)
        }
        val white=Color.rgb(229,242,255);val muted=Color.rgb(133,164,187)
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
                        val c=Canvas(bitmap);c.drawColor(Color.rgb(4,17,30));c.save();c.translate(0f,-top.toFloat())
                        paint.shader=LinearGradient(0f,0f,1080f,470f,Color.rgb(13,42,65),Color.rgb(4,17,30),Shader.TileMode.CLAMP);c.drawRect(0f,0f,1080f,470f,paint);paint.shader=null
                        text(c,"台股上車掃描器",60f,92f,35f,white,true)
                        text(c,"DAILY MARKET RADAR",60f,132f,19f,muted)
                        paint.color=accent;c.drawRoundRect(60f,175f,150f,182f,4f,4f,paint)
                        text(c,title,60f,267f,64f,accent,true)
                        text(c,"今日曾入選",64f,326f,28f,white)
                        text(c,"${stocks.size} 檔",790f,272f,58f,white,true,230f)
                        text(c,"$day  ·  截至 $time（台灣）",60f,381f,27f,muted)
                        text(c,if(scanning) "掃描進行中 · 本圖為匯出當下已取得的結果" else "今日紀錄彙整 · 每區同股保留最新入選紀錄",60f,424f,23f,muted)
                        if(stocks.isEmpty()) {
                            paint.color=Color.rgb(11,33,51);c.drawRoundRect(60f,510f,1020f,1000f,28f,28f,paint)
                            text(c,"—",478f,680f,90f,accent,true)
                            text(c,"今日尚無入選",300f,780f,48f,white,true)
                            text(c,"待掃描取得結果後，可再次導出",250f,846f,29f,muted)
                        }
                        val first=((top-480)/250).coerceAtLeast(0);val last=((top+256-480)/250).coerceAtMost(stocks.lastIndex)
                        if(last>=first) for(i in first..last) {
                            val s=stocks[i];val y=480f+i*250
                            paint.color=Color.rgb(9,31,49);c.drawRoundRect(60f,y,1020f,y+230,22f,22f,paint)
                            paint.color=accent;c.drawRoundRect(60f,y+20,65f,y+210,2f,2f,paint)
                            text(c,"${s.code}  ${s.name}",88f,y+55,35f,white,true,650f)
                            text(c,s.sector,88f,y+92,22f,muted)
                            val changeColor=if(s.change>=0) Color.rgb(255,89,120) else Color.rgb(66,225,182)
                            text(c,String.format(Locale.TAIWAN,"%,.2f",s.price),88f,y+156,48f,changeColor,true,420f)
                            text(c,String.format(Locale.TAIWAN,"%+.2f%%",s.change),455f,y+153,32f,changeColor,true,240f)
                            val bar=s.bars.lastOrNull()
                            val date=bar?.let { Instant.ofEpochMilli(it.time).atZone(RuleMetrics.TAIPEI).toLocalDate().toString() } ?: "待查核"
                            val volume=bar?.let { String.format(Locale.TAIWAN,"%,.1f 張",it.volumeShares/1000.0) } ?: "待查核"
                            text(c,"日K $date  ·  成交量 $volume",88f,y+203,23f,muted, max=880f)
                            val closes=s.bars.takeLast(30).map { it.close }
                            if(closes.size>1) {
                                val lo=closes.min();val spread=(closes.max()-lo).coerceAtLeast(.01)
                                val path=Path();closes.forEachIndexed { j,v -> val x=775f+j*200f/(closes.size-1);val yy=y+145f-((v-lo)/spread*65).toFloat();if(j==0)path.moveTo(x,yy)else path.lineTo(x,yy) }
                                paint.style=Paint.Style.STROKE;paint.strokeWidth=3f;paint.color=accent;c.drawPath(path,paint);paint.style=Paint.Style.FILL
                                text(c,"近30日收盤",775f,y+175,17f,muted,max=210f)
                            }
                        }
                        val footer=height-125f
                        paint.color=Color.rgb(28,59,81);c.drawRect(60f,footer-25,1020f,footer-23,paint)
                        text(c,"今日曾入選可能使用不同勾選條件，不代表目前即時清單。",60f,footer+10,23f,muted)
                        text(c,"A/B 排除當日日K；C 採最新日K。觀察訊號不保證後續漲幅。",60f,footer+49,23f,muted)
                        text(c,"僅供研究參考  ·  請自行評估投資風險",60f,footer+88,21f,muted)
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
