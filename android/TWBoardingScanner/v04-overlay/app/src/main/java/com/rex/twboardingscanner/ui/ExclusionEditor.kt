package com.rex.twboardingscanner.ui

import android.content.Context
import android.widget.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rex.twboardingscanner.domain.*

internal object ExclusionEditor{
    fun fields(c:Context,box:LinearLayout,type:RadarType,selected:Set<String>):()->Set<String>{
        box.addView(NeonUi.label(c,"${type.name.take(1)} 排除區 · 命中任一勾選規則即移除",15f,NeonUi.pink,true))
        box.addView(NeonUi.label(c,"此處是反向篩選，與機器人的條件停用不同。MACD＝EMA9訊號線；RSI14採Wilder。已勾規則缺資料則待查核、暫不入選。",12f))
        val options=ExclusionConditions.forRadar(type)
        val checks=options.map{rule->CheckBox(c).apply{text=rule.label;setTextColor(NeonUi.ink);isChecked=rule.id in selected;tag="exclusion-${type.name}-${rule.id}";minHeight=NeonUi.dp(c,48)}.also{box.addView(it)}}
        return {options.filterIndexed{i,_->checks[i].isChecked}.map{it.id}.toSet()}
    }
    fun show(c:Context,type:RadarType,selected:Set<String>,save:(Set<String>)->Unit){
        val box=NeonUi.vertical(c).apply{setPadding(NeonUi.dp(c,16),NeonUi.dp(c,16),NeonUi.dp(c,16),NeonUi.dp(c,16))}
        val read=fields(c,box,type,selected)
        MaterialAlertDialogBuilder(c).setTitle("${type.name.take(1)} 區排除規則").setView(ScrollView(c).apply{addView(box)})
            .setNegativeButton("取消",null).setPositiveButton("儲存排除"){_,_->save(read())}.show()
    }
}
