package com.rex.twboardingscanner.backtest

import com.rex.twboardingscanner.domain.*
import java.math.BigInteger
import java.time.LocalDate

/** Each radar's own checkbox is a separate bit, including optional financial checks. */
object BtRobotSpace {
    // Legacy bit positions must NEVER move; old journals and robot checkpoint keys
    // used 65 bits. Append new features after ALL historical positions.
    val legacySlots=RadarType.entries.filter{it!=RadarType.D_MAIN_FORCE}.flatMap{type->
        ScanConditions.forRadar(type).filterNot{
            it.id in ScanConditions.NEW_IDS||it.id==ScanConditions.MAIN_FORCE_COST_20
        }.map{type to it.id}
    }
    val slots=legacySlots+RadarType.entries.filter{it!=RadarType.D_MAIN_FORCE}.flatMap{type->
        ScanConditions.forRadar(type).filter{it.id in ScanConditions.NEW_IDS}.map{type to it.id}
    }
    // v0.4.48 search keys have 102 fixed positions. Leave every existing
    // bit (including D's 31 inherited A predicates) exactly where it was.
    val dSlots=ScanConditions.forRadar(RadarType.D_MAIN_FORCE)
        .filterNot{it.id==ScanConditions.MAIN_FORCE_COST_20}
        .map{RadarType.D_MAIN_FORCE to it.id}
    val v4Slots=slots+dSlots
    // All FOUR new switches are appended AFTER the historical bit 101.
    val mainCostSlots=RadarType.entries.map{it to ScanConditions.MAIN_FORCE_COST_20}
    val allSlots=v4Slots+mainCostSlots
    val total:BigInteger=BigInteger.ONE.shiftLeft(allSlots.size)
    val legacyTotal:BigInteger=BigInteger.ONE.shiftLeft(legacySlots.size)
    fun key(rules:Map<RadarType,Set<String>>):String=allSlots.foldIndexed(BigInteger.ZERO){i,n,(type,id)->if(id in rules[type].orEmpty())n.setBit(i)else n}.toString(16)
    fun rules(key:String):Map<RadarType,Set<String>> {
        val mask=BigInteger(key,16);require(mask.signum()>=0&&mask<total)
        return RadarType.entries.associateWith{type->allSlots.mapIndexedNotNull{i,p->if(p.first==type&&mask.testBit(i))p.second else null}.toSet()}
    }
    fun settings(rules:Map<RadarType,Set<String>> = defaultBtRules(),sectors:Set<StockSector> = StockSector.entries.toSet())=
        BtSettings(LocalDate.of(2026,8,1),LocalDate.of(2026,10,8),5000000.0,"",rules,sectors,maxHoldingStocks=25,targetNetPct=4.0)
    fun validate(s:BtSettings){
        s.validateTrading()
        // Session parameters are immutable once a run starts, not globally hardcoded.
        require(s.strategyVersion==4){"自動機器人需使用新版淨利及持倉策略"}
        require(s.start<=s.end){"測試結束日期不得早於開始日期"}
        require(s.capital.isFinite()&&s.capital>0){"本金須大於 0 元"}
        require(s.maxHoldingStocks!=null&&s.maxHoldingStocks>0){"最高持倉須至少 1 檔"}
        require(s.targetNetPct.isFinite()&&s.targetNetPct in 0.01..1000.0){"淨利下車須在 0.01%～1000%"}
        require(s.codes.isBlank()||s.codes.split(Regex("[,，\\s]+")).filter{it.isNotBlank()}.all{it.matches(Regex("[0-9]{4}"))}){"股號範圍需為四位股票代碼"}
        require(s.sectors.isNotEmpty())
        require(s.rules.keys==RadarType.entries.toSet())
        key(s.rules).let{require(rules(it)==s.rules)}
    }
    data class Candidate(val key:String,val cursor:BigInteger)
    /** Best-first one-bit neighbours, then an odd affine permutation visits every bit mask once. */
    fun required(key:String)=BigInteger(key,16).also{rules(key)}
    fun validateConstraints(required:String,forbidden:String){
        require(BtRobotSpace.required(required).and(BtRobotSpace.required(forbidden))==BigInteger.ZERO){"同一條件不能同時必選與排除"}
    }
    fun total(required:String,forbidden:String="0",legacy:Boolean=false,
              enabled:Set<RadarType> = RadarType.entries.toSet(),
              includeD:Boolean=true,includeCost:Boolean=true):BigInteger {
        validateConstraints(required,forbidden)
        val options=if(legacy)legacySlots else if(includeCost)allSlots else v4Slots
        val active=options.withIndex().filter{it.value.first in enabled&&
            (includeD||it.value.first!=RadarType.D_MAIN_FORCE)}
        val forced=BtRobotSpace.required(required).or(BtRobotSpace.required(forbidden))
        return BigInteger.ONE.shiftLeft(active.count{!forced.testBit(it.index)})
    }
    fun includes(key:String,required:String,forbidden:String="0",enabled:Set<RadarType> = RadarType.entries.toSet()):Boolean {
        validateConstraints(required,forbidden)
        val allowed=allSlots.foldIndexed(BigInteger.ZERO){i,mask,pair->if(pair.first in enabled)mask.setBit(i)else mask}
        val must=BtRobotSpace.required(required).and(allowed)
        val blocked=BtRobotSpace.required(forbidden).and(allowed)
        val candidate=BtRobotSpace.required(key)
        return candidate.andNot(allowed)==BigInteger.ZERO&&candidate.and(must)==must&&candidate.and(blocked)==BigInteger.ZERO
    }
    fun next(base:String,best:String?,cursor:BigInteger,seed:BigInteger,required:String="0",forbidden:String="0",
        legacy:Boolean=false,enabled:Set<RadarType> = RadarType.entries.toSet(),
        includeD:Boolean=true,includeCost:Boolean=true,seen:(String)->Boolean):Candidate? {
        val options=if(legacy)legacySlots else if(includeCost)allSlots else v4Slots
        val allowed=options.foldIndexed(BigInteger.ZERO){i,v,pair->
            if(pair.first in enabled&&(includeD||pair.first!=RadarType.D_MAIN_FORCE))v.setBit(i) else v
        }
        val total=total(required,forbidden,legacy,enabled,includeD,includeCost)
        val fixed=BtRobotSpace.required(required).and(allowed)
        val blocked=BtRobotSpace.required(forbidden).and(allowed)
        val free=options.indices.filter{allowed.testBit(it)&&!fixed.testBit(it)&&!blocked.testBit(it)}
        fun lock(k:String)=BigInteger(k,16).and(allowed).or(fixed).andNot(blocked).toString(16)
        fun expand(n:BigInteger)=free.foldIndexed(fixed){i,mask,bit->if(n.testBit(i))mask.setBit(bit)else mask}.toString(16)
        require(cursor.signum()>=0&&cursor<=total)
        val defaults=key(defaultBtRules())
        val starting=listOf(base,defaults)+RadarType.entries.map{type->key(RadarType.entries.associateWith{if(it==type)defaultBtRules().getValue(it)else emptySet()})}+listOf("0")
        for(k in starting.map(::lock).distinct())if(!seen(k))return Candidate(k,cursor)
        for(center in listOfNotNull(best,base).distinct())for(i in free){val k=lock(BigInteger(center,16).flipBit(i).toString(16));if(!seen(k))return Candidate(k,cursor)}
        var n=cursor
        val step=seed.shiftLeft(1).or(BigInteger.ONE).mod(total) // odd => bijection modulo a power of two
        while(n<total){val k=expand(n.multiply(step).add(seed).mod(total));n+=BigInteger.ONE;if(!seen(k))return Candidate(k,n)}
        return null
    }
}
