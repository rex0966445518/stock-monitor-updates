package com.rex.twboardingscanner.backtest

import com.rex.twboardingscanner.domain.*
import java.math.BigInteger
import java.time.LocalDate

/** Each radar's own checkbox is a separate bit, including optional financial checks. */
object BtRobotSpace {
    val slots=RadarType.entries.flatMap{type->ScanConditions.forRadar(type).map{type to it.id}}
    val total:BigInteger=BigInteger.ONE.shiftLeft(slots.size)
    fun key(rules:Map<RadarType,Set<String>>):String=slots.foldIndexed(BigInteger.ZERO){i,n,(type,id)->if(id in rules[type].orEmpty())n.setBit(i)else n}.toString(16)
    fun rules(key:String):Map<RadarType,Set<String>> {
        val mask=BigInteger(key,16);require(mask.signum()>=0&&mask<total)
        return RadarType.entries.associateWith{type->slots.mapIndexedNotNull{i,p->if(p.first==type&&mask.testBit(i))p.second else null}.toSet()}
    }
    fun settings(rules:Map<RadarType,Set<String>> = defaultBtRules(),sectors:Set<StockSector> = StockSector.entries.toSet())=
        BtSettings(LocalDate.of(2026,8,1),LocalDate.of(2026,10,8),5000000.0,"",rules,sectors,maxHoldingStocks=25,targetNetPct=4.0)
    fun validate(s:BtSettings){
        require(s.start==LocalDate.of(2026,8,1)&&s.end==LocalDate.of(2026,10,8)&&s.capital==5000000.0&&s.codes.isEmpty()&&s.maxHoldingStocks==25&&s.targetNetPct==4.0&&s.strategyVersion==4){"機器人交易參數必須固定"}
        require(s.sectors.isNotEmpty())
        require(s.rules.keys==RadarType.entries.toSet())
        key(s.rules).let{require(rules(it)==s.rules)}
    }
    data class Candidate(val key:String,val cursor:BigInteger)
    /** Best-first one-bit neighbours, then an odd affine permutation visits every bit mask once. */
    fun next(base:String,best:String?,cursor:BigInteger,seed:BigInteger,seen:(String)->Boolean):Candidate? {
        require(cursor.signum()>=0&&cursor<=total)
        val defaults=key(defaultBtRules())
        val starting=listOf(base,defaults)+RadarType.entries.map{type->key(RadarType.entries.associateWith{if(it==type)defaultBtRules().getValue(it)else emptySet()})}+listOf("0")
        for(k in starting)if(!seen(k))return Candidate(k,cursor)
        for(center in listOfNotNull(best,base).distinct())for(i in slots.indices){val k=BigInteger(center,16).flipBit(i).toString(16);if(!seen(k))return Candidate(k,cursor)}
        var n=cursor
        val step=seed.shiftLeft(1).or(BigInteger.ONE).mod(total) // odd => bijection modulo a power of two
        while(n<total){val k=n.multiply(step).add(seed).mod(total).toString(16);n+=BigInteger.ONE;if(!seen(k))return Candidate(k,n)}
        return null
    }
}
