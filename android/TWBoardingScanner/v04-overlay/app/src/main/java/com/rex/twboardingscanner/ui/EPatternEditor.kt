package com.rex.twboardingscanner.ui

import android.content.Context
import android.text.InputType
import android.widget.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rex.twboardingscanner.domain.EPatternConfig

internal object EPatternEditor{
    fun show(c:Context,initial:EPatternConfig,onSave:(EPatternConfig)->Unit){
        val box=NeonUi.vertical(c).apply{setPadding(NeonUi.dp(c,16),NeonUi.dp(c,12),NeonUi.dp(c,16),NeonUi.dp(c,12))}
        box.addView(NeonUi.label(c,"DIF 必須上升，前日位於 MACD 訊號線下方或相等，且今日差距縮小。線距以收盤價百分比衡量；正、負軸皆適用。使用前一完整交易日，排除當日日K。",13f))
        box.addView(NeonUi.label(c,"最大線距／收盤價（%） · 0.001～2",13f,NeonUi.cyan,true))
        val input=NeonUi.field(EditText(c).apply{setText(initial.tolerancePct.toString());inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL;tag="e-tolerance"})
        box.addView(input)
        val cross=CheckBox(c).apply{text="包含本日剛向上交叉（仍須在線距內）";setTextColor(NeonUi.ink);isChecked=initial.includeCross;tag="e-include-cross"}
        box.addView(cross)
        val dialog=MaterialAlertDialogBuilder(c).setTitle("E｜DIF金叉")
            .setView(ScrollView(c).apply{addView(box)}).setNegativeButton("取消",null).setPositiveButton("儲存",null).create()
        dialog.setOnShowListener{dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            runCatching{EPatternConfig(input.text.toString().toDouble(),cross.isChecked).validated()}
                .onSuccess{onSave(it);dialog.dismiss()}.onFailure{input.error=it.message?:"請填有效線距"}
        }}
        dialog.show()
    }
}
