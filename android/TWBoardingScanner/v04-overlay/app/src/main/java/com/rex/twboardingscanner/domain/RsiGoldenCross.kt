package com.rex.twboardingscanner.domain

import java.util.Locale

/** Independent RSI5/RSI10 with Wilder smoothing; existing RSI14 rules are unchanged. */
object RsiGoldenCross{
    const val ID="rsi_below35_golden_cross"
    data class Evidence(val short:Double,val long:Double,val previousShort:Double,val previousLong:Double){
        val passes get()=short<35.0&&previousShort<=previousLong&&short>long&&short>previousShort
        fun format()=String.format(Locale.TAIWAN,
            "RSI 低檔黃金交叉（Wilder）\nRSI(5) %.2f → %.2f · RSI(10) %.2f → %.2f\nRSI(5)<35、由下向上突破RSI(10)且RSI(5)上升：%s",
            previousShort,short,previousLong,long,if(passes)"通過" else "未通過")
    }
    fun inspect(closes:List<Double>):Evidence?{
        if(closes.size<12||closes.any{!it.isFinite()||it<=0.0})return null
        val short=values(closes,5);val long=values(closes,10)
        return Evidence(short.last(),long.last(),short[short.lastIndex-1],long[long.lastIndex-1])
    }
    internal fun values(closes:List<Double>,period:Int):List<Double>{
            var gain=0.0;var loss=0.0
            for(i in 1..period){val delta=closes[i]-closes[i-1];gain+=maxOf(delta,0.0);loss+=maxOf(-delta,0.0)}
            gain/=period;loss/=period
            fun value()=if(loss==0.0){if(gain==0.0)50.0 else 100.0}else 100.0-100.0/(1.0+gain/loss)
            val out=mutableListOf(value())
            for(i in period+1 until closes.size){
                val delta=closes[i]-closes[i-1]
                gain=(gain*(period-1)+maxOf(delta,0.0))/period
                loss=(loss*(period-1)+maxOf(-delta,0.0))/period
                out+=value()
            }
            return out
        }

}
