package com.rex.twboardingscanner.backtest

import com.rex.twboardingscanner.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

enum class SettingChangeKind(val label:String){ADDED("新增"),REMOVED("移除"),CHANGED("修改")}
data class SettingChange(val kind:SettingChangeKind,val text:String)
data class SettingsDifference(val changes:List<SettingChange>,val unavailable:List<String>)

/** Compare the two archived snapshots, never today's preferences or recomputed results. */
object BacktestSettingsDiff {
    private fun strings(a:JSONArray?):Set<String>?=a?.let{runCatching{(0 until it.length()).map{i->it.getString(i)}.toSet()}.getOrNull()}
    private fun scalar(o:JSONObject?,key:String,nullText:String?=null):String?{
        if(o==null||!o.has(key))return null
        if(o.isNull(key))return nullText
        val value=o.get(key)
        return if(value is Number)runCatching{BigDecimal(value.toString()).stripTrailingZeros().toPlainString()}.getOrNull() else value.toString()
    }
    private fun label(s:JSONObject,type:RadarType,id:String):String =
        s.optJSONObject("ruleLabelMap")?.optJSONObject(type.name)?.optString(id)?.takeIf{it.isNotBlank()}
            ?:if(s.optString("rulesVersion")==ScanConditions.VERSION)ScanConditions.forRadar(type).firstOrNull{it.id==id}?.label?:"條件代碼 $id" else "條件代碼 $id"
    private fun pct(value:String)=runCatching{BigDecimal(value).multiply(BigDecimal(100)).stripTrailingZeros().toPlainString()+"%"}.getOrDefault(value)
    private fun oldTrading(s:JSONObject,key:String):String? = scalar(s,key,if(key=="maxHoldingStocks")"不限" else null)
        ?:if(s.optInt("strategyVersion") in 2..3)when(key){"maxHoldingStocks"->"不限";"targetNetPct"->"3";else->null}else null

    fun compare(current:JSONObject?,previous:JSONObject?):SettingsDifference{
        if(current==null||previous==null)return SettingsDifference(emptyList(),listOf("條件快照未完整保存"))
        val changes=mutableListOf<SettingChange>();val missing=linkedSetOf<String>()
        fun change(kind:SettingChangeKind,text:String){changes+=SettingChange(kind,text)}
        fun value(title:String,before:String?,after:String?,format:(String)->String={it}){
            if(before==null||after==null){missing+=title;return}
            if(before!=after)change(SettingChangeKind.CHANGED,"$title：${format(before)} → ${format(after)}")
        }
        fun sets(title:String,before:Set<String>?,after:Set<String>?,oldLabel:(String)->String={it},newLabel:(String)->String=oldLabel){
            if(before==null||after==null){missing+=title;return}
            (after-before).sorted().forEach{change(SettingChangeKind.ADDED,"$title · ${newLabel(it)}")}
            (before-after).sorted().forEach{change(SettingChangeKind.REMOVED,"$title · ${oldLabel(it)}")}
        }
        RadarType.entries.forEach{type->
            val before=strings(previous.optJSONObject("rules")?.optJSONArray(type.name))
            val after=strings(current.optJSONObject("rules")?.optJSONArray(type.name))
            val title="${type.name.take(1)} 區"
            sets(title,before,after,{label(previous,type,it)},{label(current,type,it)})
            if(before!=null&&after!=null)(before intersect after).sorted().forEach{id->
                // Definition changes are reliable only when both runs saved ID-keyed labels.
                val a=previous.optJSONObject("ruleLabelMap")?.optJSONObject(type.name)?.optString(id)?.takeIf{it.isNotBlank()}
                val b=current.optJSONObject("ruleLabelMap")?.optJSONObject(type.name)?.optString(id)?.takeIf{it.isNotBlank()}
                if(a!=null&&b!=null&&a!=b)change(SettingChangeKind.CHANGED,"$title · $a → $b")
            }
        }
        sets("產業",strings(previous.optJSONArray("sectors")),strings(current.optJSONArray("sectors")),{id->StockSector.entries.firstOrNull{it.name==id}?.label?:id})
        fun codes(s:JSONObject)=scalar(s,"codes")?.split(Regex("[,，\\s]+"))?.filter{it.isNotBlank()}?.toSet()
        val oldCodes=codes(previous);val newCodes=codes(current)
        if(oldCodes!=null&&newCodes!=null&&oldCodes.isNotEmpty()&&newCodes.isNotEmpty())sets("股票範圍",oldCodes,newCodes)
        else value("股票範圍",oldCodes?.sorted()?.joinToString("、")?.ifBlank{"全部"},newCodes?.sorted()?.joinToString("、")?.ifBlank{"全部"})
        value("回測起日",scalar(previous,"start"),scalar(current,"start"))
        value("回測迄日",scalar(previous,"end"),scalar(current,"end"))
        value("初始本金",scalar(previous,"capital"),scalar(current,"capital")){"$it 元"}
        value("最高持倉",oldTrading(previous,"maxHoldingStocks"),oldTrading(current,"maxHoldingStocks")){if(it=="不限")it else "$it 檔"}
        value("獲利賣出",oldTrading(previous,"targetNetPct"),oldTrading(current,"targetNetPct")){"$it%（扣費稅）"}
        val oldPolicy=previous.optJSONObject("stockPolicy");val newPolicy=current.optJSONObject("stockPolicy")
        if(oldPolicy==null||newPolicy==null)missing+="禁買／限價設定" else{
            sets("禁買名單",strings(oldPolicy.optJSONArray("banned")),strings(newPolicy.optJSONArray("banned")))
            value("最高股價",scalar(oldPolicy,"ceiling","不限"),scalar(newPolicy,"ceiling","不限")){if(it=="不限")it else "$it 元"}
            StockScope.entries.forEach{scope->sets("${scope.label}限價放行",strings(oldPolicy.optJSONObject("overrides")?.optJSONArray(scope.name)),strings(newPolicy.optJSONObject("overrides")?.optJSONArray(scope.name)))}
        }
        val oldExit=previous.optJSONArray("exitRules");val newExit=current.optJSONArray("exitRules")
        if(oldExit==null||newExit==null)missing+="下車條件" else{
            val fields=linkedMapOf("days" to "留倉天數", "compare" to "虧損比較", "lossPct" to "虧損幅度", "macd" to "MACD 方向", "maDays" to "跌破均線")
            fun field(o:JSONObject?,key:String)=scalar(o,key)?.takeIf{it.isNotBlank()}
            fun display(key:String,v:String)=when(key){
                "days"->"超過 $v 個日曆日";"compare"->LossCompare.entries.firstOrNull{it.name==v}?.label?:v
                "lossPct"->"$v%";"macd"->ExitMacd.entries.firstOrNull{it.name==v}?.label?:v;"maDays"->"$v 日線";else->v
            }
            fun description(o:JSONObject?)=fields.mapNotNull{(k,v)->field(o,k)?.let{"$v ${display(k,it)}"}}.joinToString("、")
            repeat(maxOf(oldExit.length(),newExit.length())){i->
                val before=oldExit.optJSONObject(i);val after=newExit.optJSONObject(i);val a=description(before);val b=description(after)
                val title="下車第 ${i+1} 組"
                when{
                    a.isEmpty()&&b.isNotEmpty()->change(SettingChangeKind.ADDED,"$title · $b")
                    a.isNotEmpty()&&b.isEmpty()->change(SettingChangeKind.REMOVED,"$title · $a")
                    a!=b->fields.forEach{(key,name)->
                        val x=field(before,key);val y=field(after,key)
                        if(x!=y)when{
                            x==null->change(SettingChangeKind.ADDED,"$title／$name · ${display(key,y!!)}")
                            y==null->change(SettingChangeKind.REMOVED,"$title／$name · ${display(key,x)}")
                            else->change(SettingChangeKind.CHANGED,"$title／$name：${display(key,x)} → ${display(key,y)}")
                        }
                    }
                }
            }
        }
        value("保本出場天數",scalar(previous,"breakEvenAfterCalendarDays","未啟用"),scalar(current,"breakEvenAfterCalendarDays","未啟用")){if(it=="未啟用")it else "超過 $it 個日曆日"}
        val costFields=linkedMapOf("buyFeeRate" to "買入手續費率","sellFeeRate" to "賣出手續費率","minimumFee" to "最低手續費","sellTaxRate" to "賣出稅率","slippageRate" to "滑價率")
        val oldCost=previous.optJSONObject("costModel");val newCost=current.optJSONObject("costModel")
        if(oldCost==null||newCost==null)missing+="交易費稅模型" else costFields.forEach{(key,title)->value(title,scalar(oldCost,key),scalar(newCost,key)){if(key=="minimumFee")"$it 元" else pct(it)}}
        val oldRebate=previous.optJSONObject("rebateModel");val newRebate=current.optJSONObject("rebateModel")
        when{
            !previous.has("rebateModel")||!current.has("rebateModel")->missing+="折讓金設定"
            oldRebate==null&&newRebate!=null->change(SettingChangeKind.ADDED,"折讓金估算")
            oldRebate!=null&&newRebate==null->change(SettingChangeKind.REMOVED,"折讓金估算")
            oldRebate!=null&&newRebate!=null->{
                value("折讓成交門檻",scalar(oldRebate,"threshold"),scalar(newRebate,"threshold")){"$it 元"}
                listOf("lowRate" to "低額折讓率","highRate" to "高額折讓率","basis" to "折讓計算方式").forEach{(key,title)->value(title,scalar(oldRebate,key),scalar(newRebate,key)){if(key=="basis")it else pct(it)}}
            }
        }
        value("持倉計算方式",scalar(previous,"holdingLimitBasis"),scalar(current,"holdingLimitBasis"))
        value("名單套用區域",scalar(previous,"stockScope"),scalar(current,"stockScope")){v->StockScope.entries.firstOrNull{it.name==v}?.label?:v}
        value("策略版本",scalar(previous,"strategyVersion"),scalar(current,"strategyVersion"))
        value("條件定義版本",scalar(previous,"rulesVersion"),scalar(current,"rulesVersion"))
        return SettingsDifference(changes,missing.toList())
    }
}
