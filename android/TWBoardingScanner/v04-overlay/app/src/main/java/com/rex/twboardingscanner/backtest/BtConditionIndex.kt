package com.rex.twboardingscanner.backtest

import com.rex.twboardingscanner.domain.*
import java.time.LocalDate
import java.io.*
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.max

/**
 * Frozen facts for ABCDEF. BTINDEX5 stores five distinct indicator vectors and
 * the D/E audit trails; older BTINDEX1/2 files are rejected and rebuilt from the
 * same original price/index snapshots. Never interpret an ABC cache as D signals.
 */
class BtConditionIndex private constructor(
    val series:List<BtSeries>,
    private val records:List<Record>,
    val days:List<LocalDate>
){
    private data class Record(
        val code:String,val day:LocalDate,val dataDates:Array<LocalDate?>,
        val passed:LongArray,val pending:LongArray,val dEvidence:String,val eEvidence:String,val fEvidence:String,val vetoHit:LongArray,val vetoPending:LongArray
    )
    fun prepare(s:BtSettings,cancel:()->Boolean={false}):BtPrepared {
        val selected=RadarType.entries.map{type->
            val ids=if(type in s.enabledRadars)s.rules[type].orEmpty() else emptySet()
            val options=ScanConditions.forRadar(type)
            require(ids.all{id->options.any{it.id==id}})
            options.foldIndexed(0L){i,mask,rule->if(rule.id in ids)mask or (1L shl i)else mask}
        }
        val exclusionMasks=RadarType.entries.map{type->ExclusionConditions.forRadar(type).foldIndexed(0L){i,m,r->if(r.id in s.exclusions.getValue(type))m or (1L shl i)else m}}
        val signals=mutableMapOf<LocalDate,MutableList<BtSignal>>();var missing=0
        records.forEachIndexed{index,r->
            if(index%512==0)check(!cancel()){"已暫停測試"}
            val matches=mutableListOf<Int>()
            selected.forEachIndexed{i,mask->
                if((mask!=0L||(s.robotBaseline&&RadarType.entries[i] in s.enabledRadars))&&r.dataDates[i]!=null){
                    missing+=java.lang.Long.bitCount(mask and r.pending[i])
                    val veto=exclusionMasks[i]
                    missing+=java.lang.Long.bitCount(veto and r.vetoPending[i])
                    if((mask and r.passed[i])==mask&&(veto and (r.vetoHit[i] or r.vetoPending[i]))==0L)matches+=i
                }
            }
            if(matches.isNotEmpty())signals.getOrPut(r.day){mutableListOf()}.add(
                BtSignal(r.code,matches.joinToString("/"){RadarType.entries[it].name.take(1)},
                    matches.maxOf{r.dataDates[it]!!},
                    listOfNotNull(r.dEvidence.takeIf{RadarType.D_MAIN_FORCE.ordinal in matches},r.eEvidence.takeIf{RadarType.E_DIF_TOUCH.ordinal in matches},r.fEvidence.takeIf{RadarType.F_VOLUME_CONTRACTION.ordinal in matches}).joinToString("\n"))
            )
        }
        return BtPrepared(series,signals,days,missing)
    }
    fun save(file:File,fingerprint:String){
        file.parentFile?.mkdirs();val tmp=File(file.path+".tmp")
        DataOutputStream(BufferedOutputStream(GZIPOutputStream(tmp.outputStream()))).use{out->
            out.writeUTF("BTINDEX5");out.writeUTF(fingerprint);out.writeInt(records.size)
            records.forEach{r->
                out.writeUTF(r.code);out.writeLong(r.day.toEpochDay())
                repeat(RadarType.entries.size){i->
                    out.writeLong(r.dataDates[i]?.toEpochDay()?:Long.MIN_VALUE)
                    out.writeLong(r.passed[i]);out.writeLong(r.pending[i]);out.writeLong(r.vetoHit[i]);out.writeLong(r.vetoPending[i])
                }
                out.writeUTF(r.dEvidence.take(8000));out.writeUTF(r.eEvidence.take(8000));out.writeUTF(r.fEvidence.take(8000))
            }
            out.writeInt(days.size);days.forEach{out.writeLong(it.toEpochDay())}
        }
        RandomAccessFile(tmp,"rw").use{it.fd.sync()}
        check(tmp.renameTo(file)){"歷史條件快取無法保存"}
    }
    companion object {
        fun fingerprint(digest:String,settings:BtSettings,flowRevision:String="none")=
            "$digest:${settings.start}:${settings.end}:${ScanConditions.VERSION}:$flowRevision:"+
            settings.enabledRadars.sortedBy{it.ordinal}.joinToString(","){it.name}+":"+
            settings.dConfig.json().toString()+":"+settings.eConfig.json().toString()+":"+settings.fConfig.json().toString()+":"+settings.bConfig.json().toString()+":"+BtRobotSpace.allSlots.joinToString(","){"${it.first.name}/${it.second}"}
        fun read(file:File,series:List<BtSeries>,fingerprint:String):BtConditionIndex=
            DataInputStream(BufferedInputStream(GZIPInputStream(file.inputStream()))).use{input->
                require(input.readUTF()=="BTINDEX5"&&input.readUTF()==fingerprint){"歷史 ABCDEF 條件快取或版本不一致"}
                val count=input.readInt();require(count in 0..1000000)
                val codes=series.map{it.code}.toSet()
                val options=RadarType.entries.map{ScanConditions.forRadar(it)}
                val records=List(count){
                    val code=input.readUTF();require(code in codes)
                    val day=LocalDate.ofEpochDay(input.readLong())
                    val dates=arrayOfNulls<LocalDate>(RadarType.entries.size);val passed=LongArray(RadarType.entries.size);val pending=LongArray(RadarType.entries.size);val vetoHit=LongArray(RadarType.entries.size);val vetoPending=LongArray(RadarType.entries.size)
                    repeat(RadarType.entries.size){i->
                        val date=input.readLong()
                        dates[i]=if(date==Long.MIN_VALUE)null else LocalDate.ofEpochDay(date)
                        passed[i]=input.readLong();pending[i]=input.readLong();vetoHit[i]=input.readLong();vetoPending[i]=input.readLong()
                        val vetoAllowed=(1L shl ExclusionConditions.forRadar(RadarType.entries[i]).size)-1L
                        require((vetoHit[i] and vetoAllowed)==vetoHit[i]&&(vetoPending[i] and vetoAllowed)==vetoPending[i]&&(vetoHit[i] and vetoPending[i])==0L)
                        val allowed=(1L shl options[i].size)-1L
                        require((passed[i] and allowed)==passed[i]&&(pending[i] and allowed)==pending[i]&&
                            (passed[i] and pending[i])==0L)
                    }
                    Record(code,day,dates,passed,pending,input.readUTF(),input.readUTF(),input.readUTF(),vetoHit,vetoPending)
                }
                val n=input.readInt();require(n in 0..5000)
                val days=List(n){LocalDate.ofEpochDay(input.readLong())}
                require(days==days.distinct().sorted()&&input.read()==-1){"歷史 ABCDEF 條件快取不完整"}
                BtConditionIndex(series,records,days)
            }
        /** Atomic chunks survive a WorkManager time slice or process interruption. */
        fun buildResumable(series:List<BtSeries>,settings:BtSettings,parts:File,fingerprint:String,
            progress:(String)->Unit={},cancel:()->Boolean={false},
            flowFor:(String,LocalDate)->TradeSideVolume?={_,_->null},benchmark:Map<LocalDate,Double>?=null,
            chunkSize:Int=8):BtConditionIndex {
            require(chunkSize in 1..64)
            parts.mkdirs()
            val records=mutableListOf<Record>();val days=sortedSetOf<LocalDate>();var completed=0
            series.chunked(chunkSize).forEachIndexed{i,chunk->
                check(!cancel()){"已暫停測試"}
                val file=File(parts,"$i.bin.gz")
                val key="$fingerprint:chunk:$chunkSize:$i"
                val cached=if(file.exists())runCatching{read(file,chunk,key)}.getOrNull()else null
                val part=cached?:build(chunk,settings,{},cancel,flowFor,benchmark).also{index->
                    check(!cancel()){"已暫停測試"};index.save(file,key)
                }
                records.addAll(part.records);days.addAll(part.days);completed+=chunk.size
                progress("歷史條件分段保存 $completed/${series.size} 檔 · ${if(cached!=null)"沿用快取" else "已保存"}")
            }
            return BtConditionIndex(series,records,days.toList())
        }
        fun build(series:List<BtSeries>,settings:BtSettings,progress:(String)->Unit={},
                  cancel:()->Boolean={false},
                  flowFor:(String,LocalDate)->TradeSideVolume?={_,_->null},
                  benchmark:Map<LocalDate,Double>?=null):BtConditionIndex {
            val records=mutableListOf<Record>();val calc=TechnicalCalculator()
            val options=RadarType.entries.map{ScanConditions.forRadar(it)}
            require(options.all{it.size<63})
            series.forEachIndexed{index,s->
                check(!cancel()){"已暫停測試"}
                val bars=s.bars.sortedBy{it.time}.distinctBy{RuleMetrics.tradingDate(it.time)}
                bars.indices.forEach dayLoop@{j->
                    val day=RuleMetrics.tradingDate(bars[j].time)
                    if(day<settings.start||day>settings.end)return@dayLoop
                    check(!cancel()){"已暫停測試"}
                    val pass=LongArray(RadarType.entries.size);val pending=LongArray(RadarType.entries.size);val vetoHit=LongArray(RadarType.entries.size);val vetoPending=LongArray(RadarType.entries.size);val dates=arrayOfNulls<LocalDate>(RadarType.entries.size)
                    val metrics=mutableMapOf<Int,RuleMetrics>();var details="";var eDetails="";var fDetails=""
                    RadarType.entries.forEachIndexed radar@{i,type->
                        if(type !in settings.enabledRadars)return@radar
                        val end=if(type==RadarType.C_LONG_RED_VOLUME||type==RadarType.D_MAIN_FORCE)j+1 else j
                        if(end<60)return@radar
                        val m=metrics.getOrPut(end){
                            val known=bars.subList(max(0,end-320),end);val bar=known.last()
                            val stock=MarketStock(s.code,s.name,s.market,StockSector.UNKNOWN,
                                bar.open,bar.high,bar.low,bar.close,0.0,
                                (bar.volumeShares/1000).toInt(),null,null,
                                tradeSideVolume=flowFor(s.code,day))
                            RuleMetrics(calc.buildForRules(stock,known).copy(
                                timestamp=day.atTime(13,30).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),
                                dConfig=settings.dConfig,eConfig=settings.eConfig,fConfig=settings.fConfig,bConfig=settings.bConfig,marketIndexCloses=benchmark))
                        }
                        dates[i]=RuleMetrics.tradingDate(m.last!!.time)
                        options[i].forEachIndexed{bit,condition->
                            when(condition.test(m)){
                                true->pass[i]=pass[i] or (1L shl bit)
                                null->pending[i]=pending[i] or (1L shl bit)
                                false->Unit
                            }
                        }
                        ExclusionConditions.forRadar(type).forEachIndexed{bit,condition->when(condition.test(m)){true->vetoHit[i]=vetoHit[i] or (1L shl bit);null->vetoPending[i]=vetoPending[i] or (1L shl bit);false->Unit}}
                        if(type==RadarType.D_MAIN_FORCE)details=m.dPattern.format()
                        if(type==RadarType.F_VOLUME_CONTRACTION)fDetails=m.fPattern.format()
                        if(type==RadarType.E_DIF_TOUCH)eDetails=m.ePattern?.format().orEmpty()
                    }
                    records+=Record(s.code,day,dates,pass,pending,details,eDetails,fDetails,vetoHit,vetoPending)
                }
                if(index%10==0||index==series.lastIndex)progress("預算歷史 ABCDEF 條件 ${index+1}/${series.size} 檔；完成後重複使用")
            }
            return BtConditionIndex(series,records,
                series.flatMap{it.bars.map{b->RuleMetrics.tradingDate(b.time)}}.distinct().sorted())
        }
    }
}
