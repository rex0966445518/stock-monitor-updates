package com.rex.twboardingscanner.ui

import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rex.twboardingscanner.backtest.BtRobotSpace
import com.rex.twboardingscanner.domain.*

internal object BtRobotRulesDialog {
    fun show(a:AppCompatActivity,key:String,forbidden:String="0",
             enabled:Set<RadarType> = RadarType.entries.toSet(),onSave:(String,String)->Unit){
        BtRobotSpace.validateConstraints(key,forbidden)
        val excluded=BtRobotSpace.rules(forbidden).mapValues{it.value.toMutableSet()}
        val selected=BtRobotSpace.rules(key).mapValues{it.value.toMutableSet()}
        lateinit var dialog:androidx.appcompat.app.AlertDialog
        val root=NeonUi.vertical(a).apply{setPadding(NeonUi.dp(a,14),NeonUi.dp(a,16),NeonUi.dp(a,14),NeonUi.dp(a,14))}
        root.addView(NeonUi.label(a,"機器人條件選單",20f,NeonUi.ink,true));root.addView(NeonUi.gap(a,10))
        val count=NeonUi.label(a,"",13f,NeonUi.cyan,true)
        fun summary(){val k=BtRobotSpace.key(selected);val e=BtRobotSpace.key(excluded);val on=selected.values.sumOf{it.size};val off=excluded.values.sumOf{it.size}
            count.text="✓ 必選 $on · ✕ 排除 $off · 自由可調\n候選 ${java.text.DecimalFormat("#,###").format(BtRobotSpace.total(k,e,enabled=enabled))} 組"
        }
        summary();root.addView(count)
        root.addView(NeonUi.label(a,"單點 ✓ 必選；快速雙點 ✕ 排除，再單點解除。空白由機器人調整；✕ 不加入任何組合。長按也可排除。",12f))
        val body=NeonUi.vertical(a);val tabs=NeonUi.row(a,RadarType.entries.map{type->NeonUi.button(a,when(type){RadarType.A_EARLY_BREAKOUT->"A 起漲";RadarType.B_DEEP_REVERSAL->"B 反轉";RadarType.C_LONG_RED_VOLUME->"C 爆量";else->"D 主力"}){
            populate(a,body,type,selected.getValue(type),excluded.getValue(type)){summary()}
        }})
        root.addView(tabs)
        root.addView(NeonUi.button(a,"一鍵把 A 的必選／排除同步到 D（保留 D 原有六項）",NeonUi.mint){
            val aType=RadarType.A_EARLY_BREAKOUT
            val dType=RadarType.D_MAIN_FORCE
            selected.getValue(dType).removeAll(ScanConditions.A_RULE_IDS)
            excluded.getValue(dType).removeAll(ScanConditions.A_RULE_IDS)
            selected.getValue(dType).addAll(selected.getValue(aType))
            excluded.getValue(dType).addAll(excluded.getValue(aType))
            populate(a,body,dType,selected.getValue(dType),excluded.getValue(dType)){summary()}
            summary()
        }.apply{tag="robot-d-copy-a"})
        val scroll=ScrollView(a).apply{addView(body)};val height=minOf(240,(a.resources.displayMetrics.heightPixels/a.resources.displayMetrics.density).toInt()-500).coerceAtLeast(100)
        root.addView(scroll,LinearLayout.LayoutParams(-1,NeonUi.dp(a,height)))
        populate(a,body,RadarType.A_EARLY_BREAKOUT,selected.getValue(RadarType.A_EARLY_BREAKOUT),excluded.getValue(RadarType.A_EARLY_BREAKOUT)){summary()}
        root.addView(NeonUi.label(a,"ABCD 各自設定；僅主頁/機器人亮燈的類型才會分析。儲存後另建批次；排除只是停用條件，並非反向篩選。",11f,NeonUi.amber))
        root.addView(NeonUi.gap(a,10))
        root.addView(NeonUi.row(a,listOf(
            NeonUi.button(a,"全部自由",NeonUi.amber){onSave("0","0");dialog.dismiss()},
            NeonUi.button(a,"取消",NeonUi.muted){dialog.dismiss()},
            NeonUi.button(a,"儲存設定",NeonUi.mint){onSave(BtRobotSpace.key(selected),BtRobotSpace.key(excluded));dialog.dismiss()}.apply{tag="save-required-rules"}
        )),LinearLayout.LayoutParams(-1,NeonUi.dp(a,52)))
        dialog=MaterialAlertDialogBuilder(a).setBackground(NeonUi.panel(a,NeonUi.cyan)).setView(root).create();dialog.show()
    }
    private fun populate(a:AppCompatActivity,body:LinearLayout,type:RadarType,selected:MutableSet<String>,excluded:MutableSet<String>,change:()->Unit){
        body.removeAllViews();body.addView(NeonUi.label(a,"${type.name.take(1)} 區 · 必選／排除",15f,NeonUi.cyan,true))
        ScanConditions.forRadar(type).forEachIndexed{index,rule->
            if(type==RadarType.D_MAIN_FORCE&&index in setOf(0,6,17))
                body.addView(NeonUi.label(a,when(index){
                    0->"D 原有主力形態"
                    6->"A 同步技術條件"
                    else->"A 同步額外查核"
                },13f,NeonUi.amber,true))
            val state=when(rule.id){in selected->BtRuleChoice.Choice.REQUIRED;in excluded->BtRuleChoice.Choice.EXCLUDED;else->BtRuleChoice.Choice.FREE}
            body.addView(BtRuleChoice(a,rule.label,state){choice->
                selected-=rule.id;excluded-=rule.id
                when(choice){BtRuleChoice.Choice.REQUIRED->selected+=rule.id;BtRuleChoice.Choice.EXCLUDED->excluded+=rule.id;else->Unit}
                change()
            }.apply{tag="required-${type.name}-${rule.id}"})
        }
    }
}
