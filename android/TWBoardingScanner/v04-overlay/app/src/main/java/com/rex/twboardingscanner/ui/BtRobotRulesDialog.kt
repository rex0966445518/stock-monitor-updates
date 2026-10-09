package com.rex.twboardingscanner.ui

import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rex.twboardingscanner.backtest.BtRobotSpace
import com.rex.twboardingscanner.domain.*

internal object BtRobotRulesDialog {
    fun show(a:AppCompatActivity,key:String,onSave:(String)->Unit){
        val selected=BtRobotSpace.rules(key).mapValues{it.value.toMutableSet()}
        val root=NeonUi.vertical(a).apply{setPadding(NeonUi.dp(a,16),NeonUi.dp(a,8),NeonUi.dp(a,16),0)}
        val count=NeonUi.label(a,"",13f,NeonUi.cyan,true)
        fun summary(){val k=BtRobotSpace.key(selected);count.text="必選 ${selected.values.sumOf{it.size}} 項 · 自由 ${65-selected.values.sumOf{it.size}} 項\n候選 ${java.text.DecimalFormat("#,###").format(BtRobotSpace.total(k))} 組"}
        summary();root.addView(count)
        root.addView(NeonUi.label(a,"勾選＝每個組合必須保留；未勾選＝交給機器人增減。修改後另建批次，沿用行情與條件快取。",12f))
        val body=NeonUi.vertical(a);val tabs=NeonUi.row(a,RadarType.entries.map{type->NeonUi.button(a,when(type){RadarType.A_EARLY_BREAKOUT->"A 起漲";RadarType.B_DEEP_REVERSAL->"B 反轉";else->"C 爆量"}){
            populate(a,body,type,selected.getValue(type)){summary()}
        }})
        root.addView(tabs)
        val scroll=ScrollView(a).apply{addView(body)};root.addView(scroll,LinearLayout.LayoutParams(-1,NeonUi.dp(a,260)))
        populate(a,body,RadarType.A_EARLY_BREAKOUT,selected.getValue(RadarType.A_EARLY_BREAKOUT)){summary()}
        root.addView(NeonUi.label(a,"財報／法人缺歷史資料時仍會阻擋入選。各區的必選條件獨立設定。",11f,NeonUi.amber))
        MaterialAlertDialogBuilder(a).setTitle("機器人必選條件").setView(root).setNegativeButton("取消",null)
            .setNeutralButton("全部自由"){_,_->onSave("0")}.setPositiveButton("儲存必選"){_,_->onSave(BtRobotSpace.key(selected))}.show()
    }
    private fun populate(a:AppCompatActivity,body:LinearLayout,type:RadarType,selected:MutableSet<String>,change:()->Unit){
        body.removeAllViews();body.addView(NeonUi.label(a,"${type.name.take(1)} 區 · 必選條件",15f,NeonUi.cyan,true))
        ScanConditions.forRadar(type).forEach{rule->body.addView(CheckBox(a).apply{
            text=rule.label;textSize=13f;setTextColor(NeonUi.ink);isChecked=rule.id in selected;tag="required-${type.name}-${rule.id}"
            setOnCheckedChangeListener{_,checked->if(checked)selected+=rule.id else selected-=rule.id;change()}
        })}
    }
}
