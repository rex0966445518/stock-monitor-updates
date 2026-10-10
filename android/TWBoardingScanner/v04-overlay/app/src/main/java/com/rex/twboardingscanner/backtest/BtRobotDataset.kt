package com.rex.twboardingscanner.backtest

import com.rex.twboardingscanner.domain.*
import java.io.*
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Session-owned historical snapshot; resumes never fetch a different comparison sample. */
object BtRobotDataset {
    fun write(file:File,data:BacktestData.Loaded){
        file.parentFile?.mkdirs();val temp=File(file.path+".tmp")
        DataOutputStream(BufferedOutputStream(GZIPOutputStream(temp.outputStream()))).use{out->
            out.writeUTF(if(data.market.isEmpty())"BTROBOT1" else "BTROBOT2");out.writeInt(data.requested);out.writeInt(data.excluded.size);data.excluded.forEach{out.writeUTF(it)}
            out.writeInt(data.series.size)
            data.series.forEach{s->
                out.writeUTF(s.code);out.writeUTF(s.name);out.writeUTF(s.market.name);out.writeInt(s.bars.size)
                s.bars.forEach{b->out.writeLong(b.time);out.writeDouble(b.open);out.writeDouble(b.high);out.writeDouble(b.low);out.writeDouble(b.close);out.writeLong(b.volumeShares)}
                out.writeInt(s.dividends.size);s.dividends.forEach{(day,value)->out.writeLong(day.toEpochDay());out.writeDouble(value)}
            }
            if(data.market.isNotEmpty()){out.writeInt(data.market.size);data.market.forEach{b->out.writeLong(b.date.toEpochDay());out.writeDouble(b.close);out.writeDouble(b.change)}}
        }
        RandomAccessFile(temp,"rw").use{it.fd.sync()};check(temp.renameTo(file)){"無法保存歷史資料快照"}
    }
    fun read(file:File):BacktestData.Loaded=DataInputStream(BufferedInputStream(GZIPInputStream(file.inputStream()))).use{input->
        val version=input.readUTF();require(version in listOf("BTROBOT1","BTROBOT2")){"資料快照版本不符"}
        fun count(max:Int)=input.readInt().also{require(it in 0..max)}
        val requested=count(10000);val excluded=List(count(10000)){input.readUTF()}
        val series=List(count(10000)){
            val code=input.readUTF();val name=input.readUTF();val market=Market.valueOf(input.readUTF())
            val bars=List(count(5000)){DailyBar(input.readLong(),input.readDouble(),input.readDouble(),input.readDouble(),input.readDouble(),input.readLong())}
            val div=mutableMapOf<LocalDate,Double>();repeat(count(1000)){div[LocalDate.ofEpochDay(input.readLong())]=input.readDouble()}
            BtSeries(code,name,market,bars,div)
        }
        val market=if(version=="BTROBOT2")List(count(5000)){MarketIndexBar(LocalDate.ofEpochDay(input.readLong()),input.readDouble(),input.readDouble())} else emptyList()
        MarketCrashGuard.timeline(market)
        require(series.isNotEmpty()&&input.read()==-1){"歷史資料快照不完整"};BacktestData.Loaded(series,requested,excluded,market)
    }
}
