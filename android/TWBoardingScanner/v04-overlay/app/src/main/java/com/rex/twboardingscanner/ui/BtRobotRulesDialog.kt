package com.rex.twboardingscanner.ui

import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rex.twboardingscanner.backtest.BtRobotSpace
import com.rex.twboardingscanner.domain.*

internal object BtRobotRulesDialog {
    fun show(a:AppCompatActivity,key:String,forbidden:String="0",
             enabled:Set<RadarType> = RadarType.entries.toSet(),
             initialType:RadarType = RadarType.A_EARLY_BREAKOUT,
             dConfig:DPatternConfig=DPatternConfig.DEFAULT,fConfig:FPatternConfig=FPatternConfig.DEFAULT,
             onParameters:(DPatternConfig,FPatternConfig)->Unit={_,_->},
             bConfig:BPatternConfig=BPatternConfig.DEFAULT,onBParameters:(BPatternConfig)->Unit={},onSave:(String,String)->Unit){
        val numbers=RuleNumberFields(a,dConfig,fConfig,bConfig)
        BtRobotSpace.validateConstraints(key,forbidden)
        val excluded=BtRobotSpace.rules(forbidden).mapValues{it.value.toMutableSet()}
        val selected=BtRobotSpace.rules(key).mapValues{it.value.toMutableSet()}
        lateinit var dialog:androidx.appcompat.app.AlertDialog
        val root=NeonUi.vertical(a)
        val content=NeonUi.vertical(a).apply{setPadding(NeonUi.dp(a,14),NeonUi.dp(a,16),NeonUi.dp(a,14),NeonUi.dp(a,14))}
        content.addView(NeonUi.label(a,"機器人條件選單",20f,NeonUi.ink,true));content.addView(NeonUi.gap(a,10))
        val count=NeonUi.label(a,"",13f,NeonUi.cyan,true)
        fun summary(){val k=BtRobotSpace.key(selected);val e=BtRobotSpace.key(excluded);val on=selected.values.sumOf{it.size};val off=excluded.values.sumOf{it.size}
            count.text="✓ 必選 $on · ✕ 排除 $off · 自由可調\n候選 ${java.text.DecimalFormat("#,###").format(BtRobotSpace.total(k,e,enabled=enabled))} 組"
        }
        summary();content.addView(count)
        content.addView(NeonUi.label(a,"單點 ✓ 必選；快速雙點 ✕ 排除，再單點解除。空白為機器人自由測試；✕ 不加入任何組合。長按也可排除。",12f))
        val body=NeonUi.vertical(a);val tabs=NeonUi.radarRows(a,RadarType.entries.map{type->NeonUi.button(a,type.label){
            populate(a,body,numbers,type,selected.getValue(type),excluded.getValue(type)){summary()}
        }.apply{textSize=11f;setPadding(3,4,3,4);maxLines=2;minHeight=NeonUi.dp(a,44)}})
        content.addView(tabs)
        content.addView(NeonUi.button(a,"一鍵把 A 的必選／排除同步到 D（保留 D 原有六項）",NeonUi.mint){
            val aType=RadarType.A_EARLY_BREAKOUT
            val dType=RadarType.D_MAIN_FORCE
            selected.getValue(dType).removeAll(ScanConditions.A_RULE_IDS)
            excluded.getValue(dType).removeAll(ScanConditions.A_RULE_IDS)
            selected.getValue(dType).addAll(selected.getValue(aType).intersect(ScanConditions.A_RULE_IDS))
            excluded.getValue(dType).addAll(excluded.getValue(aType).intersect(ScanConditions.A_RULE_IDS))
            populate(a,body,numbers,dType,selected.getValue(dType),excluded.getValue(dType)){summary()}
            summary()
        }.apply{tag="robot-d-copy-a"})
        content.addView(body)
        populate(a,body,numbers,initialType,selected.getValue(initialType),excluded.getValue(initialType)){summary()}
        content.addView(NeonUi.label(a,"ABCDEF 各自設定；僅主頁/機器人亮燈的類型才會分析。儲存後另建條件組合測試批次（不再使用持倉×淨利網格）；排除只是停用條件，並非反向篩選。",11f,NeonUi.amber))
        content.addView(NeonUi.gap(a,10))
        val contentHeight=(a.resources.displayMetrics.heightPixels*0.60f).toInt()
        root.addView(ScrollView(a).apply{addView(content)},LinearLayout.LayoutParams(-1,contentHeight))
        root.addView(NeonUi.row(a,listOf(
            NeonUi.button(a,"全部自由",NeonUi.amber){runCatching{val d=numbers.readD();val f=numbers.readF();val b=numbers.readB();onBParameters(b);onParameters(d,f);onSave("0","0")}.onSuccess{dialog.dismiss()}.onFailure{Toast.makeText(a,it.message,Toast.LENGTH_LONG).show()}},
            NeonUi.button(a,"取消",NeonUi.muted){dialog.dismiss()},
            NeonUi.button(a,"儲存設定",NeonUi.mint){runCatching{val d=numbers.readD();val f=numbers.readF();val b=numbers.readB();onBParameters(b);onParameters(d,f);onSave(BtRobotSpace.key(selected),BtRobotSpace.key(excluded))}.onSuccess{dialog.dismiss()}.onFailure{Toast.makeText(a,it.message,Toast.LENGTH_LONG).show()}}.apply{tag="save-required-rules"}
        )),LinearLayout.LayoutParams(-1,NeonUi.dp(a,52)))
        dialog=MaterialAlertDialogBuilder(a).setBackground(NeonUi.panel(a,NeonUi.cyan)).setView(root).create();dialog.show()
    }
    private fun populate(a:AppCompatActivity,body:LinearLayout,numbers:RuleNumberFields,type:RadarType,selected:MutableSet<String>,excluded:MutableSet<String>,change:()->Unit){
        body.removeAllViews();body.addView(NeonUi.label(a,"${type.label} · 必選／排除",15f,NeonUi.cyan,true))
        if(type==RadarType.A_EARLY_BREAKOUT)AVideoPresets.add(a,body){ids->
            selected.clear();selected.addAll(ids);excluded.clear();populate(a,body,numbers,type,selected,excluded,change);change()
        }
        if(type==RadarType.B_DEEP_REVERSAL)BReversalPresets.add(a,body){ids->
            selected.clear();selected.addAll(ids);excluded.clear();populate(a,body,numbers,type,selected,excluded,change);change()
        }
        ScanConditions.forEditor(type).forEachIndexed{index,rule->
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
            numbers.add(body,type,rule.id)
        }
    }
}
