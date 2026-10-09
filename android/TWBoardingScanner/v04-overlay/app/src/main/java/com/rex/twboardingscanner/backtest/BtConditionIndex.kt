package com.rex.twboardingscanner.backtest

import com.rex.twboardingscanner.domain.*
import java.time.LocalDate
import java.io.*
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.max

/** Evaluate historical facts once. Every candidate then selects subsets of the same facts. */
class BtConditionIndex private constructor(val series:List<BtSeries>,private val records:List<Record>,val days:List<LocalDate>){
    private data class Record(val code:String,val day:LocalDate,val dataDates:Array<LocalDate?>,val passed:LongArray,val pending:LongArray)
    fun prepare(s:BtSettings,cancel:()->Boolean={false}):BtPrepared {
        val selected=RadarType.entries.map{type->
            val ids=s.rules[type].orEmpty();val options=ScanConditions.forRadar(type)
            require(ids.all{id->options.any{it.id==id}})
            options.foldIndexed(0L){i,mask,rule->if(rule.id in ids)mask or (1L shl i)else mask}
        }
        val signals=mutableMapOf<LocalDate,MutableList<BtSignal>>();var missing=0
        records.forEachIndexed{index,r->
            if(index%512==0)check(!cancel()){ "已暫停測試" }
            val matches=mutableListOf<Int>()
            selected.forEachIndexed{i,mask->
                if(mask!=0L&&r.dataDates[i]!=null){
                    missing+=java.lang.Long.bitCount(mask and r.pending[i])
                    if((mask and r.passed[i])==mask)matches+=i
                }
            }
            if(matches.isNotEmpty())signals.getOrPut(r.day){mutableListOf()}.add(BtSignal(r.code,matches.joinToString("/"){RadarType.entries[it].name.take(1)},matches.maxOf{r.dataDates[it]!!}))
        }
        return BtPrepared(series,signals,days,missing)
    }
    fun save(file:File,fingerprint:String){
        file.parentFile?.mkdirs();val tmp=File(file.path+".tmp")
        DataOutputStream(BufferedOutputStream(GZIPOutputStream(tmp.outputStream()))).use{out->
            out.writeUTF("BTINDEX1");out.writeUTF(fingerprint);out.writeInt(records.size)
            records.forEach{r->out.writeUTF(r.code);out.writeLong(r.day.toEpochDay());repeat(3){i->out.writeLong(r.dataDates[i]?.toEpochDay()?:Long.MIN_VALUE);out.writeLong(r.passed[i]);out.writeLong(r.pending[i])}}
            out.writeInt(days.size);days.forEach{out.writeLong(it.toEpochDay())}
        }
        RandomAccessFile(tmp,"rw").use{it.fd.sync()};check(tmp.renameTo(file)){"歷史條件快取無法保存"}
    }
    companion object {
        fun fingerprint(digest:String,settings:BtSettings)="$digest:${settings.start}:${settings.end}:${ScanConditions.VERSION}:"+BtRobotSpace.slots.joinToString(","){"${it.first.name}/${it.second}"}
        fun read(file:File,series:List<BtSeries>,fingerprint:String):BtConditionIndex=DataInputStream(BufferedInputStream(GZIPInputStream(file.inputStream()))).use{input->
            require(input.readUTF()=="BTINDEX1"&&input.readUTF()==fingerprint){"歷史條件快取版本或行情不符"}
            val count=input.readInt();require(count in 0..1000000)
            val codes=series.map{it.code}.toSet()
            val records=List(count){
                val code=input.readUTF();require(code in codes);val day=LocalDate.ofEpochDay(input.readLong())
                val dates=arrayOfNulls<LocalDate>(3);val passed=LongArray(3);val pending=LongArray(3)
                repeat(3){i->val date=input.readLong();dates[i]=if(date==Long.MIN_VALUE)null else LocalDate.ofEpochDay(date);passed[i]=input.readLong();pending[i]=input.readLong()
                    val allowed=(1L shl ScanConditions.forRadar(RadarType.entries[i]).size)-1
                    require((passed[i] and allowed)==passed[i]&&(pending[i] and allowed)==pending[i]&&(passed[i] and pending[i])==0L)
                };Record(code,day,dates,passed,pending)
            }
            val n=input.readInt();require(n in 0..5000);val days=List(n){LocalDate.ofEpochDay(input.readLong())}
            require(days==days.distinct().sorted()&&input.read()==-1){"歷史條件快取不完整"}
            BtConditionIndex(series,records,days)
        }
        fun build(series:List<BtSeries>,settings:BtSettings,progress:(String)->Unit={},cancel:()->Boolean={false}):BtConditionIndex {
            val records=mutableListOf<Record>();val calc=TechnicalCalculator()
            val options=RadarType.entries.map{ScanConditions.forRadar(it)}
            require(options.all{it.size<63})
            series.forEachIndexed{index,s->
                check(!cancel()){ "已暫停測試" }
                val bars=s.bars.sortedBy{it.time}.distinctBy{RuleMetrics.tradingDate(it.time)}
                bars.indices.forEach dayLoop@{j->
                    val day=RuleMetrics.tradingDate(bars[j].time)
                    if(day<settings.start||day>settings.end)return@dayLoop
                    check(!cancel()){ "已暫停測試" }
                    val pass=LongArray(3);val pending=LongArray(3);val dates=arrayOfNulls<LocalDate>(3)
                    val metrics=mutableMapOf<Int,RuleMetrics>()
                    RadarType.entries.forEachIndexed radar@{i,type->
                        val end=if(type==RadarType.C_LONG_RED_VOLUME)j+1 else j
                        if(end<60)return@radar
                        val m=metrics.getOrPut(end){
                            val known=bars.subList(max(0,end-320),end);val bar=known.last()
                            val stock=MarketStock(s.code,s.name,s.market,StockSector.UNKNOWN,bar.open,bar.high,bar.low,bar.close,0.0,(bar.volumeShares/1000).toInt(),null,null)
                            RuleMetrics(calc.build(stock,known).copy(timestamp=day.atTime(13,30).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli()))
                        }
                        dates[i]=RuleMetrics.tradingDate(m.last!!.time)
                        options[i].forEachIndexed{bit,condition->when(condition.test(m)){true->pass[i]=pass[i] or (1L shl bit);null->pending[i]=pending[i] or (1L shl bit);false->Unit}}
                    }
                    records+=Record(s.code,day,dates,pass,pending)
                }
                if(index%10==0||index==series.lastIndex)progress("預算歷史條件 ${index+1}/${series.size} 檔；完成後重複使用")
            }
            return BtConditionIndex(series,records,series.flatMap{it.bars.map{b->RuleMetrics.tradingDate(b.time)}}.distinct().sorted())
        }
    }
}
