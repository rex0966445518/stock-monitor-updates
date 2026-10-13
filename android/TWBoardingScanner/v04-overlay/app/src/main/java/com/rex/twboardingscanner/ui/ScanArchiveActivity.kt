package com.rex.twboardingscanner.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rex.twboardingscanner.data.*
import com.rex.twboardingscanner.domain.*
import org.json.JSONObject
import java.time.LocalDate
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Per-run immutable folders. Older date-only signal logs remain accessible. */
class ScanArchiveActivity:AppCompatActivity(){
    private lateinit var archive:ScanArchiveStore
    private lateinit var old:SignalHistoryDb
    private lateinit var root:LinearLayout
    private lateinit var folders:ScrollView
    private lateinit var folderContent:LinearLayout
    private lateinit var detailHead:LinearLayout
    private lateinit var ranked:RecyclerView
    private var dateFilter:String?=null
    private var selected:String?=null
    private fun dp(x:Int)=NeonUi.dp(this,x)
    private fun text(t:String,size:Float=12f,color:Int=NeonUi.muted,bold:Boolean=false)=
        NeonUi.label(this,t,size,color,bold)
    private fun cash(x:Double)=String.format(Locale.TAIWAN,"%,.0f",x)
    private fun dateOf(time:Long)=Instant.ofEpochMilli(time).atZone(RuleMetrics.TAIPEI)
        .format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm",Locale.TAIWAN))
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        archive=ScanArchiveStore(this);old=SignalHistoryDb(this)
        root=NeonUi.vertical(this).apply{setPadding(dp(12),dp(12),dp(12),dp(16))}
        val base=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            background=NeonUi.backdrop(context)
            addView(root,LinearLayout.LayoutParams(-1,-1))
        }
        setContentView(base)
        ViewCompat.setOnApplyWindowInsetsListener(base){v,i->
            val bars=i.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom);i
        }
        root.addView(NeonUi.header(this,"掃描條件資料夾","STRATEGY ARCHIVE",
            "每次完成掃描都獨立保留條件、入選股票與技術評分排名"){if(selected!=null)showFolders()else finish()})
        val controls=NeonUi.row(this,listOf(
            NeonUi.button(this,"選擇日期"){pickDate()}.apply{tag="folder-date-filter"},
            NeonUi.button(this,"全部資料夾"){dateFilter=null;showFolders()}.apply{tag="folder-all"}
        ))
        root.addView(controls)
        root.addView(NeonUi.button(this,"舊版日曆日誌（按日期查詢）",NeonUi.muted){pickLegacyDate()}.apply{
            tag="folder-legacy-calendar";minHeight=dp(38)
        })
        folderContent=NeonUi.vertical(this)
        folders=ScrollView(this).apply{
            isFillViewport=false;addView(folderContent)
        }
        root.addView(folders,LinearLayout.LayoutParams(-1,0,1f))
        detailHead=NeonUi.vertical(this).apply{visibility=View.GONE;tag="folder-detail-head"}
        root.addView(detailHead)
        ranked=RecyclerView(this).apply{
            visibility=View.GONE
            layoutManager=LinearLayoutManager(this@ScanArchiveActivity)
            clipToPadding=false;setPadding(dp(2),dp(4),dp(2),dp(18))
            tag="folder-ranked-list"
        }
        root.addView(ranked,LinearLayout.LayoutParams(-1,0,1f))
        showFolders()
    }
    private fun pickDate(){
        val today=LocalDate.now(RuleMetrics.TAIPEI)
        DatePickerDialog(this,{_,y,m,d->
            dateFilter=LocalDate.of(y,m+1,d).toString()
            showFolders()
        },today.year,today.monthValue-1,today.dayOfMonth).show()
    }
    private fun pickLegacyDate(){
        val today=LocalDate.now(RuleMetrics.TAIPEI)
        DatePickerDialog(this,{_,y,m,d->
            val selectedDay=LocalDate.of(y,m+1,d).toString()
            val rows=old.queryByDate(selectedDay)
            if(rows.isEmpty()){
                MaterialAlertDialogBuilder(this).setTitle("舊版掃描日誌 · $selectedDay")
                    .setMessage("該日沒有舊版逐股紀錄。")
                    .setPositiveButton("關閉",null).show()
            }else showLegacyRows(selectedDay,rows)
        },today.year,today.monthValue-1,today.dayOfMonth).show()
    }
    private fun showLegacyRows(date:String,rows:List<HistoryRow>){
        MaterialAlertDialogBuilder(this).setTitle("舊版掃描 · $date")
            .setMessage("此為舊版按日期合併的逐股紀錄，沒有當次掃描完整條件快照；不會偽造成新版獨立資料夾。")
            .setPositiveButton("查看 ${rows.size} 筆"){_,_->
                selected="legacy"
                detailHead.removeAllViews()
                detailHead.addView(NeonUi.button(this,"‹ 返回資料夾"){showFolders()})
                detailHead.addView(text("舊版逐股日誌 · $date",16f,NeonUi.ink,true))
                detailHead.visibility=View.VISIBLE
                folders.visibility=View.GONE;ranked.visibility=View.VISIBLE
                ranked.adapter=HistoryAdapter{r->openChart(r)}.apply{submit(rows.sortedByDescending{it.score})}
            }.setNegativeButton("取消",null).show()
    }
    private fun showFolders(){
        selected=null
        ranked.visibility=View.GONE;detailHead.visibility=View.GONE
        folders.visibility=View.VISIBLE;folderContent.removeAllViews()
        val items=archive.list(dateFilter)
        folderContent.addView(NeonUi.gap(this,8))
        folderContent.addView(text(if(dateFilter==null)"歷次掃描 · 共 ${items.size} 個獨立資料夾"
            else "${dateFilter} · 共 ${items.size} 次掃描",14f,NeonUi.cyan,true))
        folderContent.addView(NeonUi.gap(this,7))
        if(items.isEmpty()){
            folderContent.addView(NeonUi.empty(this,"尚無這個日期的掃描資料夾",
                "每次完成主頁全市場掃描後，會自動建立一個條件與股票結果的獨立資料夾。舊版紀錄請使用上方日曆。"))
            return
        }
        items.forEachIndexed{index,item->
            val card=NeonUi.vertical(this).apply{
                background=NeonUi.panel(this@ScanArchiveActivity,if(index==0)NeonUi.cyan else NeonUi.blue)
                setPadding(dp(12),dp(12),dp(12),dp(12))
                tag="scan-folder-${item.id}"
            }
            card.addView(text("${dateOf(item.created)} · ${item.name}",15f,NeonUi.ink,true))
            card.addView(NeonUi.gap(this,6))
            card.addView(text("A 起漲 ${item.a} · B 反轉 ${item.b} · C 長紅 ${item.c} · D 主力 ${item.d} · E 接觸 ${item.e} · F 縮量 ${item.f} 檔",14f,NeonUi.cyan,true))
            card.addView(text("去重 ${item.uniqueStocks} 檔 · 估算資金 ${cash(item.estimatedCash)} 元",15f,NeonUi.amber,true))
            card.addView(NeonUi.gap(this,6))
            card.addView(text("與前次掃描條件比較",12f,NeonUi.muted,true))
            card.addView(text(item.changes,12f,NeonUi.ink).apply{
                maxLines=5;ellipsize=android.text.TextUtils.TruncateAt.END
            })
            card.addView(NeonUi.gap(this,8))
            card.addView(NeonUi.row(this,listOf(
                NeonUi.button(this,"條件快照"){showSettings(item.id)},
                NeonUi.button(this,"開啟 · AI 排名",NeonUi.cyan){openFolder(item)}
            )))
            folderContent.addView(card)
            folderContent.addView(NeonUi.gap(this,9))
        }
        folderContent.addView(text(ScanArchiveStore.CASH_NOTE+"\n"+ScanArchiveStore.RANK_NOTE,11f))
    }
    private fun showSettings(id:String){
        val json=archive.settings(id)?:return
        val types=RadarType.entries.joinToString("\n\n"){type->
            val a=json.getJSONObject("rules").optJSONArray(type.name)?:org.json.JSONArray()
            val selected=(0 until a.length()).map{a.getString(it)}
            val options=ScanConditions.forRadar(type).associate{it.id to it.label}
            "${type.name.take(1)}（${selected.size} 項）：\n"+
                selected.joinToString("\n"){"• ${options[it]?:it}"}.ifBlank{"（本區未勾選）"}
        }
        val industry=json.getJSONArray("sectors")
        val sectors=(0 until industry.length()).map{industry.getString(it)}
        val policy=StockPolicy.read(json.getJSONObject("policy"))
        val details=text("日期 ${json.getString("date")} · 版本 ${json.getString("rulesVersion")}\n"+
            "參與分析區域：${json.optJSONArray("enabledRadars")?.let{a->(0 until a.length()).joinToString("、"){a.getString(it).take(1)}}?:"ABC"}\n"+
            "排除區：${com.rex.twboardingscanner.domain.ExclusionConditions.read(json.optJSONObject("exclusions")).entries.joinToString("；"){(t,ids)->"${t.name.take(1)} ${ids.size} 項"}}\n"+
            "F 門檻：${com.rex.twboardingscanner.domain.FPatternConfig.read(json.optJSONObject("fConfig"))}\n"+
            "E 門檻：${com.rex.twboardingscanner.domain.EPatternConfig.read(json.optJSONObject("eConfig"))}\n"+
            "D 門檻：${com.rex.twboardingscanner.domain.DPatternConfig.read(json.optJSONObject("dConfig"))}\n"+
            "檢查 ${json.optInt("analyzed")}／${json.optInt("requested")} 檔 · 失敗 ${json.optInt("failed")} 檔\n"+
            "產業：${sectors.joinToString("、")}\n"+
            "禁股限價：${policy.summary()}\n"+
            "內外盤資料版本：${json.optString("flowRevision")}\n\n"+
            types+"\n\n"+ScanArchiveStore.RANK_NOTE,12f,NeonUi.ink)
        details.setPadding(dp(15),dp(10),dp(15),dp(16))
        MaterialAlertDialogBuilder(this).setTitle("本次掃描完整條件快照")
            .setView(ScrollView(this).apply{addView(details)})
            .setPositiveButton("關閉",null).show()
    }
    private fun openFolder(folder:ScanArchiveFolder){
        selected=folder.id
        folders.visibility=View.GONE
        detailHead.removeAllViews()
        detailHead.addView(NeonUi.button(this,"‹ 返回掃描資料夾",NeonUi.cyan){showFolders()}.apply{
            tag="folder-back"
        })
        detailHead.addView(text("${dateOf(folder.created)} · AI 綜合技術評分排名",15f,NeonUi.ink,true))
        detailHead.addView(text("A ${folder.a} · B ${folder.b} · C ${folder.c} · D ${folder.d} · E ${folder.e} · F ${folder.f} · 去重 ${folder.uniqueStocks} 股",12f,NeonUi.cyan))
        detailHead.addView(text("一檔預計買 1 張 · 所需約 ${cash(folder.estimatedCash)} 元",12f,NeonUi.amber))
        detailHead.addView(text("排序：技術訊號分數由高至低；每張卡保留當時 K 線與勾選查核結果。",11f))
        detailHead.visibility=View.VISIBLE
        ranked.visibility=View.VISIBLE
        val rows=archive.rows(folder.id)
        ranked.adapter=HistoryAdapter{openChart(it)}.apply{submit(rows)}
    }
    private fun openChart(row:HistoryRow){
        ChipKLauncher.open(this,row.code,row.name)
    }
    override fun onBackPressed(){
        if(selected!=null)showFolders()else super.onBackPressed()
    }
    override fun onDestroy(){archive.close();old.close();super.onDestroy()}
}
