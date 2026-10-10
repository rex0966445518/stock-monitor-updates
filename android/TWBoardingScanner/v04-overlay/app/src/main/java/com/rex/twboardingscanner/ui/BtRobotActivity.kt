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
    private lateinit var requiredButton:com.google.android.material.button.MaterialButton
    private lateinit var spaceLabel:TextView
    private var busy=false;private var recovering=false;private var exportId=""
    private var stamp="";private var lastId="";private var page=0
    private var visible=false
    private var alert:androidx.appcompat.app.AlertDialog?=null
    private val handler=Handler(Looper.getMainLooper())
    private val poll=object:Runnable{override fun run(){render();recover();handler.postDelayed(this,2000)}}
    private fun label(s:String,size:Float=13f,color:Int=NeonUi.muted,bold:Boolean=false)=NeonUi.label(this,s,size,color,bold)
    private fun signed(v:Double)=String.format(Locale.TAIWAN,"%+,.0f",v)
    private fun num(v:Any)=java.text.DecimalFormat("#,###").format(v)
    private fun color(v:Double)=if(v>=0)NeonUi.pink else NeonUi.mint
    private fun panel(accent:Int)=NeonUi.vertical(this).apply{background=NeonUi.panel(this@BtRobotActivity,accent);setPadding(NeonUi.dp(context,12),NeonUi.dp(context,12),NeonUi.dp(context,12),NeonUi.dp(context,12))}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);store=BtRobotStore(this);exportId=savedInstanceState?.getString("exportId").orEmpty()
        val root=NeonUi.vertical(this).apply{setPadding(NeonUi.dp(context,16),NeonUi.dp(context,16),NeonUi.dp(context,16),NeonUi.dp(context,28))}
        val scroll=ScrollView(this).apply{setBackgroundColor(NeonUi.canvas);addView(root)};setContentView(scroll)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll){v,insets->val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets}
        root.addView(NeonUi.header(this,"自動測試機器人","AUTOMATION LAB"){finish()})
        val toolsRow=NeonUi.row(this,listOf(NeonUi.button(this,"禁股名單",NeonUi.pink){StockToolsActivity.open(this,"BAN",StockScope.ROBOT)},NeonUi.button(this,"限價名單",NeonUi.amber){StockToolsActivity.open(this,"LIMIT",StockScope.ROBOT)},NeonUi.button(this,"搜尋股票"){StockToolsActivity.open(this,"SEARCH",StockScope.ROBOT)}));root.addView(toolsRow)
        root.addView(NeonUi.gap(this,8))
        root.addView(label("自動增減 ABC 條件 · 持續搜尋更高總收益",12f,NeonUi.cyan))
        root.addView(NeonUi.gap(this,12))
        val fixed=panel(NeonUi.cyan)
        fixed.addView(label("固定測試範圍",15f,NeonUi.ink,true));fixed.addView(NeonUi.gap(this,6))
        fixed.addView(label("2026 / 08 / 01 → 2026 / 10 / 08",19f,NeonUi.cyan,true))
        fixed.addView(NeonUi.gap(this,10))
        fixed.addView(NeonUi.row(this,listOf(NeonUi.tile(this,"初始本金","500 萬","不融資",NeonUi.cyan),NeonUi.tile(this,"持倉上限","25 檔","不同股號",NeonUi.amber),NeonUi.tile(this,"淨利下車","4%","扣買賣費稅",NeonUi.pink))))
        fixed.addView(NeonUi.gap(this,8));fixed.addView(label("每次各買 1,000 股；沿用超過 5 天可保本賣出與折讓金。產業沿用建立測試時的回測設定，股號範圍不限。",11f))
        root.addView(fixed)
        root.addView(NeonUi.button(this,"下車賣出條件 · 5 組選填",NeonUi.amber){
            if(!busy){
                val prior=store.session()?.let{BtSettingsCodec.decode(it.getJSONObject("settings"))}?:base()
                ExitRulesEditor.show(this,"自動測試機器人",prior.exitRules){rules->applyExitRules(rules)}
            }
        }.apply{tag="robot-exit-rules"})
        root.addView(label("修改下車條件會保存成新批次、保留舊結果；按開始測試才執行。",11f))
        root.addView(NeonUi.gap(this,12))
        val configLast=root.getChildAt(root.childCount-1)
        val progress=panel(NeonUi.amber);progress.tag="robot-progress"
        progress.addView(MarketGuardPanel(this,"所有組合共用同一份大盤歷史快照"));progress.addView(NeonUi.gap(this,10))
        progress.addView(label("組合探索進度",16f,NeonUi.ink,true));progress.addView(NeonUi.gap(this,8))
        status=label("尚未開始",13f,NeonUi.amber);progress.addView(status)
        counts=label("已測試 0 組",13f,NeonUi.cyan,true);counts.setPadding(0,NeonUi.dp(this,10),0,NeonUi.dp(this,10));progress.addView(counts)
        best=label("目前已測最佳：—",23f,NeonUi.pink,true);progress.addView(best)
        coverage=label("首次取得日線後，固定保存本次資料",11f);progress.addView(NeonUi.gap(this,8));progress.addView(coverage)
        root.addView(progress);root.addView(NeonUi.gap(this,10))
        startButton=NeonUi.primary(this,"開始測試"){startTesting()}
        pauseButton=NeonUi.button(this,"暫停",NeonUi.amber){if(!busy)BtRobotWorker.pause(this,store.active());render()}
        root.addView(NeonUi.row(this,listOf(startButton,pauseButton)))
        root.addView(NeonUi.gap(this,6))
        bestButton=NeonUi.button(this,"目前最佳 · 條件與套用",NeonUi.pink){if(!busy)store.session()?.let{showBestRules(it)}};root.addView(bestButton)
        root.addView(NeonUi.gap(this,6))
        val manageFirst=NeonUi.row(this,listOf(NeonUi.button(this,"未測組合"){untested()},NeonUi.button(this,"測試紀錄"){sessions()}));root.addView(manageFirst)
        root.addView(NeonUi.gap(this,6))
        requiredButton=NeonUi.button(this,"條件選單",NeonUi.cyan){if(!busy)BtRobotRulesDialog.show(this,required(),forbidden()){required,forbidden->applyRequired(required,forbidden)}};requiredButton.tag="robot-required";root.addView(requiredButton)
        root.addView(NeonUi.gap(this,6))
        root.addView(NeonUi.button(this,"一鍵清除零收益",NeonUi.amber){clearZeros()}.apply{tag="robot-clear-zero"})
        root.addView(NeonUi.gap(this,6))
        root.addView(NeonUi.row(this,listOf(NeonUi.button(this,"匯出結果／斷點"){exportCheckpoint()},NeonUi.button(this,"匯入並續跑"){importCheckpoint()})))
        root.addView(NeonUi.gap(this,6));root.addView(NeonUi.button(this,"建立新測試",NeonUi.amber){
            if(!busy)MaterialAlertDialogBuilder(this).setTitle("建立新的測試批次").setMessage("會暂停目前批次並保留所有紀錄；新批次沿用目前回測的 ABC 與產業及必選／排除設定，重新取得歷史資料。")
                .setNegativeButton("取消",null).setPositiveButton("建立"){_,_->BtRobotWorker.pause(this,store.active());store.create(base(),required(),forbidden());stamp="";render()}.show()
        })
        root.addView(NeonUi.gap(this,10))
        spaceLabel=label("",12f);root.addView(spaceLabel)
        root.addView(label("目前已測最佳 ≠ 全組合最高。固定區間反覆調整可能過度擬合，不代表未來獲利；總損益含留倉、股息與估計折讓金。",12f,NeonUi.amber))
        root.addView(label("勾選財報／法人但缺歷史證據時不通過。意外中斷 10 秒後自動續跑，手動暫停不自啟。歷史條件首次預算完成後保存，續跑直接讀取。Android 省電／背景限制可能延後啟動；每組完成即保存。",11f))
        val manageLast=root.getChildAt(root.childCount-1)
        root.addView(NeonUi.gap(this,16));root.addView(label("已測試組合",19f,NeonUi.ink,true))
        records=NeonUi.vertical(this);root.addView(records)
        // Keep execution and best result above configuration and maintenance tools.
        val controls=NeonUi.vertical(this)
        val from=root.indexOfChild(progress);val end=root.indexOfChild(bestButton)
        repeat(end-from+1){val child=root.getChildAt(from);root.removeViewAt(from);controls.addView(child)}
        root.addView(controls,1)
        root.removeView(toolsRow);fixed.addView(NeonUi.gap(this,8));fixed.addView(toolsRow)
        NeonUi.group(root,fixed,configLast,"測試參數與下車條件","固定區間、資金、5 組認賠條件","robot-settings")
        NeonUi.group(root,manageFirst,manageLast,"條件選單與批次管理","必選／排除、匯入匯出與續跑","robot-management")
        render()
    }
    override fun onResume(){super.onResume();visible=true;handler.post(poll)}
    override fun onPause(){visible=false;handler.removeCallbacks(poll);super.onPause()}
    private fun base():BtSettings {
        val saved=runCatching{BtSettingsCodec.decode(BacktestStore(this).configuration()!!)}.getOrNull()
            ?:BtSettingsCodec.capture(this,java.time.LocalDate.of(2026,8,1),java.time.LocalDate.of(2026,10,8),5000000.0,"")
        return BtRobotSpace.settings(saved.rules,saved.sectors.ifEmpty{StockSector.entries.toSet()}).copy(stockPolicy=com.rex.twboardingscanner.data.StockPolicyStore(this).read(),stockScope=StockScope.ROBOT,exitRules=com.rex.twboardingscanner.data.ExitRuleStore(this).read(StockScope.ROBOT),marketGuardVersion=MarketCrashGuard.VERSION)
    }
    private fun policySession():String {
        val old=store.session()?:return store.create(base(),required(),forbidden())
        val settings=BtSettingsCodec.decode(old.getJSONObject("settings"))
        val policy=com.rex.twboardingscanner.data.StockPolicyStore(this).read()
        if(settings.marketGuardVersion==0){BtRobotWorker.pause(this,old.getString("id"));return store.create(settings.copy(stockPolicy=policy,stockScope=StockScope.ROBOT,marketGuardVersion=MarketCrashGuard.VERSION),old.optString("required","0"),old.optString("forbidden","0"))}
        if(settings.stockPolicy==policy)return old.getString("id")
        val id=store.create(settings.copy(stockPolicy=policy,stockScope=StockScope.ROBOT),old.optString("required","0"),old.optString("forbidden","0"))
        BtRobotCheckpoint.cloneData(store,old.getString("id"),id)
        return id
    }
    private fun startTesting(){
        if(busy)return
        startButton.isEnabled=false
        Thread{
            val result=runCatching{val id=policySession();BtRobotWorker.start(this,id)}
            runOnUiThread{if(!isFinishing&&!isDestroyed){result.onFailure{Toast.makeText(this,"無法開始：${it.message}",Toast.LENGTH_LONG).show()};render()}}
        }.start()
    }
    internal fun render(){
        if(busy)return
        val s=store.session();val id=s?.optString("id").orEmpty()
        if(lastId!=id){lastId=id;page=0;stamp=""}
        val tested=s?.optLong("tested")?:0L;val total=BtRobotSpace.total(required(),forbidden());val remaining=total-BigInteger.valueOf(tested)
        val fixed=BtRobotSpace.required(required()).bitCount();val blocked=BtRobotSpace.required(forbidden()).bitCount()
        requiredButton.text="條件選單 · 必選 $fixed／排除 $blocked"
        spaceLabel.text="必選 $fixed 項 · 排除 $blocked 項 · 自由 ${65-fixed-blocked} 項 · 共 ${num(total)} 種組合。先測基準與最佳附近，再探索其他組合。已清理的組合仍標記為測過。"
        val policyChanged=s?.let{BtSettingsCodec.decode(it.getJSONObject("settings")).stockPolicy!=com.rex.twboardingscanner.data.StockPolicyStore(this).read()}?:false
        val guardChanged=s?.getJSONObject("settings")?.optInt("marketGuardVersion",0)==0
        val running=!guardChanged&&s?.optString("state") in setOf("RUNNING","RETRY")
        status.text=if(guardChanged)"此舊批次未啟用大盤保護；按開始建立受保護新批次，舊績效保留" else if(policyChanged)"禁股／限價已更新；按開始將建立新批次並沿用快取" else s?.optString("message")?:"按開始測試，自動尋找更高收益組合"
        counts.text="已測試 ${num(tested)} 組\n未測試 ${num(remaining)} 組\n已清理 ${num(s?.optLong("cleared")?:0L)} 組零收益"
        val key=s?.optString("bestKey").orEmpty()
        best.text=if(key.isEmpty())"目前已測最佳：—" else "目前已測最佳\n${signed(s!!.getDouble("bestProfit"))} 元"
        if(key.isNotEmpty())best.setTextColor(color(s!!.getDouble("bestProfit")))
        coverage.text=if(s==null)"首次載入資料後固定樣本；結果逐組保存" else {
            val settings=BtSettingsCodec.decode(s.getJSONObject("settings"))
            "批次 ${id.take(8)} · "+(if(s.has("loaded"))"資料 ${s.getInt("loaded")} / ${s.getInt("requested")} 檔"else"資料尚未備妥")+"\n產業："+settings.sectors.sortedBy{it.ordinal}.joinToString("、"){it.label}
        }
        startButton.text=when{guardChanged->"啟用大盤保護並開始新批次";policyChanged->"套用新名單並開始";running->"測試進行中…";s?.optString("state")=="DONE"->"全部測試完成";tested>0->"繼續／重試";else->"開始測試"}
        startButton.isEnabled=!busy&&!running&&(guardChanged||policyChanged||s?.optString("state")!="DONE");pauseButton.isEnabled=running;bestButton.isEnabled=key.isNotEmpty()
        val signature="$id-$tested-${s?.optLong("cleared")?:0}-$page"
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
        records.removeAllViews();if(id.isBlank()){records.addView(NeonUi.empty(this,"等待第一組結果","完成測試後，可在這裡檢閱組合條件與完整買賣紀錄。"));return}
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
        if(rows.isEmpty())records.addView(label("目前沒有保留的結果；載入資料不計入已測數，清理不會重置已測進度。",12f))
        val total=store.retained(id)
        val previous=NeonUi.button(this,"上一頁"){page--;stamp="";render()}.apply{isEnabled=page>0}
        val next=NeonUi.button(this,"下一頁"){page++;stamp="";render()}.apply{isEnabled=total>(page+1L)*20}
        records.addView(NeonUi.gap(this,8));records.addView(label("第 ${page+1} 頁 · 共 $total 組完整紀錄",12f));records.addView(NeonUi.row(this,listOf(previous,next)))
    }
    private fun untested(){
        if(busy)return
        val s=store.session();if(s==null){Toast.makeText(this,"請先開始或建立測試批次",Toast.LENGTH_SHORT).show();return}
        val id=s.getString("id");val content=NeonUi.vertical(this);val preview=mutableSetOf<String>()
        var cursor=BigInteger(s.getString("cursor"))
        repeat(10){
            val candidate=BtRobotSpace.next(BtRobotSpace.key(BtSettingsCodec.decode(s.getJSONObject("settings")).rules),s.optString("bestKey").ifBlank{null},cursor,BigInteger(s.getString("seed")),s.optString("required","0"),s.optString("forbidden","0")){it in preview||store.seen(id,it)}
            if(candidate!=null){
                preview+=candidate.key;cursor=candidate.cursor
                content.addView(NeonUi.button(this,BtRobotSpace.rules(candidate.key).entries.joinToString(" · "){"${it.key.name.take(1)} ${it.value.size} 項"}){BtSnapshotDialog.show(this,BtSettingsCodec.encode(settings(s,candidate.key)),"尚未測試 · 候選規則")})
                content.addView(NeonUi.gap(this,6))
            }
        }
        val wrap=NeonUi.vertical(this).apply{setPadding(NeonUi.dp(context,16),NeonUi.dp(context,10),NeonUi.dp(context,16),NeonUi.dp(context,10));addView(label("未測試 ${num(store.remaining(id))} 組\n以下預覽最多 10 組；找到新最佳後順序會調整。",12f,NeonUi.ink));addView(content)}
        MaterialAlertDialogBuilder(this).setTitle("未測試組合").setView(ScrollView(this).apply{addView(wrap)}).setPositiveButton("關閉",null).show()
    }
    private fun required()=store.session()?.optString("required","0")?:getSharedPreferences("backtest_robot",0).getString("requiredDraft","0").orEmpty()
    private fun forbidden()=store.session()?.optString("forbidden","0")?:getSharedPreferences("backtest_robot",0).getString("forbiddenDraft","0").orEmpty()
    internal fun applyRequired(key:String,forbiddenKey:String=forbidden()){
        BtRobotSpace.validateConstraints(key,forbiddenKey)
        if(key==required()&&forbiddenKey==forbidden())return
        val old=store.active();val previous=store.session()
        val settings=previous?.let{BtSettingsCodec.decode(it.getJSONObject("settings"))}?:base()
        runTask("正在保存必選／排除條件並沿用快取…"){
            if(old.isNotBlank())BtRobotWorker.pause(this,old)
            val id=store.create(settings,key,forbiddenKey)
            if(old.isNotBlank())BtRobotCheckpoint.cloneData(store,old,id)
            getSharedPreferences("backtest_robot",0).edit().putString("requiredDraft",key).putString("forbiddenDraft",forbiddenKey).commit()
            "必選／排除條件已保存；按開始測試，新批次沿用原歷史快取"
        }
    }
    internal fun applyExitRules(rules:List<ExitRule>){
        ExitRules.validate(rules)
        val previous=store.session();val old=previous?.getString("id").orEmpty()
        val settings=previous?.let{BtSettingsCodec.decode(it.getJSONObject("settings"))}?:base()
        if(settings.exitRules==rules)return
        runTask("正在保存下車條件與新測試批次…"){
            if(old.isNotBlank())BtRobotWorker.pause(this,old)
            val id=store.create(settings.copy(exitRules=rules),required(),forbidden())
            if(old.isNotBlank())BtRobotCheckpoint.cloneData(store,old,id)
            com.rex.twboardingscanner.data.ExitRuleStore(this).save(StockScope.ROBOT,rules)
            "下車條件已保存；按開始測試，新批次沿用歷史資料"
        }
    }
    private fun clearZeros(){
        val id=store.active();if(id.isBlank()||busy)return
        runTask("正在清理零收益結果…"){
            val count=store.clearZero(id)
            "已清除 $count 組零收益；已測標記保留，不會重測"
        }
    }
    private fun runTask(message:String,task:()->String){
        if(busy)return;busy=true;status.text=message;startButton.isEnabled=false
        Thread{val result=runCatching(task);runOnUiThread{busy=false;if(!isFinishing&&!isDestroyed){page=0;stamp="";render();Toast.makeText(this,result.getOrElse{"操作未完成：${it.message}"},Toast.LENGTH_LONG).show()}}}.start()
    }
    private fun exportCheckpoint(){
        if(busy)return
        exportId=store.active();if(exportId.isBlank()){Toast.makeText(this,"請先建立測試",Toast.LENGTH_SHORT).show();return}
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE,"AI-離職神器-機器人-${exportId.take(8)}-${System.currentTimeMillis()}.zip"),731)
    }
    private fun importCheckpoint(){
        if(busy)return
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),732)
    }
    override fun onSaveInstanceState(outState:Bundle){outState.putString("exportId",exportId);super.onSaveInstanceState(outState)}
    @Deprecated("Activity result compatibility")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data);val uri=data?.data?:return
        if(resultCode!=RESULT_OK)return
        if(requestCode==731){val id=exportId;runTask("暫停並匯出結果、斷點及快取…"){
            BtRobotWorker.pause(this,id)
            val temp=java.io.File(cacheDir,"robot-${java.util.UUID.randomUUID()}.zip")
            try{BtRobotCheckpoint.export(this,id,temp);contentResolver.openOutputStream(uri,"wt")!!.use{out->temp.inputStream().use{it.copyTo(out)}}}finally{temp.delete()}
            "已匯出 ZIP：含 CSV 結果、完整報告、斷點與歷史快取；可按繼續測試"
        }}else if(requestCode==732)runTask("正在校驗並匯入測試斷點…"){
            val old=store.active();val id=contentResolver.openInputStream(uri)!!.use{BtRobotCheckpoint.restore(this,it)}
            if(old.isNotBlank())BtRobotWorker.pause(this,old)
            if(BtSettingsCodec.decode(store.session(id)!!.getJSONObject("settings")).marketGuardVersion>0&&BtSettingsCodec.decode(store.session(id)!!.getJSONObject("settings")).stockPolicy==com.rex.twboardingscanner.data.StockPolicyStore(this).read()){
                BtRobotWorker.start(this,id);"已匯入獨立批次並接續未測組合"
            }else "已匯入並保留暫停；按開始將套用目前名單及大盤保護建立新批次。"
        }
    }
    private fun recover(){
        if(recovering||busy)return
        val s=store.session()?:return
        if(s.optString("state") !in setOf("RUNNING","RETRY"))return
        recovering=true
        Thread{runCatching{BtRobotWorker.recover(this,s.getString("id"),s.optString("token"))};runOnUiThread{recovering=false}}.start()
    }
    private fun sessions(){
        if(busy)return
        val sessions=store.sessions();if(sessions.isEmpty()){Toast.makeText(this,"尚無測試紀錄",Toast.LENGTH_SHORT).show();return}
        val names=sessions.map{ "${BacktestJournalUi.time(it.getLong("created"))} · ${it.getLong("tested")} 組\n"+if(it.has("bestProfit"))"已測最佳 ${signed(it.getDouble("bestProfit"))} 元"else"尚無結果"}
        MaterialAlertDialogBuilder(this).setTitle("選擇測試批次").setItems(names.toTypedArray()){_,i->
            if(store.active()!=sessions[i].getString("id"))BtRobotWorker.pause(this,store.active())
            store.activate(sessions[i].getString("id"));stamp="";render()
        }.setNegativeButton("關閉",null).show()
    }
}
