package com.rex.twboardingscanner.ui

import android.content.Context
import android.text.InputType
import android.widget.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rex.twboardingscanner.domain.FPatternConfig

internal object FPatternEditor{
    fun show(c:Context,initial:FPatternConfig,onSave:(FPatternConfig)->Unit){
        val box=NeonUi.vertical(c).apply{setPadding(16,12,16,12)}
        box.addView(NeonUi.label(c,"僅使用前一完整交易日及更早日K，不以盤中量比較全天量。連續3日需4根K線；主條件同時要求價漲與量縮。額外條件在 F 條件清單獨立勾選。",13f))
        fun field(label:String,value:Number,decimal:Boolean=false):EditText{
            box.addView(NeonUi.label(c,label,13f,NeonUi.cyan,true))
            return NeonUi.field(EditText(c).apply{setText(value.toString());inputType=InputType.TYPE_CLASS_NUMBER or if(decimal)InputType.TYPE_NUMBER_FLAG_DECIMAL else 0}).also{box.addView(it)}
        }
        val days=field("連續符合交易日（1～60）",initial.days)
        val pct=field("每日量不超過前日量（%，大於0、小於100）",initial.volumePct,true)
        val avgDays=field("均量基準天數（不含當日）",initial.averageDays)
        val avgPct=field("當日量不超過均量（%）",initial.averagePct,true)
        val lots=field("最低成交量（張）",initial.minLots)
        val ma=field("向上均線天數",initial.maDays)
        val gainDays=field("累計漲幅觀察天數",initial.gainDays)
        val gain=field("累計漲幅上限（%）",initial.maxGainPct,true)
        val dialog=MaterialAlertDialogBuilder(c).setTitle("F｜縮量上漲")
            .setView(ScrollView(c).apply{addView(box)}).setNegativeButton("取消",null).setPositiveButton("儲存",null).create()
        dialog.setOnShowListener{dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            runCatching{FPatternConfig(days.text.toString().toInt(),pct.text.toString().toDouble(),avgDays.text.toString().toInt(),avgPct.text.toString().toDouble(),lots.text.toString().toInt(),ma.text.toString().toInt(),gainDays.text.toString().toInt(),gain.text.toString().toDouble()).validated()}
                .onSuccess{onSave(it);dialog.dismiss()}.onFailure{days.error=it.message?:"請填有效數值"}
        }}
        dialog.show()
    }
}
