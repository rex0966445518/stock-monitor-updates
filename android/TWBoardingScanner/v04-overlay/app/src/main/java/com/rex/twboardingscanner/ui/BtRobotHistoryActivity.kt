package com.rex.twboardingscanner.ui

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.rex.twboardingscanner.backtest.*
import com.rex.twboardingscanner.domain.RadarType
import org.json.JSONObject
import java.util.Locale

/** A report archive across every robot batch; details are opened one report at a time. */
class BtRobotHistoryActivity:AppCompatActivity(){
    private lateinit var entries:LinearLayout
    private lateinit var status:TextView
    private lateinit var previous:MaterialButton
    private lateinit var next:MaterialButton
    private data class RankCursor(val profit:Double?,val n:Long)
    private val cursors=mutableListOf(RankCursor(null,Long.MAX_VALUE))
    private var nextCursor:RankCursor?=null
    private var category:RadarType?=null
    private val categoryButtons=mutableMapOf<RadarType?,MaterialButton>()
    @Volatile private var generation=0
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private var reader:Thread?=null
    private var lastError=""
    private var loading=false
    private var mutating=false
    private lateinit var cleanup:MaterialButton
    private val store get()=BtRobotStore(this)
    private fun money(value:Double)=String.format(Locale.TAIWAN,"%+,.0f",value)
    private fun label(value:String,size:Float=12f,color:Int=NeonUi.muted,bold:Boolean=false)=NeonUi.label(this,value,size,color,bold)
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        category=savedInstanceState?.getString("category")?.let{runCatching{RadarType.valueOf(it)}.getOrNull()}
        val savedN=savedInstanceState?.getLongArray("cursors")
        val savedProfits=savedInstanceState?.getDoubleArray("profits")
        if(savedN!=null&&savedProfits!=null&&savedN.isNotEmpty()&&savedN.size==savedProfits.size){
            cursors.clear();savedN.indices.forEach{i->cursors+=RankCursor(savedProfits[i].takeIf{it.isFinite()},savedN[i])}
        }
        val root=NeonUi.vertical(this).apply{setPadding(NeonUi.dp(context,16),NeonUi.dp(context,16),NeonUi.dp(context,16),NeonUi.dp(context,28))}
        val scroll=ScrollView(this).apply{background=NeonUi.backdrop(context);addView(root)};setContentView(scroll)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll){v,insets->val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets}
        root.addView(NeonUi.header(this,"機器人回測紀錄","ROBOT ARCHIVE","所有批次 · 每輪條件 · 詳細買賣紀錄"){finish()})
        fun categoryButton(type:RadarType?):MaterialButton=NeonUi.button(this,type?.label?:"全部類別"){selectCategory(type)}.apply{
            tag="robot-history-category-${type?.name?.take(1)?:"ALL"}";categoryButtons[type]=this
            NeonUi.selected(this,category==type)
        }
        root.addView(categoryButton(null))
        RadarType.entries.chunked(3).forEach{types->root.addView(NeonUi.row(this,types.map{categoryButton(it)}))}
        root.addView(label("跨類條件列入相關類別；空白基準依啟用區域歸檔。",11f))
        root.addView(NeonUi.button(this,"重新整理") {resetPage();reload()}.apply{tag="robot-history-refresh"})
        root.addView(NeonUi.button(this,"複製讀取錯誤"){
            val details=lastError.ifBlank{"尚無讀取錯誤；"+status.text}
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("回測紀錄讀取",details))
            Toast.makeText(this,"已複製讀取資訊",Toast.LENGTH_SHORT).show()
        }.apply{tag="robot-history-copy-error"})
        cleanup=NeonUi.button(this,"刪除未加星紀錄",NeonUi.pink){confirmDeleteUnstarred()}.apply{tag="robot-history-delete-unstarred"}
        root.addView(cleanup)
        root.addView(label("★ 保留的卡片不會被刪除；批次刪除涵蓋全部類別及所有頁面。",11f))
        status=label("讀取回測紀錄…").apply{tag="robot-history-status"};root.addView(status)
        root.addView(NeonUi.gap(this,10));entries=NeonUi.vertical(this).apply{tag="robot-history-cards"};root.addView(entries)
        previous=NeonUi.button(this,"上一頁"){if(!loading&&cursors.size>1){cursors.removeAt(cursors.lastIndex);reload()}}.apply{tag="robot-history-previous"}
        next=NeonUi.button(this,"下一頁"){if(!loading)nextCursor?.let{cursors+=it;reload()}}.apply{tag="robot-history-next"}
        root.addView(NeonUi.row(this,listOf(previous,next)))
    }
    override fun onResume(){super.onResume();reload()}
    override fun onSaveInstanceState(outState:Bundle){
        outState.putString("category",category?.name);outState.putLongArray("cursors",cursors.map{it.n}.toLongArray())
        outState.putDoubleArray("profits",cursors.map{it.profit?:Double.NaN}.toDoubleArray());super.onSaveInstanceState(outState)
    }
    private fun resetPage(){cursors.clear();cursors+=RankCursor(null,Long.MAX_VALUE)}
    private fun selectCategory(type:RadarType?){
        category=type;categoryButtons.forEach{(key,button)->NeonUi.selected(button,key==type)}
        resetPage();reload()
    }
    override fun onDestroy(){generation++;reader?.interrupt();handler.removeCallbacksAndMessages(null);super.onDestroy()}
    internal fun reload(){
        val ticket=++generation;loading=true;previous.isEnabled=false;next.isEnabled=false
        status.text="讀取回測紀錄…"
        val before=cursors.last();val selected=category
        reader?.interrupt()
        val slow=Runnable{if(ticket==generation&&loading&&!isDestroyed){status.text="紀錄仍在讀取中，可能正在整理舊資料。可按重新整理重試。"}}
        handler.postDelayed(slow,10000)
        reader=Thread{
            val cancelled={ticket!=generation||Thread.currentThread().isInterrupted}
            val result=runCatching{
                val source=store
                source.prepareHistory({n->runOnUiThread{if(ticket==generation&&!isDestroyed)status.text="正在整理舊回測紀錄… 已處理至第 $n 筆"}},cancelled)
                check(!cancelled())
                source.rankedHistory(selected,before.profit,before.n,21) to source.historyCount(selected)
            }
            runOnUiThread{if(!isFinishing&&!isDestroyed&&ticket==generation){
                handler.removeCallbacks(slow);loading=false
                result.fold({(rows,total)->
                    if(rows.isEmpty()&&cursors.size>1){cursors.removeAt(cursors.lastIndex);reload();return@fold}
                    runCatching{renderRows(rows,total)}.onFailure{showReadError(it)}
                },{showReadError(it)})
            }}
        }.apply{name="robot-history-reader";start()}
    }
    private fun showReadError(error:Throwable){
        lastError=error.stackTraceToString()
        android.util.Log.e("RobotArchive","History load failed",error)
        status.text="紀錄讀取失敗：${error.message.orEmpty().take(180)}\n請按重新整理重試，或複製讀取錯誤。"
        previous.isEnabled=cursors.size>1;next.isEnabled=false
    }
    internal fun renderRows(rows:List<JSONObject>,total:Long){
        entries.removeAllViews()
        nextCursor=if(rows.size>20)RankCursor(rows[19].getJSONObject("summary").getDouble("profit"),rows[19].getLong("n")) else null
        status.text="${category?.label?:"全部類別"} · 共 $total 筆 · 第 ${cursors.size} 頁\n總淨利潤由高到低"
        if(rows.isEmpty())entries.addView(NeonUi.empty(this,"此類別尚無回測紀錄","完成對應類別的測試後，結果會保存在這裡。"))
        rows.take(20).forEach{entry->
            val id=entry.getString("session");val key=entry.getString("key");val summary=entry.getJSONObject("summary")
            val settings=entry.optJSONObject("batch")?.optJSONObject("settings")?.let{JSONObject(it.toString()).apply{
                listOf("maxHoldingStocks","targetNetPct").forEach{field->if(summary.has(field))put(field,summary.get(field))}
            }}
            val pnl=summary.getDouble("profit");val tint=if(pnl>=0)NeonUi.pink else NeonUi.mint
            val card=NeonUi.vertical(this).apply{
                tag="robot-history-card-${entry.getLong("n")}"
                background=NeonUi.panel(this@BtRobotHistoryActivity,tint)
                setPadding(NeonUi.dp(context,14),NeonUi.dp(context,14),NeonUi.dp(context,14),NeonUi.dp(context,14))
            }
            val starred=entry.optBoolean("favorite")
            val keep=NeonUi.button(this,if(starred)"★ 已保留" else "☆ 保留此卡",NeonUi.amber){toggleFavorite(entry)}.apply{
                tag="robot-history-star-${entry.getLong("n")}";NeonUi.selected(this,starred)
                contentDescription=if(starred)"已保留此卡，點擊取消保留" else "保留此卡，避免刪除"
            }
            card.addView(keep)
            card.addView(label("第 ${entry.optLong("rank",rows.indexOf(entry)+1L)} 名",18f,NeonUi.amber,true))
            val classified=entry.optInt("categories")
            if(classified!=0)card.addView(label("類別 "+RadarType.entries.filter{classified and (1 shl it.ordinal)!=0}.joinToString("／"){it.name.take(1)},12f,NeonUi.cyan,true))
            card.addView(label("${BacktestJournalUi.time(summary.optLong("finishedAt"))} · 第 ${summary.getLong("seq")} 輪",12f))
            card.addView(label("批次 ${id.take(8)}",11f,NeonUi.cyan))
            card.addView(label("總淨利潤 ${money(pnl)} 元",24f,tint,true))
            if(summary.optBoolean("baseline"))card.addView(label("空白基準測試 · 未勾選選股條件",14f,NeonUi.amber,true))
            else summary.optJSONObject("testedOption")?.let{card.addView(label("本輪新增條件\n${it.getString("radarLabel")} · ${it.getString("label")}",14f,NeonUi.amber,true))}
            if(summary.optString("mode")=="GRID")card.addView(label("參數測試 · 持倉 ${summary.optInt("maxHoldingStocks")} 檔 · 淨利 ${summary.optDouble("targetNetPct")}%",14f,NeonUi.amber,true))
            if(settings!=null){
                card.addView(label("${settings.optString("start")} → ${settings.optString("end")}",14f,NeonUi.ink,true))
                val enabled=settings.optJSONArray("enabledRadars")
                val types=enabled?.let{a->(0 until a.length()).map{a.getString(it).take(1)}.joinToString("／")}.orEmpty()
                card.addView(label("啟用 $types · ${BacktestJournalUi.tradingSummary(settings)}",12f,NeonUi.cyan))
            }
            card.addView(label("買入 ${summary.optInt("buys")} 張 · 賣出 ${summary.optInt("closed")} 張\n已實現 ${money(summary.optDouble("realized"))} 元 · 未實現 ${money(summary.optDouble("unrealized"))} 元",12f))
            card.addView(label("最大回撤 ${String.format(Locale.TAIWAN,"%.2f",summary.optDouble("drawdown"))}%",11f))
            card.addView(NeonUi.gap(this,8))
            card.addView(NeonUi.button(this,"詳細買入／賣出紀錄"){
                startActivity(Intent(this,BacktestActivity::class.java).putExtra("robotSession",id).putExtra("robotKey",key))
            }.apply{tag="robot-history-details-${entry.getLong("n")}"})
            card.addView(NeonUi.button(this,"刪除此卡片",NeonUi.pink){confirmDelete(entry)}.apply{tag="robot-history-delete-${entry.getLong("n")}";isEnabled=!starred})
            entries.addView(card);entries.addView(NeonUi.gap(this,12))
        }
        previous.isEnabled=cursors.size>1;next.isEnabled=nextCursor!=null
    }
    private fun toggleFavorite(entry:JSONObject){
        if(mutating)return
        val desired=!entry.optBoolean("favorite")
        mutating=true;cleanup.isEnabled=false
        Thread{
            val result=runCatching{store.setFavorite(entry.getString("session"),entry.getString("key"),desired)}
            runOnUiThread{if(!isFinishing&&!isDestroyed){
                mutating=false;cleanup.isEnabled=true
                result.fold({changed->
                    if(changed){entry.put("favorite",desired);reload()}
                    else {Toast.makeText(this,"此紀錄已移除",Toast.LENGTH_SHORT).show();reload()}
                },{Toast.makeText(this,"保留設定失敗：${it.message}",Toast.LENGTH_LONG).show()})
            }}
        }.start()
    }
    private fun confirmDeleteUnstarred(){
        if(mutating||loading)return
        mutating=true;cleanup.isEnabled=false
        Thread{
            val result=runCatching{store.unstarredSnapshot()}
            runOnUiThread{if(!isFinishing&&!isDestroyed){
                mutating=false;cleanup.isEnabled=true
                result.fold({(through,count)->
                    if(count==0L){Toast.makeText(this,"沒有未加星的紀錄可刪除",Toast.LENGTH_SHORT).show();return@fold}
                    MaterialAlertDialogBuilder(this).setTitle("刪除未加星紀錄？")
                        .setMessage("將刪除全部類別、所有頁面中 $count 筆未加星紀錄及其買賣報告。\n所有 ★ 已保留卡片與機器人已測進度會保留。本次之後新增的測試紀錄不會被刪除。")
                        .setNegativeButton("取消",null).setPositiveButton("刪除未加星紀錄"){_,_->deleteUnstarred(through)}.show()
                },{Toast.makeText(this,"無法讀取刪除筆數：${it.message}",Toast.LENGTH_LONG).show()})
            }}
        }.start()
    }
    private fun deleteUnstarred(through:Long){
        if(mutating)return
        mutating=true;cleanup.isEnabled=false
        status.text="正在刪除未加星紀錄…"
        Thread{
            val result=runCatching{store.deleteUnstarred(through){count->runOnUiThread{
                if(!isFinishing&&!isDestroyed)status.text="正在刪除未加星紀錄… 已刪除 $count 筆"
            }}}
            runOnUiThread{if(!isFinishing&&!isDestroyed){
                mutating=false;cleanup.isEnabled=true
                result.fold({count->Toast.makeText(this,"已刪除 $count 筆未加星紀錄，★ 保留卡片仍在",Toast.LENGTH_LONG).show()},
                    {Toast.makeText(this,"刪除未完成：${it.message}，可重試",Toast.LENGTH_LONG).show()})
                resetPage();reload()
            }}
        }.start()
    }
    private fun confirmDelete(entry:JSONObject){
        if(mutating)return
        if(entry.optBoolean("favorite")){Toast.makeText(this,"請先取消星號保留，才能刪除此卡",Toast.LENGTH_SHORT).show();return}
        val r=entry.getJSONObject("summary")
        MaterialAlertDialogBuilder(this).setTitle("刪除此測試紀錄？")
            .setMessage("批次 ${entry.getString("session").take(8)} · 第 ${r.getLong("seq")} 輪\n將刪除此卡片與詳細買賣報告。其他紀錄及機器人已測進度會保留。")
            .setNegativeButton("取消",null).setPositiveButton("刪除"){_,_->
                Thread{
                    val result=runCatching{store.deleteTrial(entry.getString("session"),entry.getString("key"))}
                    runOnUiThread{if(!isFinishing&&!isDestroyed){
                        result.fold({Toast.makeText(this,if(it)"已刪除測試紀錄" else "此紀錄已保留或移除",Toast.LENGTH_SHORT).show();reload()},
                            {Toast.makeText(this,"刪除失敗：${it.message}",Toast.LENGTH_LONG).show()})
                    }}
                }.start()
            }.show()
    }
}
