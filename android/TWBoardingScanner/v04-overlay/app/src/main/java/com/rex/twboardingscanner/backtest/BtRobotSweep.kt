package com.rex.twboardingscanner.backtest

import org.json.JSONObject
import java.math.BigInteger

/**
 * Finite, deterministic exhaustive sweep of two entry/exit parameters.
 * ABC, stock universe, dates, capital and risk rules remain frozen for one session.
 * The traversal index and every completed report are committed together by BtRobotStore.
 */
data class BtRobotSweep(
    val holdingFrom:Int=5,
    val holdingTo:Int=50,
    val profitFrom:Int=1,
    val profitTo:Int=30
) {
    init {
        require(holdingFrom in 5..50 && holdingTo in holdingFrom..50){"持倉範圍須在 5～50 檔，起點不可大於終點"}
        require(profitFrom in 1..30 && profitTo in profitFrom..30){"淨利範圍須在 1%～30%，起點不可大於終點"}
    }
    val profitCount:Int get()=profitTo-profitFrom+1
    val total:Int get()=(holdingTo-holdingFrom+1)*profitCount
    data class Point(val holding:Int,val profit:Int) {
        val key:String get()="grid_${holding}_${profit}"
        val label:String get()="持倉 ${holding} 檔 · 淨利下車 ${profit}%"
    }
    fun at(index:Int):Point {
        require(index in 0 until total){"測試進度已到範圍終點"}
        return Point(holdingFrom+index/profitCount,profitFrom+index%profitCount)
    }
    fun parseKey(key:String):Point {
        val m=Regex("^grid_([0-9]{1,2})_([0-9]{1,2})$").matchEntire(key)
            ?:throw IllegalArgumentException("非參數範圍測試代碼")
        val h=m.groupValues[1].toInt();val p=m.groupValues[2].toInt()
        require(h in holdingFrom..holdingTo && p in profitFrom..profitTo && at((h-holdingFrom)*profitCount+p-profitFrom).key==key){
            "測試代碼超出本批次參數範圍"
        }
        return Point(h,p)
    }
    fun index(key:String):Int = parseKey(key).let{(it.holding-holdingFrom)*profitCount+it.profit-profitFrom}
    fun next(tested:Long):BtRobotSpace.Candidate? {
        require(tested>=0&&tested<=total.toLong()){"已測試數量不符"}
        return if(tested==total.toLong())null else BtRobotSpace.Candidate(at(tested.toInt()).key,BigInteger.valueOf(tested+1))
    }
    fun settings(base:BtSettings,key:String):BtSettings{
        val point=parseKey(key)
        return base.copy(maxHoldingStocks=point.holding,targetNetPct=point.profit.toDouble())
    }
    fun json():JSONObject=JSONObject().put("version",1).put("holdingFrom",holdingFrom).put("holdingTo",holdingTo)
        .put("profitFrom",profitFrom).put("profitTo",profitTo)
    companion object {
        fun read(raw:JSONObject?):BtRobotSweep?{
            if(raw==null)return null
            require(raw.getInt("version")==1){"參數網格版本不相容"}
            return BtRobotSweep(raw.getInt("holdingFrom"),raw.getInt("holdingTo"),
                raw.getInt("profitFrom"),raw.getInt("profitTo"))
        }
        fun parse(a:String,b:String,c:String,d:String):BtRobotSweep{
            val h1=a.trim().toIntOrNull()?:throw IllegalArgumentException("請輸入持倉起點整數")
            val h2=b.trim().toIntOrNull()?:throw IllegalArgumentException("請輸入持倉終點整數")
            val p1=c.trim().removeSuffix("%").trim().toIntOrNull()?:throw IllegalArgumentException("請輸入淨利起點整數百分比")
            val p2=d.trim().removeSuffix("%").trim().toIntOrNull()?:throw IllegalArgumentException("請輸入淨利終點整數百分比")
            return BtRobotSweep(h1,h2,p1,p2)
        }
    }
}
