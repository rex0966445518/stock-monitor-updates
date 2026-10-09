package com.rex.twboardingscanner.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rex.twboardingscanner.backtest.*
import com.rex.twboardingscanner.domain.*
import org.json.JSONObject
import java.math.BigInteger
import java.util.Locale

class BtRobotActivity:AppCompatActivity(){
    private lateinit var store:BtRobotStore
    private lateinit var status:TextView;private lateinit var counts:TextView;private lateinit var best:TextView;private lateinit var coverage:TextView
    private lateinit var startButton:com.google.android.material.button.MaterialButton
    private lateinit var pauseButton:com.google.android.material.button.MaterialButton
    private lateinit var bestButton:com.google.android.material.button.MaterialButton
    private lateinit var records:LinearLayout
    private var stamp="";private var lastId="";private var page=0
    private var visible=false
    private var alert:androidx.appcompat.app.AlertDialog?=null
    private val handler=Handler(Looper.getMainLooper())
    private val poll=object:Runnable{override fun run(){render();handler.postDelayed(this,2000)}}
    private fun label(s:String,size:Float=13f,color:Int=NeonUi.muted,bold:Boolean=false)=NeonUi.label(this,s,size,color,bold)
    private fun signed(v:Double)=String.format(Locale.TAIWAN,"%+,.0f",v)
    private fun num(v:Any)=java.text.DecimalFormat("#,###").format(v)
    private fun color(v:Double)=if(v>=0)NeonUi.pink else NeonUi.mint
    private fun panel(accent:Int)=NeonUi.vertical(this).apply{background=NeonUi.panel(this@BtRobotActivity,accent);setPadding(NeonUi.dp(context,12),NeonUi.dp(context,12),NeonUi.dp(context,12),NeonUi.dp(context,12))}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);store=BtRobotStore(this)
        val root=NeonUi.vertical(this).apply{setPadding(NeonUi.dp(context,14),NeonUi.dp(context,12),NeonUi.dp(context,14),NeonUi.dp(context,24))}
        val scroll=ScrollView(this).apply{setBackgroundColor(Color.rgb(4,17,30));addView(root)};setContentView(scroll)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll){v,insets->val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets}
        root.addView(NeonUi.row(this,listOf(label("自動測試機器人",22f,NeonUi.ink,true),NeonUi.button(this,"返回"){finish()})))
        root.addView(label("自動增減 ABC 條件 · 持續搜尋更高總收益",12f,NeonUi.cyan))
        root.addView(NeonUi.gap(this,12))
        val fixed=panel(NeonUi.cyan)
        fixed.addView(label("固定測試範圍",15f,NeonUi.ink,true));fixed.addView(NeonUi.gap(this,6))
        fixed.addView(label("2026 / 08 / 01 → 2026 / 10 / 08",19f,NeonUi.cyan,true))
        fixed.addView(NeonUi.gap(this,10))
        fixed.addView(NeonUi.row(this,listOf(NeonUi.tile(this,"初始本金","500 萬","不融資",NeonUi.cyan),NeonUi.tile(this,"持倉上限","25 檔","不同股號",NeonUi.amber),NeonUi.tile(this,"淨利下車","4%","扣買賣費稅",NeonUi.pink))))
        fixed.addView(NeonUi.gap(this,8));fixed.addView(label("每次各買 1,000 股；沿用超過 5 天可保本賣出與折讓金。產業沿用建立測試時的回測設定，股號範圍不限。",11f))
        root.addView(fixed);root.addView(NeonUi.gap(this,12))
        val progress=panel(NeonUi.amber);progress.tag="robot-progress"
        progress.addView(label("組合探索進度",16f,NeonUi.ink,true));progress.addView(NeonUi.gap(this,8))
        status=label("尚未開始",13f,NeonUi.amber);progress.addView(status)
        counts=label("已測試 0 組",15f,NeonUi.cyan,true);counts.setPadding(0,NeonUi.dp(this,10),0,NeonUi.dp(this,10));progress.addView(counts)
        best=label("目前已測最佳：—",23f,NeonUi.pink,true);progress.addView(best)
        coverage=label("首次取得日線後，固定保存本次資料",11f);progress.addView(NeonUi.gap(this,8));progress.addView(coverage)
        root.addView(progress);root.addView(NeonUi.gap(this,10))
        startButton=NeonUi.button(this,"開始測試",NeonUi.mint){startTesting()}
        pauseButton=NeonUi.button(this,"暫停",NeonUi.amber){BtRobotWorker.pause(this,store.active());render()}
        root.addView(NeonUi.row(this,listOf(startButton,pauseButton)))
        root.addView(NeonUi.gap(this,6))
        bestButton=NeonUi.button(this,"目前最佳 · 條件與套用",NeonUi.pink){store.session()?.let{showBestRules(it)}};root.addView(bestButton)
        root.addView(NeonUi.gap(this,6))
        root.addView(NeonUi.row(this,listOf(NeonUi.button(this,"未測組合"){untested()},NeonUi.button(this,"測試紀錄"){sessions()})))
        root.addView(NeonUi.gap(this,6));root.addView(NeonUi.button(this,"建立新測試",NeonUi.amber){
            MaterialAlertDialogBuilder(this).setTitle("建立新的測試批次").setMessage("會暂停目前批次並保留所有紀錄；新批次沿用目前回測的 ABC 與產業，重新取得歷史資料。")
                .setNegativeButton("取消",null).setPositiveButton("建立"){_,_->BtRobotWorker.pause(this,store.active());store.create(base());stamp="";render()}.show()
        })
        root.addView(NeonUi.gap(this,10))
        root.addView(label("${BtRobotSpace.slots.size} 個獨立開關，共 ${num(BtRobotSpace.total)} 種排列（含全部關閉）。先測基準與最佳組合附近，再探索其他組合；可暫停續跑。",12f))
        root.addView(label("目前已測最佳 ≠ 全組合最高。固定區間反覆調整可能過度擬合，不代表未來獲利；總損益含留倉、股息與估計折讓金。",12f,NeonUi.amber))
        root.addView(label("勾選財報／法人但缺歷史證據時不通過。系統限制背景執行時可返回此頁重試；每個完成組合都已保存。",11f))
        root.addView(NeonUi.gap(this,16));root.addView(label("已測試組合",19f,NeonUi.ink,true))
        records=NeonUi.vertical(this);root.addView(records);render()
    }
    override fun onResume(){super.onResume();visible=true;handler.post(poll)}
    override fun onPause(){visible=false;handler.removeCallbacks(poll);super.onPause()}
    private fun base():BtSettings {
        val saved=runCatching{BtSettingsCodec.decode(BacktestStore(this).configuration()!!)}.getOrNull()
            ?:BtSettingsCodec.capture(this,java.time.LocalDate.of(2026,8,1),java.time.LocalDate.of(2026,10,8),5000000.0,"")
        return BtRobotSpace.settings(saved.rules,saved.sectors.ifEmpty{StockSector.entries.toSet()})
    }
    private fun startTesting(){
        startButton.isEnabled=false
        Thread{
            val result=runCatching{val id=store.active().ifBlank{store.create(base())};BtRobotWorker.start(this,id)}
            runOnUiThread{if(!isFinishing&&!isDestroyed){result.onFailure{Toast.makeText(this,"無法開始：${it.message}",Toast.LENGTH_LONG).show()};render()}}
        }.start()
    }
    internal fun render(){
        val s=store.session();val id=s?.optString("id").orEmpty()
        if(lastId!=id){lastId=id;page=0;stamp=""}
        val tested=s?.optLong("tested")?:0L;val remaining=BtRobotSpace.total-BigInteger.valueOf(tested)
        val running=s?.optString("state")=="RUNNING"
        status.text=s?.optString("message")?:"按開始測試，自動尋找更高收益組合"
        counts.text="已測試 ${num(tested)} 組\n未測試 ${num(remaining)} 組"
        val key=s?.optString("bestKey").orEmpty()
        best.text=if(key.isEmpty())"目前已測最佳：—" else "目前已測最佳\n${signed(s!!.getDouble("bestProfit"))} 元"
        if(key.isNotEmpty())best.setTextColor(color(s!!.getDouble("bestProfit")))
        coverage.text=if(s==null)"首次載入資料後固定樣本；結果逐組保存" else {
            val settings=BtSettingsCodec.decode(s.getJSONObject("settings"))
            "批次 ${id.take(8)} · "+(if(s.has("loaded"))"資料 ${s.getInt("loaded")} / ${s.getInt("requested")} 檔"else"資料尚未備妥")+"\n產業："+settings.sectors.sortedBy{it.ordinal}.joinToString("、"){it.label}
        }
        startButton.text=when{running->"測試進行中…";s?.optString("state")=="DONE"->"全部測試完成";tested>0->"繼續／重試";else->"開始測試"}
        startButton.isEnabled=!running&&s?.optString("state")!="DONE";pauseButton.isEnabled=running;bestButton.isEnabled=key.isNotEmpty()
        val signature="$id-$tested-$page"
        if(stamp!=signature){stamp=signature;renderTrials(id)}
        if(visible&&s!=null&&key.isNotEmpty()&&s.optLong("bestSeq")>s.optLong("ack")&&alert?.isShowing!=true){
            val seq=s.getLong("bestSeq");store.acknowledge(id,seq)
            alert=MaterialAlertDialogBuilder(this).setTitle("發現目前已測最佳")
                .setMessage("第 $seq 組 · 總損益 ${signed(s.getDouble("bestProfit"))} 元\n已測試 ${num(tested)} 組，仍有未測組合。這是固定區間的樣本內結果，非未來收益保證。")
                .setNegativeButton("繼續測試",null).setNeutralButton("查看明細"){_,_->openReport(id,key)}
                .setPositiveButton("查看／套用規則"){_,_->showBestRules(s)}.show()
        }
    }
    private fun settings(s:JSONObject,key:String)=BtSettingsCodec.decode(s.getJSONObject("settings")).copy(rules=BtRobotSpace.rules(key))
    private fun showBestRules(s:JSONObject){val key=s.optString("bestKey");if(key.isNotEmpty())BtSnapshotDialog.show(this,BtSettingsCodec.encode(settings(s,key)),"目前已測最佳 · 條件快照")}
    private fun openReport(id:String,key:String){startActivity(Intent(this,BacktestActivity::class.java).putExtra("robotSession",id).putExtra("robotKey",key))}
    private fun renderTrials(id:String){
        records.removeAllViews();if(id.isBlank()){records.addView(label("完成第一組後會顯示結果與完整買賣紀錄。",13f));return}
        val rows=store.trials(id,20,page*20)
        rows.forEach{r->
            val key=r.getString("key");val pnl=r.getDouble("profit");val box=panel(color(pnl))
            box.addView(label("第 ${r.getLong("seq")} 組 · ${BacktestJournalUi.time(r.getLong("finishedAt"))}",11f))
            box.addView(label("${signed(pnl)} 元",24f,color(pnl),true))
            box.addView(label(BtRobotSpace.rules(key).entries.joinToString(" · "){"${it.key.name.take(1)} ${it.value.size} 項"},13f,NeonUi.cyan))
            box.addView(label("買 ${r.getInt("buys")} 張／賣 ${r.getInt("closed")} 張 · 最大回撤 ${String.format(Locale.TAIWAN,"%.2f",r.getDouble("drawdown"))}%",11f))
            box.addView(NeonUi.gap(this,6))
            box.addView(NeonUi.row(this,listOf(NeonUi.button(this,"條件快照"){store.session(id)?.let{BtSnapshotDialog.show(this,BtSettingsCodec.encode(settings(it,key)),"第 ${r.getLong("seq")} 組")}},NeonUi.button(this,"完整日誌"){openReport(id,key)})))
            records.addView(NeonUi.gap(this,8));records.addView(box)
        }
        if(rows.isEmpty())records.addView(label("尚未完成任何組合；載入資料不計入已測數。",12f))
        val total=store.session(id)?.optLong("tested")?:0
        val previous=NeonUi.button(this,"上一頁"){page--;stamp="";render()}.apply{isEnabled=page>0}
        val next=NeonUi.button(this,"下一頁"){page++;stamp="";render()}.apply{isEnabled=total>(page+1L)*20}
        records.addView(NeonUi.gap(this,8));records.addView(label("第 ${page+1} 頁 · 共 $total 組完整紀錄",12f));records.addView(NeonUi.row(this,listOf(previous,next)))
    }
    private fun untested(){
        val s=store.session();if(s==null){Toast.makeText(this,"請先開始或建立測試批次",Toast.LENGTH_SHORT).show();return}
        val id=s.getString("id");val content=NeonUi.vertical(this);val preview=mutableSetOf<String>()
        var cursor=BigInteger(s.getString("cursor"))
        repeat(10){
            val candidate=BtRobotSpace.next(BtRobotSpace.key(BtSettingsCodec.decode(s.getJSONObject("settings")).rules),s.optString("bestKey").ifBlank{null},cursor,BigInteger(s.getString("seed"))){it in preview||store.seen(id,it)}
            if(candidate!=null){
                preview+=candidate.key;cursor=candidate.cursor
                content.addView(NeonUi.button(this,BtRobotSpace.rules(candidate.key).entries.joinToString(" · "){"${it.key.name.take(1)} ${it.value.size} 項"}){BtSnapshotDialog.show(this,BtSettingsCodec.encode(settings(s,candidate.key)),"尚未測試 · 候選規則")})
                content.addView(NeonUi.gap(this,6))
            }
        }
        val wrap=NeonUi.vertical(this).apply{setPadding(NeonUi.dp(context,16),NeonUi.dp(context,10),NeonUi.dp(context,16),NeonUi.dp(context,10));addView(label("未測試 ${num(store.remaining(id))} 組\n以下預覽最多 10 組；找到新最佳後順序會調整。",12f,NeonUi.ink));addView(content)}
        MaterialAlertDialogBuilder(this).setTitle("未測試組合").setView(ScrollView(this).apply{addView(wrap)}).setPositiveButton("關閉",null).show()
    }
    private fun sessions(){
        val sessions=store.sessions();if(sessions.isEmpty()){Toast.makeText(this,"尚無測試紀錄",Toast.LENGTH_SHORT).show();return}
        val names=sessions.map{ "${BacktestJournalUi.time(it.getLong("created"))} · ${it.getLong("tested")} 組\n"+if(it.has("bestProfit"))"已測最佳 ${signed(it.getDouble("bestProfit"))} 元"else"尚無結果"}
        MaterialAlertDialogBuilder(this).setTitle("選擇測試批次").setItems(names.toTypedArray()){_,i->
            if(store.active()!=sessions[i].getString("id"))BtRobotWorker.pause(this,store.active())
            store.activate(sessions[i].getString("id"));stamp="";render()
        }.setNegativeButton("關閉",null).show()
    }
}
