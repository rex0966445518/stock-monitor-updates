package com.rex.twboardingscanner.ui

import android.content.Context
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rex.twboardingscanner.backtest.BtRuleApply
import org.json.JSONObject

internal object BtSnapshotDialog {
    fun show(c:Context,snapshot:JSONObject,title:String="回測條件快照",afterApply:()->Unit={}) {
        val frozen=JSONObject(snapshot.toString())
        val dialog=MaterialAlertDialogBuilder(c).setTitle(title)
            .setMessage(BacktestJournalUi.describe(frozen)+"\n\n套用規則：ABC 與產業會同步主頁；日期、本金、股號、持倉及獲利目標會帶入下次回測。套用後不會自動掃描或改寫歷史結果。")
            .setNegativeButton("關閉",null).setPositiveButton("套用規則",null).create()
        dialog.show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            runCatching{BtRuleApply.apply(c,frozen)}.onSuccess{
                Toast.makeText(c,"規則已套用到主頁與歷史回測；按開始才執行",Toast.LENGTH_LONG).show()
                afterApply();dialog.dismiss()
            }.onFailure{Toast.makeText(c,"未套用：${it.message}",Toast.LENGTH_LONG).show()}
        }
    }
}
