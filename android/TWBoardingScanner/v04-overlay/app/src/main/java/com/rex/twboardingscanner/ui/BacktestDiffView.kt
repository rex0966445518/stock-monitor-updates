package com.rex.twboardingscanner.ui

import android.content.Context
import android.widget.LinearLayout
import com.rex.twboardingscanner.backtest.*
import org.json.JSONObject

/** Card-local comparison: compact preview with all changes reachable in the same card. */
internal class BacktestDiffView(c:Context,current:JSONObject,previous:JSONObject?,expanded:Boolean=false,onExpanded:(Boolean)->Unit={}):LinearLayout(c){
    init{
        orientation=VERTICAL;tag="journal-diff-${current.optString("id")}";setPadding(0,NeonUi.dp(c,10),0,0)
        addView(NeonUi.label(c,"與前次回測比較",14f,NeonUi.ink,true))
        if(previous==null)addView(NeonUi.label(c,"最早一筆日誌，無前次紀錄可比較",12f)) else{
            val time=previous.optLong("startedAt").takeIf{it>0}?:previous.optLong("finishedAt")
            val ref=previous.optString("id")
            addView(NeonUi.label(c,"對照 ${BacktestJournalUi.time(time)}\n${ref.take(8)} · ${BacktestJournalUi.state(previous.optString("state"))}",10f))
            val diff=BacktestSettingsDiff.compare(current.optJSONObject("settings"),previous.optJSONObject("settings"))
            if(diff.changes.isEmpty())addView(NeonUi.label(c,if(diff.unavailable.isEmpty())"條件與前次相同" else "資料不完整，無法確認是否相同",12f,NeonUi.cyan))
            else{
                addView(NeonUi.gap(c,5))
                addView(NeonUi.label(c,SettingChangeKind.entries.joinToString("  ·  "){kind->"${kind.label} ${diff.changes.count{it.kind==kind}}"},12f,NeonUi.cyan,true))
                val detail=NeonUi.vertical(c);addView(detail)
                var open=expanded
                val preview=SettingChangeKind.entries.flatMap{kind->diff.changes.filter{it.kind==kind}.take(2)}
                fun render(){
                    detail.removeAllViews()
                    val selected=if(open)SettingChangeKind.entries.flatMap{kind->diff.changes.filter{it.kind==kind}} else preview
                    selected.forEach{change->
                        val (prefix,color)=when(change.kind){SettingChangeKind.ADDED->"＋ 新增" to NeonUi.mint;SettingChangeKind.REMOVED->"− 移除" to NeonUi.pink;SettingChangeKind.CHANGED->"↔ 修改" to NeonUi.amber}
                        detail.addView(NeonUi.label(c,"$prefix｜${change.text}",12f,color).apply{setPadding(0,NeonUi.dp(c,5),0,0)})
                    }
                    if(preview.size<diff.changes.size)detail.addView(NeonUi.button(c,if(open)"收合差異" else "展開全部 ${diff.changes.size} 項差異",NeonUi.cyan){open=!open;onExpanded(open);render()}.apply{tag="journal-diff-toggle"})
                }
                render()
            }
            if(diff.unavailable.isNotEmpty())addView(NeonUi.label(c,"部分舊資料未完整保存，無法比較：${diff.unavailable.joinToString("、")}",10f,NeonUi.amber).apply{setPadding(0,NeonUi.dp(c,5),0,0)})
        }
    }
}
