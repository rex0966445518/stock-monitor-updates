package com.rex.twboardingscanner.ui

import android.content.Context
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rex.twboardingscanner.domain.GPatternConfig

/**
 * G3–G5 are holding-management switches, NOT extra AND predicates in the entry scanner.
 * G1/G2 and entry filters are shown in the normal radar checkbox dialog.
 */
internal object GPatternEditor {
    class Controls(private val c:Context, private val parent:LinearLayout, initial:GPatternConfig) {
        private fun tick(text:String,checked:Boolean,id:String)=CheckBox(c).apply{
            this.text=text;isChecked=checked;tag=id;textSize=14f;minHeight=NeonUi.dp(c,42)
        }.also{parent.addView(it)}
        private fun field(text:String,value:Number,key:String,integer:Boolean=false)=EditText(c).apply{
            setText(value.toString());setSingleLine();setSelectAllOnFocus(true);tag=key
            inputType=InputType.TYPE_CLASS_NUMBER or if(integer)0 else InputType.TYPE_NUMBER_FLAG_DECIMAL
        }.also {
            val row=NeonUi.row(c,listOf(NeonUi.label(c,text,12f,NeonUi.ink),NeonUi.field(it)))
            parent.addView(row)
        }
        init {
            parent.addView(NeonUi.label(c,"G3～G5｜持股管理（不會限制 G1/G2 的入選）",15f,NeonUi.amber,true))
            parent.addView(NeonUi.label(c,"日K回測以「前一日收盤確診，下一交易日開盤成交」處理；缺少 13:20 逐筆資料時，不會偽造尾盤成交。",11f,NeonUi.ink))
        }
        private val g3=tick("☑ G3 乖離過大且獲利達標 → 減碼一次",initial.g3Enabled,"g3-enabled")
        private val g3Dist=field("高於5MA至少（%）",initial.g3DistancePct,"g3-dist")
        private val g3Profit=field("持股獲利至少（%）",initial.g3ProfitPct,"g3-profit")
        private val g3Sell=field("減碼目前股數（%）",initial.g3SellPct,"g3-sell")
        private val g3Rebuy=tick("☑ G3 回踩5MA後，回補 G3 賣出的股數",initial.g3RebuyEnabled,"g3-rebuy")
        private val g3RebuyMax=field("回補時高於5MA最多（%）",initial.g3RebuyMaxPct,"g3-rebuy-max")
        private val g4=tick("☑ G4 前日收盤跌破5MA → 次日開盤減碼一次",initial.g4Enabled,"g4-enabled")
        private val g4Sell=field("減碼目前剩餘股數（%）",initial.g4SellPct,"g4-sell")
        private val g4Rebuy=tick("☑ G4 收盤重新站上5MA → 次日回補",initial.g4RebuyEnabled,"g4-rebuy")
        private val g5=tick("☑ G5 連續 N 日收盤低於5MA → 全數出清",initial.g5Enabled,"g5-enabled")
        private val g5Days=field("連續跌破天數（1～20）",initial.g5Days,"g5-days",true)
        private val stop=tick("☐ 額外風控：虧損達門檻 → 全數出清",initial.stopEnabled,"g-stop-enabled")
        private val stopPct=field("額外虧損門檻（%）",initial.stopLossPct,"g-stop-pct")
        fun read(base:GPatternConfig):GPatternConfig=base.copy(
            g3Enabled=g3.isChecked,g3DistancePct=g3Dist.text.toString().toDouble(),
            g3ProfitPct=g3Profit.text.toString().toDouble(),g3SellPct=g3Sell.text.toString().toDouble(),
            g3RebuyEnabled=g3Rebuy.isChecked,g3RebuyMaxPct=g3RebuyMax.text.toString().toDouble(),
            g4Enabled=g4.isChecked,g4SellPct=g4Sell.text.toString().toDouble(),g4RebuyEnabled=g4Rebuy.isChecked,
            g5Enabled=g5.isChecked,g5Days=g5Days.text.toString().toInt(),stopEnabled=stop.isChecked,
            stopLossPct=stopPct.text.toString().toDouble()
        ).validated()
    }
    fun add(c:Context,parent:LinearLayout,initial:GPatternConfig)=Controls(c,parent,initial)
    fun show(c:Context,initial:GPatternConfig,onSave:(GPatternConfig)->Unit) {
        val body=NeonUi.vertical(c).apply{setPadding(16,12,16,12)}
        val controls=add(c,body,initial)
        val dialog=MaterialAlertDialogBuilder(c).setTitle("G｜主力大陽線・5MA 波段管理")
            .setView(ScrollView(c).apply{addView(body)})
            .setNegativeButton("取消",null).setPositiveButton("儲存",null).create()
        dialog.setOnShowListener{
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                runCatching{controls.read(initial)}.onSuccess{onSave(it);dialog.dismiss()}
                    .onFailure{Toast.makeText(c,it.message?:"請填有效數字",Toast.LENGTH_LONG).show()}
            }
        }
        dialog.show()
    }
}
