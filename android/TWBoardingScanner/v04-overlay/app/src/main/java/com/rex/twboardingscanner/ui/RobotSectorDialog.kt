package com.rex.twboardingscanner.ui

import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rex.twboardingscanner.domain.StockSector

internal object RobotSectorDialog {
    fun show(a:AppCompatActivity,initial:Set<StockSector>,save:(Set<StockSector>)->Unit) {
        val selected=initial.toMutableSet()
        val root=NeonUi.vertical(a).apply{setPadding(NeonUi.dp(a,14),NeonUi.dp(a,12),NeonUi.dp(a,14),NeonUi.dp(a,12))}
        val summary=NeonUi.label(a,"",13f,NeonUi.cyan,true)
        val rows=mutableMapOf<StockSector,CheckBox>()
        fun refresh(){
            summary.text="保留 ${selected.size} 類／排除 ${StockSector.entries.size-selected.size} 類"
            rows.forEach{(sector,row)->row.text="${sector.label}  ·  ${if(sector in selected)"保留" else "排除"}"}
        }
        root.addView(NeonUi.label(a,"產業範圍 · 保留／排除",19f,NeonUi.ink,true))
        root.addView(NeonUi.label(a,"勾選＝保留此產業；取消勾選＝排除此產業。儲存後套用所有啟用區域；舊批次及結果保留。",12f))
        root.addView(summary)
        root.addView(NeonUi.row(a,listOf(
            NeonUi.button(a,"全部保留"){rows.values.forEach{it.isChecked=true}},
            NeonUi.button(a,"全部排除",NeonUi.amber){rows.values.forEach{it.isChecked=false}}
        )))
        val body=NeonUi.vertical(a)
        StockSector.entries.forEach{sector->
            val row=CheckBox(a).apply{
                tag="robot-sector-${sector.name}";setTextColor(NeonUi.ink);textSize=14f
                isChecked=sector in selected;minHeight=NeonUi.dp(a,48)
                setOnCheckedChangeListener{_,checked->if(checked)selected+=sector else selected-=sector;refresh()}
            }
            rows[sector]=row;body.addView(row)
        }
        refresh()
        root.addView(ScrollView(a).apply{addView(body)},LinearLayout.LayoutParams(-1,NeonUi.dp(a,300)))
        val dialog=MaterialAlertDialogBuilder(a).setBackground(NeonUi.panel(a,NeonUi.cyan)).setView(root)
            .setNegativeButton("取消",null).setPositiveButton("儲存產業",null).create()
        dialog.show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener{
            if(selected.isEmpty()){Toast.makeText(a,"請至少保留一個產業",Toast.LENGTH_LONG).show();return@setOnClickListener}
            save(selected.toSet());dialog.dismiss()
        }
    }
}
