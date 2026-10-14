package com.rex.twboardingscanner.backtest

import com.rex.twboardingscanner.domain.*
import java.math.BigInteger
import org.json.JSONObject

/** Fixed must-haves plus ONE free checkbox, never a cumulative combination. */
object BtRobotSingleOption {
    fun active(s:JSONObject?)=s!=null&&s.optInt("searchVersion",0)==9&&s.optJSONObject("sweep")==null
    private fun fixed(required:String,enabled:Set<RadarType>):BigInteger = BtRobotSpace.required(required).and(
        BtRobotSpace.allSlots.foldIndexed(BigInteger.ZERO){i,m,p->if(p.first in enabled)m.setBit(i)else m})
    fun keys(required:String,forbidden:String,enabled:Set<RadarType>):List<String> {
        BtRobotSpace.validateConstraints(required,forbidden)
        val must=fixed(required,enabled);val blocked=BtRobotSpace.required(forbidden)
        val free=BtRobotSpace.allSlots.withIndex().filter{it.value.first in enabled&&!must.testBit(it.index)&&!blocked.testBit(it.index)}
            .sortedWith(compareBy({it.value.first.ordinal},{it.index}))
        return if(free.isEmpty())if(must.signum()>0)listOf(must.toString(16))else emptyList()
            else free.map{must.setBit(it.index).toString(16)}
    }
    fun total(required:String,forbidden:String,enabled:Set<RadarType>)=BigInteger.valueOf(keys(required,forbidden,enabled).size.toLong())
    fun next(required:String,forbidden:String,enabled:Set<RadarType>,cursor:BigInteger,seen:(String)->Boolean):BtRobotSpace.Candidate? {
        val keys=keys(required,forbidden,enabled)
        require(cursor>=BigInteger.ZERO&&cursor<=BigInteger.valueOf(keys.size.toLong()))
        for(i in cursor.toInt() until keys.size)if(!seen(keys[i]))return BtRobotSpace.Candidate(keys[i],BigInteger.valueOf(i+1L))
        return null
    }
    fun option(required:String,key:String,enabled:Set<RadarType>):JSONObject? {
        val extra=BtRobotSpace.required(key).andNot(fixed(required,enabled))
        if(extra.bitCount()!=1)return null
        val (type,id)=BtRobotSpace.allSlots[extra.lowestSetBit]
        val label=ScanConditions.forRadar(type).first{it.id==id}.label
        return JSONObject().put("radar",type.name).put("id",id).put("label",label).put("radarLabel",type.label)
    }
    fun label(required:String,key:String,enabled:Set<RadarType>):String = option(required,key,enabled)?.let{
        "本輪新增：${it.getString("radarLabel")} · ${it.getString("label")}"
    }?:"基準測試：全部固定必選條件（無留空選項）"
}
