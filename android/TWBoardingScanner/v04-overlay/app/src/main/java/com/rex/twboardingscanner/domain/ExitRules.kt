package com.rex.twboardingscanner.domain

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class LossCompare(val label:String) { LT("小於"), LTE("小於等於"), GT("大於"), GTE("大於等於") }
enum class ExitMacd(val label:String) { DOWN("向下"), UP("向上") }

/** Null means omitted. A half-filled comparison is retained for editing but not evaluated. */
data class ExitRule(val days:Int?=null,val compare:LossCompare?=null,val lossPct:Double?=null,val macd:ExitMacd?=null,val maDays:Int?=null) {
    val active:Boolean get()=days!=null||(compare!=null&&lossPct!=null)||macd!=null||maDays!=null
    fun validate(){
        require(days==null||days in 0..36500){"留倉天數請填 0～36500 的整數"}
        require(lossPct==null||(lossPct.isFinite()&&lossPct in 0.0..1000.0)){"虧損幅度請填 0～1000 的數字"}
        require(maDays==null||maDays in 1..320){"日線天數請填 1～320 的整數"}
    }
    fun description():String {
        val parts=mutableListOf<String>()
        days?.let{parts+="留倉超過 $it 個日曆日"}
        if(compare!=null&&lossPct!=null)parts+="虧損幅度${compare.label} ${java.math.BigDecimal.valueOf(lossPct).stripTrailingZeros().toPlainString()}%"
        macd?.let{parts+="MACD DIF ${it.label}"}
        maDays?.let{parts+="收盤低於 $it 日均線"}
        return if(parts.isEmpty())"未啟用" else parts.joinToString(" 且 ")+" → 認賠賣出"
    }
}

object ExitRules {
    fun example()=listOf(ExitRule(15,LossCompare.LT,3.0,ExitMacd.DOWN,20))+List(4){ExitRule()}
    fun validate(rules:List<ExitRule>){require(rules.size<=5){"最多 5 組下車條件"};rules.forEach{it.validate()}}
    fun json(rules:List<ExitRule>):JSONArray {validate(rules);return JSONArray(rules.map{r->JSONObject()
        .put("days",r.days?:JSONObject.NULL).put("compare",r.compare?.name?:JSONObject.NULL)
        .put("lossPct",r.lossPct?:JSONObject.NULL).put("macd",r.macd?.name?:JSONObject.NULL).put("maDays",r.maDays?:JSONObject.NULL)})}
    fun read(a:JSONArray?):List<ExitRule> {
        if(a==null)return emptyList()
        require(a.length()<=5)
        return List(a.length()){i->val o=a.getJSONObject(i)
            fun integer(key:String):Int? {if(o.isNull(key))return null;val n=o.getDouble(key);require(n.isFinite()&&n==kotlin.math.floor(n)&&n in 0.0..36500.0);return n.toInt()}
            ExitRule(integer("days"),if(o.isNull("compare"))null else LossCompare.valueOf(o.getString("compare")),
                if(o.isNull("lossPct"))null else o.getDouble("lossPct"),if(o.isNull("macd"))null else ExitMacd.valueOf(o.getString("macd")),integer("maDays"))
        }.also(::validate)
    }
    fun summary(rules:List<ExitRule>)=rules.mapIndexedNotNull{i,r->if(r.active)"第 ${i+1} 組：${r.description()}" else null}.joinToString("\n").ifBlank{"下車認賠條件：未啟用"}

    /** All supplied predicates in a row are AND; rows are OR. Only loss-making holdings qualify. */
    fun match(rules:List<ExitRule>,entryDate:LocalDate,signalDate:LocalDate,netPnlPct:Double,bars:List<DailyBar>):Int? {
        validate(rules)
        if(!netPnlPct.isFinite()||netPnlPct>=0||signalDate<entryDate)return null
        val known=bars.filter{RuleMetrics.tradingDate(it.time)<=signalDate}.sortedBy{it.time}.distinctBy{RuleMetrics.tradingDate(it.time)}
        val current=known.lastOrNull()?.takeIf{RuleMetrics.tradingDate(it.time)==signalDate}
        val loss=-netPnlPct
        val days=ChronoUnit.DAYS.between(entryDate,signalDate)
        val calculator=TechnicalCalculator()
        val difDelta by lazy {
            val closes=known.map{it.close}
            val now=calculator.macd(closes);val prior=calculator.macd(closes.dropLast(1))
            if(now==null||prior==null)null else now.dif-prior.dif
        }
        return rules.indexOfFirst{r->r.active &&
            (r.days==null||days>r.days) &&
            (r.compare==null||r.lossPct==null||when(r.compare){LossCompare.LT->loss<r.lossPct-1e-9;LossCompare.LTE->loss<=r.lossPct+1e-9;LossCompare.GT->loss>r.lossPct+1e-9;LossCompare.GTE->loss>=r.lossPct-1e-9}) &&
            (r.macd==null||(current!=null&&difDelta?.let{d->d.isFinite()&&if(r.macd==ExitMacd.DOWN)d<0 else d>0}==true)) &&
            (r.maDays==null||(current!=null&&known.size>=r.maDays&&known.takeLast(r.maDays).all{it.close.isFinite()&&it.close>0}&&current.close<known.takeLast(r.maDays).map{it.close}.average()))
        }.takeIf{it>=0}
    }
}
