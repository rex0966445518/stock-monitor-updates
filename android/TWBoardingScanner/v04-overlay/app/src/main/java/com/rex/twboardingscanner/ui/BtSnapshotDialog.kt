package com.rex.twboardingscanner.ui

import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rex.twboardingscanner.backtest.BtRuleApply
import org.json.JSONObject

internal object BtSnapshotDialog {
    fun show(c:Context,snapshot:JSONObject,title:String="回測條件快照",afterApply:()->Unit={}) {
        val frozen=JSONObject(snapshot.toString())
        lateinit var dialog:androidx.appcompat.app.AlertDialog
        val root=NeonUi.vertical(c).apply{setPadding(NeonUi.dp(c,14),NeonUi.dp(c,16),NeonUi.dp(c,14),NeonUi.dp(c,14))}
        root.addView(NeonUi.label(c,title,20f,NeonUi.ink,true));root.addView(NeonUi.gap(c,12))
        val height=minOf(320,(c.resources.displayMetrics.heightPixels/c.resources.displayMetrics.density).toInt()-420).coerceAtLeast(80)
        val message=NeonUi.label(c,BacktestJournalUi.describe(frozen),13f,NeonUi.ink).apply{setLineSpacing(NeonUi.dp(c,3).toFloat(),1f)}
        root.addView(ScrollView(c).apply{isFillViewport=false;addView(message)},LinearLayout.LayoutParams(-1,NeonUi.dp(c,height)))
        root.addView(NeonUi.gap(c,12))
        root.addView(NeonUi.label(c,"ABC／產業／下車條件同步主頁，交易設定帶入下次回測。禁股／限價保留目前名單，請至名單管理修改。套用後不自動執行。",11f,NeonUi.cyan))
        root.addView(NeonUi.gap(c,10))
        val apply=NeonUi.button(c,"套用規則",NeonUi.mint){
            runCatching{BtRuleApply.apply(c,frozen)}.onSuccess{
                Toast.makeText(c,"規則已套用到主頁與歷史回測；按開始才執行",Toast.LENGTH_LONG).show()
                afterApply();dialog.dismiss()
            }.onFailure{Toast.makeText(c,"未套用：${it.message}",Toast.LENGTH_LONG).show()}
        }.apply{tag="apply-snapshot-rules"}
        root.addView(NeonUi.row(c,listOf(NeonUi.button(c,"關閉",NeonUi.muted){dialog.dismiss()},apply)),LinearLayout.LayoutParams(-1,NeonUi.dp(c,52)))
        dialog=MaterialAlertDialogBuilder(c).setBackground(NeonUi.panel(c,NeonUi.cyan)).setView(root).create()
        dialog.show()
    }
}
