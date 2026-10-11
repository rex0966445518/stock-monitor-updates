package com.rex.twboardingscanner.ui

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rex.twboardingscanner.domain.DPatternConfig

/** Same numeric D thresholds in scanner, historical backtest and robot snapshots. */
internal object DPatternEditor {
    fun show(c:Context,initial:DPatternConfig,onSave:(DPatternConfig)->Unit){
        val box=NeonUi.vertical(c).apply{setPadding(NeonUi.dp(c,16),NeonUi.dp(c,12),
            NeonUi.dp(c,16),NeonUi.dp(c,12))}
        box.addView(NeonUi.label(c,"D 主力進場 · 六項證據的可調門檻",16f,NeonUi.cyan,true))
        box.addView(NeonUi.label(c,"前三種事件共用最近 N 個交易日範圍；所有數值會完整保存於本次條件快照。",12f))
        val fields=linkedMapOf<String,EditText>()
        fun row(title:String,key:String,value:String,decimal:Boolean=false){
            box.addView(NeonUi.gap(c,8))
            val group=LinearLayout(c).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
            group.addView(NeonUi.label(c,title,12f,NeonUi.ink),LinearLayout.LayoutParams(0,-2,1.5f))
            val field=EditText(c).apply{
                tag="d-param-$key";setText(value);setSingleLine(true);textSize=16f
                setTextColor(NeonUi.cyan);setSelectAllOnFocus(true)
                inputType=InputType.TYPE_CLASS_NUMBER or
                    (if(decimal)InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
                background=NeonUi.panel(c,NeonUi.blue)
                setPadding(NeonUi.dp(c,10),NeonUi.dp(c,9),NeonUi.dp(c,10),NeonUi.dp(c,9))
                minHeight=NeonUi.dp(c,46)
            }
            fields[key]=field;group.addView(field,LinearLayout.LayoutParams(0,-2,.85f))
            box.addView(group)
        }
        row("最近觀察交易日 N（4～120）","lookback",initial.lookback.toString())
        row("至少觸及漲停 K 次","limit",initial.minimumLimitHits.toString())
        row("連續陽線 K 根","green",initial.bullishBars.toString())
        row("最近連續放量 K 日","volumeDays",initial.volumeDays.toString())
        row("放量之前的 N 日均量","base",initial.volumeBaseDays.toString())
        row("每日至少 M 倍均量","multiple",initial.volumeMultiple.toString(),true)
        box.addView(NeonUi.gap(c,10))
        box.addView(NeonUi.label(c,
            "缺口定義：當日最低價嚴格高於前日最高價；後續任何一天最低價碰到缺口下緣即視為完全回補。" +
            "漲停為台股一般幅度的估算觸及，不等於官方漲停認證；連陽勝大盤需同日期的證交所指數。",11f,NeonUi.amber))
        val scroller=ScrollView(c).apply{addView(box)}
        val dialog=MaterialAlertDialogBuilder(c).setTitle("設定 D 主力進場門檻")
            .setView(scroller).setNegativeButton("取消",null)
            .setPositiveButton("儲存",null).create()
        dialog.setOnShowListener{
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                try{
                    fun n(key:String)=fields.getValue(key).text.toString().trim().toInt()
                    val config=DPatternConfig(
                        n("lookback"),n("limit"),n("green"),n("volumeDays"),n("base"),
                        fields.getValue("multiple").text.toString().trim().toDouble()
                    ).validated()
                    onSave(config);dialog.dismiss()
                }catch(err:Exception){Toast.makeText(c,"D 條件數值錯誤：${err.message}",Toast.LENGTH_LONG).show()}
            }
        }
        dialog.show()
    }
}
