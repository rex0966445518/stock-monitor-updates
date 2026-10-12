package com.rex.twboardingscanner.domain

import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

data class EPatternConfig(val tolerancePct:Double=0.3,val includeCross:Boolean=true){
    fun validated():EPatternConfig{
        require(tolerancePct.isFinite()&&tolerancePct in 0.001..2.0){"E 線距請填 0.001～2%"}
        return this
    }
    fun json()=JSONObject().put("tolerancePct",tolerancePct).put("includeCross",includeCross)
    companion object{
        val DEFAULT=EPatternConfig()
        fun read(o:JSONObject?):EPatternConfig=if(o==null)DEFAULT else
            EPatternConfig(o.getDouble("tolerancePct"),o.getBoolean("includeCross")).validated()
    }
}

/** MACD here means EMA9(DIF), the signal line, not the histogram. */
object EPattern{
    const val EVENT="e_dif_touch"
    const val CROSS="e_cross_only"
    data class Evidence(val dif:Double,val signal:Double,val previousDif:Double,
        val previousSignal:Double,val close:Double,val config:EPatternConfig){
        val gap get()=dif-signal
        val previousGap get()=previousDif-previousSignal
        val distancePct get()=abs(gap)/close*100.0
        val rising get()=dif>previousDif
        val crossed get()=previousGap<=0.0&&gap>0.0
        val passes get()=rising&&previousGap<=0.0&&gap>previousGap&&
            distancePct<=config.tolerancePct+1e-12&& (gap<=0.0||config.includeCross)
        fun format()=String.format(Locale.TAIWAN,
            "E DIF 向上接觸 MACD（12,26,9）\nDIF %.4f → %.4f · MACD %.4f → %.4f\nDIF−MACD %+.4f · 線距／收盤價 %.3f%%（門檻 %.3f%%）\n%s；%s",
            previousDif,dif,previousSignal,signal,gap,distancePct,config.tolerancePct,
            if(crossed)"本日向上交叉" else "由下方靠近訊號線",
            if(passes)"通過" else "未符合向上接觸條件")
    }
    fun inspect(closes:List<Double>,config:EPatternConfig=EPatternConfig.DEFAULT):Evidence?{
        config.validated()
        if(closes.size<31||closes.any{!it.isFinite()||it<=0.0})return null
        val calc=TechnicalCalculator()
        val now=calc.macd(closes)?:return null
        val previous=calc.macd(closes.dropLast(1))?:return null
        return Evidence(now.dif,now.signal,previous.dif,previous.signal,closes.last(),config)
    }
}
