package com.rex.twboardingscanner.ui

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.work.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rex.twboardingscanner.backtest.*
import com.rex.twboardingscanner.domain.*
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

class BacktestActivity:AppCompatActivity(){
    private val today get()=LocalDate.now(RuleMetrics.TAIPEI)
    private var start=today.minusDays(180);private var end=today.minusDays(1)
    private lateinit var store:BacktestStore
    private lateinit var startButton:com.google.android.material.button.MaterialButton
    private lateinit var endButton:com.google.android.material.button.MaterialButton
    private lateinit var capital:EditText;private lateinit var codes:EditText
    private lateinit var maxHoldings:EditText;private lateinit var profitTarget:EditText
    private var lastLimit=25;private var lastTarget=3.0
    private lateinit var status:TextView;private lateinit var results:LinearLayout
    private lateinit var runButton:com.google.android.material.button.MaterialButton
    private var displayed=""
    private var viewingLog=false
    private var appliedRevision=""
    private var btRules:Map<RadarType,Set<String>> = emptyMap()
    private var btSectors:Set<StockSector> = emptySet()
    private lateinit var sectorButton:com.google.android.material.button.MaterialButton
    private val ruleButtons=mutableMapOf<RadarType,com.google.android.material.button.MaterialButton>()
    private val handler=Handler(Looper.getMainLooper())
    private val refresh=object:Runnable{override fun run(){refreshStatus();handler.postDelayed(this,2000)}}
    private fun dp(n:Int)=NeonUi.dp(this,n)
    private fun label(s:String,size:Float=13f,color:Int=NeonUi.muted,bold:Boolean=false)=NeonUi.label(this,s,size,color,bold)
    private fun money(v:Double)=String.format(Locale.TAIWAN,"%,.0f",v)
    private fun signed(v:Double)=(if(v>=0)"+" else "−")+money(kotlin.math.abs(v))
    private fun tint(v:Double)=if(v>=0)NeonUi.pink else NeonUi.mint
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);store=BacktestStore(this)
        appliedRevision=getSharedPreferences("scanner_filters",0).getString(BtRuleApply.REVISION,"").orEmpty()
        intent.getStringExtra("robotSession")?.let { id -> viewingLog=true;openRobotReport(id,intent.getStringExtra("robotKey").orEmpty());return }
        val logId=intent.getStringExtra("journalId")
        if(logId!=null){viewingLog=true;openJournal(logId);return}
        val config=store.configuration()
        val saved=runCatching{BtSettingsCodec.decode(config!!)}.getOrElse{BtSettingsCodec.capture(this,start,end,3000000.0,"")}
        btRules=saved.rules;btSectors=saved.sectors
        lastLimit=saved.maxHoldingStocks?:25;lastTarget=saved.targetNetPct
        start=runCatching{LocalDate.parse(config?.getString("start"))}.getOrDefault(start);end=runCatching{LocalDate.parse(config?.getString("end"))}.getOrDefault(end)
        val root=NeonUi.vertical(this).apply{setPadding(dp(14),dp(12),dp(14),dp(24))}
        val scroll=ScrollView(this).apply{setBackgroundColor(Color.rgb(4,17,30));addView(root)};setContentView(scroll)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll){v,insets->val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets}
        root.addView(NeonUi.row(this,listOf(label("歷史回測",25f,NeonUi.ink,true),NeonUi.button(this,"返回"){finish()})))
        root.addView(label("每日篩選 → 尾盤買一張 → 達獲利目標賣出",12f,NeonUi.mint));root.addView(NeonUi.gap(this,12))
        root.addView(NeonUi.row(this,listOf(NeonUi.button(this,"禁股名單",NeonUi.pink){StockToolsActivity.open(this,"BAN",StockScope.BACKTEST)},NeonUi.button(this,"限價名單",NeonUi.amber){StockToolsActivity.open(this,"LIMIT",StockScope.BACKTEST)},NeonUi.button(this,"搜尋股票"){StockToolsActivity.open(this,"SEARCH",StockScope.BACKTEST)})))
        root.addView(NeonUi.gap(this,8))
        root.addView(NeonUi.button(this,"自動測試機器人",NeonUi.amber){startActivity(Intent(this,BtRobotActivity::class.java))})
        root.addView(NeonUi.gap(this,8))
        root.addView(tradingControls())
        root.addView(NeonUi.gap(this,12))
        startButton=NeonUi.button(this,"起始 $start"){pick(true)};endButton=NeonUi.button(this,"結束 $end"){pick(false)}
        root.addView(startButton);root.addView(NeonUi.gap(this,6));root.addView(endButton)
        root.addView(label("2016 年至昨日；單次最多 2 年，可選單日。",11f))
        capital=EditText(this).apply{setText((config?.optDouble("capital",3000000.0)?:3000000.0).toLong().toString());hint="初始模擬本金";inputType=android.text.InputType.TYPE_CLASS_NUMBER;setTextColor(NeonUi.ink)}
        root.addView(label("模擬本金 TWD",12f));root.addView(capital)
        codes=EditText(this).apply{setText(config?.optString("codes")?:"");hint="全部股票（或輸入 2330, 3661…）";setTextColor(NeonUi.ink);setHintTextColor(NeonUi.muted)}
        root.addView(label("留空掃描現存上市櫃；全市場首次下載較久。",12f));root.addView(codes)
        val watcher=object:android.text.TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){}
            override fun afterTextChanged(s:android.text.Editable?){if(readTrading(false)!=null)store.saveDraft(draft())}
        }
        maxHoldings.addTextChangedListener(watcher);profitTarget.addTextChangedListener(watcher)
        sectorButton=NeonUi.button(this,""){editSectors()};refreshSectorButton();root.addView(sectorButton)
        root.addView(label("產業可複選；第一次沿用主頁，之後保留回測專用設定。",11f))
        root.addView(NeonUi.gap(this,8))
        root.addView(label("回測專用 ABC 條件",15f,NeonUi.ink,true))
        root.addView(NeonUi.row(this,RadarType.entries.map{type->NeonUi.button(this,"",when(type){RadarType.A_EARLY_BREAKOUT->NeonUi.pink;RadarType.B_DEEP_REVERSAL->NeonUi.amber;else->NeonUi.cyan}){editRules(type)}.also{ruleButtons[type]=it}}))
        refreshRuleButtons()
        root.addView(label("點 A／B／C 自行勾選；不影響主頁。首次沿用主頁條件，之後保留上次回測設定。",11f))
        root.addView(NeonUi.button(this,"查看完整回測條件"){if(readTrading(true)!=null)showRules(BtSettingsCodec.encode(draft()))})
        root.addView(label("開始時保存 ABC、產業、持倉上限及獲利目標；執行中修改僅供下次回測。",11f))
        runButton=NeonUi.button(this,"開始自動回測",NeonUi.mint){launch()};root.addView(runButton)
        root.addView(NeonUi.button(this,"停止本次回測",NeonUi.amber){store.update(store.active(),"使用者停止回測；未完成結果不列為績效","CANCELED");WorkManager.getInstance(this).cancelUniqueWork("historical-backtest");refreshStatus()})
        root.addView(NeonUi.gap(this,8))
        root.addView(NeonUi.row(this,listOf(NeonUi.button(this,"清除歸零",NeonUi.amber){clearCurrent()},NeonUi.button(this,"回測日誌"){startActivity(Intent(this,BacktestJournalActivity::class.java))})))
        root.addView(label("歸零損益、持倉與成交；保留條件設定及歷史日誌。",11f))
        status=label("準備中",13f,NeonUi.ink);root.addView(status);root.addView(NeonUi.gap(this,14))
        results=NeonUi.vertical(this);root.addView(results)
        root.addView(NeonUi.button(this,"查看模型假設與規則"){assumptions()});refreshStatus()
    }
    private fun tradingControls():LinearLayout {
        val panel=NeonUi.vertical(this).apply{tag="backtest-trading-controls";background=NeonUi.panel(this@BacktestActivity,NeonUi.cyan);setPadding(dp(12),dp(12),dp(12),dp(12))}
        panel.addView(label("交易設定",17f,NeonUi.ink,true));panel.addView(NeonUi.gap(this,8))
        fun field(title:String,value:String,unit:String,tagName:String,color:Int,decimal:Boolean):Pair<LinearLayout,EditText>{
            val box=NeonUi.vertical(this).apply{background=NeonUi.panel(this@BacktestActivity,color);setPadding(dp(10),dp(10),dp(10),dp(10))}
            box.addView(label(title,13f,NeonUi.ink,true))
            val input=EditText(this).apply{tag=tagName;contentDescription=title;setText(value);textSize=28f;setTextColor(color);setSelectAllOnFocus(true);setSingleLine();minHeight=dp(52);inputType=android.text.InputType.TYPE_CLASS_NUMBER or if(decimal)android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL else 0}
            box.addView(input);box.addView(label(unit,11f));return box to input
        }
        val limit=field("最高持倉檔數",lastLimit.toString(),"檔 · 1 以上整數","max-holding-stocks",NeonUi.cyan,false);maxHoldings=limit.second
        val target=field("獲利賣出",btPercent(lastTarget),"% · 扣除買賣費稅","target-net-pct",NeonUi.pink,true);profitTarget=target.second
        panel.addView(NeonUi.row(this,listOf(limit.first,target.first)));panel.addView(NeonUi.gap(this,8))
        panel.addView(label("按不同股號計算持倉。滿額暫停新增股票，賣出清空該股後釋出名額；同股隔日再入選仍可加買一張，受可用資金限制。",12f))
        panel.addView(label("各張獨立計算淨利，最早隔日賣出。超過 5 個日曆日改掛扣費稅保本價，仍虧損則續抱。",12f))
        panel.addView(NeonUi.button(this,"折讓金 · 月成交 0.05%／0.1%"){rebateRules()})
        return panel
    }
    private fun readTrading(showErrors:Boolean):Pair<Int,Double>? {
        val limit=maxHoldings.text.toString().trim().toIntOrNull()
        val target=profitTarget.text.toString().trim().toDoubleOrNull()
        val limitOk=limit!=null&&limit>0
        val targetOk=target!=null&&target.isFinite()&&target in 0.01..1000.0
        if(showErrors){maxHoldings.error=if(limitOk)null else "請填 1 以上整數（最多 2147483647）";profitTarget.error=if(targetOk)null else "請填 0.01～1000%"}
        if(!limitOk||!targetOk)return null
        lastLimit=limit!!;lastTarget=target!!;return limit to target
    }
    override fun onResume(){super.onResume();if(!viewingLog){reloadApplied();handler.post(refresh)}}
    override fun onPause(){handler.removeCallbacks(refresh);if(!viewingLog&&::codes.isInitialized){reloadApplied();if(readTrading(false)!=null)store.saveDraft(draft())};super.onPause()}
    private fun pick(first:Boolean){val d=if(first)start else end;val dialog=DatePickerDialog(this,{_,y,m,day->val date=LocalDate.of(y,m+1,day);if(first){start=date;startButton.text="起始 $start"}else{end=date;endButton.text="結束 $end"}},d.year,d.monthValue-1,d.dayOfMonth);dialog.datePicker.minDate=LocalDate.of(2016,1,1).atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli();dialog.datePicker.maxDate=today.minusDays(1).atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli();dialog.show()}
    private fun launch(){
        val cash=capital.text.toString().toDoubleOrNull();val raw=codes.text.toString().trim()
        if(start>end||start<LocalDate.of(2016,1,1)||end>=today||ChronoUnit.DAYS.between(start,end)>730){Toast.makeText(this,"請選擇有效日期，區間最多 2 年",Toast.LENGTH_LONG).show();return}
        if(cash==null||!cash.isFinite()||cash !in 50000.0..100000000.0){capital.error="本金限 50,000～100,000,000";return}
        val trading=readTrading(true)?:return
        if(raw.split(Regex("[,，\\s]+" )).filter{it.isNotBlank()}.any{!it.matches(Regex("[1-9][0-9]{3}"))}){codes.error="請輸入四碼股票代號，以逗號分隔";return}
        val id=UUID.randomUUID().toString();val s=BtSettingsCodec.capture(this,start,end,cash,raw).copy(rules=btRules.mapValues{it.value.toSet()},sectors=btSectors.toSet(),maxHoldingStocks=trading.first,targetNetPct=trading.second)
        if(s.sectors.isEmpty()){Toast.makeText(this,"請至少選擇一個產業類型",Toast.LENGTH_LONG).show();return}
        if(s.rules.values.all{it.isEmpty()}){Toast.makeText(this,"請至少勾選一區的掃描條件",Toast.LENGTH_LONG).show();return}
        val data=workDataOf("id" to id)
        val request=OneTimeWorkRequestBuilder<BacktestWorker>().setInputData(data).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        // REPLACE also handles a process stopped before the old progress label was saved.
        runCatching{store.begin(id,s);WorkManager.getInstance(this).enqueueUniqueWork("historical-backtest",ExistingWorkPolicy.REPLACE,request)}
            .onFailure{store.update(id,"無法開始回測：${it.message}","ERROR");Toast.makeText(this,"無法開始：${it.message}",Toast.LENGTH_LONG).show()}
        displayed="";refreshStatus()
    }
    private fun refreshStatus(){
        status.text=store.status()
        runButton.text=if(store.state()=="RUNNING")"重新開始本次回測" else "開始自動回測"
        val id=store.resultId();if(id.isBlank()){if(displayed!="zero"){showZero();displayed="zero"};return};if(displayed==id)return
        val data=store.result()?:return
        if(displayed!=id){displayed=id;showResult(data)}
    }
    private fun draft():BtSettings {
        readTrading(false)
        return BtSettingsCodec.capture(this,start,end,capital.text.toString().toDoubleOrNull()?.takeIf{it.isFinite()&&it>0}?:3000000.0,codes.text.toString().trim()).copy(rules=btRules.mapValues{it.value.toSet()},sectors=btSectors.toSet(),maxHoldingStocks=lastLimit,targetNetPct=lastTarget)
    }
    private fun refreshRuleButtons(){ruleButtons.forEach{(type,button)->button.text="${type.name.take(1)} 條件 (${btRules[type].orEmpty().size})"}}
    internal fun applyRules(type:RadarType,selected:Set<String>){
        btRules=btRules+mapOf(type to selected.intersect(ScanConditions.forRadar(type).map{it.id}.toSet()))
        store.saveDraft(draft());refreshRuleButtons()
    }
    private fun editRules(type:RadarType){
        val options=ScanConditions.forRadar(type);val selected=btRules[type].orEmpty()
        val content=NeonUi.vertical(this).apply{setPadding(dp(14),dp(10),dp(14),dp(10))}
        content.addView(label("勾選才列入本區門檻；全部取消則該區不入選。",12f,NeonUi.ink))
        val boxes=options.map{rule->
            CheckBox(this).apply{text=rule.label;textSize=13f;setTextColor(NeonUi.ink);isChecked=rule.id in selected;minHeight=dp(48);setPadding(0,dp(5),0,dp(5));buttonTintList=android.content.res.ColorStateList.valueOf(NeonUi.cyan)}.also{box->
                if(rule.id=="extra_eps")content.addView(label("額外查核 · 缺歷史資料時不通過",12f,NeonUi.amber,true))
                content.addView(box)
            }
        }
        content.addView(NeonUi.row(this,listOf(NeonUi.button(this,"全不選"){boxes.forEach{it.isChecked=false}},NeonUi.button(this,"恢復基本條件"){boxes.forEachIndexed{i,box->box.isChecked=options[i].defaultEnabled}})))
        showSelectionDialog("${type.name.take(1)} 區回測條件",content){applyRules(type,options.filterIndexed{i,_->boxes[i].isChecked}.map{it.id}.toSet())}
    }
    private fun refreshSectorButton(){
        val names=btSectors.sortedBy{it.ordinal}.map{it.label}
        sectorButton.text="產業類型 · "+when{names.isEmpty()->"尚未選擇";btSectors.size==StockSector.entries.size->"全部";names.size<=2->names.joinToString("、");else->"${names.take(2).joinToString("、")} 等 ${names.size} 類"}
    }
    internal fun applySectors(selected:Set<StockSector>){btSectors=selected.toSet();store.saveDraft(draft());refreshSectorButton()}
    private fun editSectors(){
        val options=StockSector.entries
        val content=NeonUi.vertical(this).apply{setPadding(dp(6),dp(4),dp(6),dp(4))}
        content.addView(label("可複選；僅掃描勾選的產業。分類依目前資料來源，並非歷史產業成分。",12f,NeonUi.ink))
        val boxes=options.map{sector->CheckBox(this).apply{text=sector.label;textSize=14f;setTextColor(NeonUi.ink);isChecked=sector in btSectors;minHeight=dp(48);buttonTintList=android.content.res.ColorStateList.valueOf(NeonUi.cyan)}.also{content.addView(it)}}
        content.addView(NeonUi.row(this,listOf(NeonUi.button(this,"產業全選"){boxes.forEach{it.isChecked=true}},NeonUi.button(this,"全不選"){boxes.forEach{it.isChecked=false}})))
        showSelectionDialog("回測股票產業類型",content){applySectors(options.filterIndexed{i,_->boxes[i].isChecked}.toSet())}
    }
    private fun showSelectionDialog(title:String,content:LinearLayout,onApply:()->Unit){
        val height=minOf(420,(resources.displayMetrics.heightPixels/resources.displayMetrics.density).toInt()-240).coerceAtLeast(140)
        lateinit var dialog:androidx.appcompat.app.AlertDialog
        val viewport=NeonUi.vertical(this).apply{
            setPadding(dp(14),dp(14),dp(14),dp(14))
            addView(label(title,20f,NeonUi.ink,true))
            addView(NeonUi.gap(this@BacktestActivity,10))
            addView(ScrollView(this@BacktestActivity).apply{addView(content)},LinearLayout.LayoutParams(-1,dp(height)))
            addView(NeonUi.gap(this@BacktestActivity,10))
            addView(NeonUi.row(this@BacktestActivity,listOf(
                NeonUi.button(this@BacktestActivity,"取消",NeonUi.muted){dialog.dismiss()},
                NeonUi.button(this@BacktestActivity,"套用",NeonUi.mint){onApply();dialog.dismiss()}
            )))
        }
        dialog=MaterialAlertDialogBuilder(this).setBackground(NeonUi.panel(this,NeonUi.cyan)).setView(viewport).create()
        dialog.show()
    }
    internal fun clearCurrent(){
        val wasRunning=store.state()=="RUNNING"
        runCatching{store.saveDraft(draft());store.resetCurrent()}.onSuccess{
            // The run ID is already invalidated, so even a late worker cannot restore it.
            if(wasRunning)runCatching{WorkManager.getInstance(this).cancelUniqueWork("historical-backtest")}
            displayed="";refreshStatus()
            Toast.makeText(this,"已歸零，歷史日誌仍保留",Toast.LENGTH_SHORT).show()
        }.onFailure{Toast.makeText(this,"歸零失敗：${it.message}",Toast.LENGTH_LONG).show()}
    }
    private fun showZero(){
        results.removeAllViews()
        results.addView(NeonUi.row(this,listOf(NeonUi.tile(this,"目前總損益","0 元","等待新的回測結果",NeonUi.cyan),NeonUi.tile(this,"目前持倉／成交","0 / 0","歷史紀錄請至回測日誌",NeonUi.cyan))))
    }
    private fun showRules(settings:JSONObject){BtSnapshotDialog.show(this,settings,afterApply={if(!viewingLog)reloadApplied()})}
    private fun reloadApplied(){
        val revision=getSharedPreferences("scanner_filters",0).getString(BtRuleApply.REVISION,"").orEmpty()
        if(revision==appliedRevision||!::codes.isInitialized)return
        val saved=runCatching{BtSettingsCodec.decode(store.configuration()!!)}.getOrNull()?:return
        appliedRevision=revision;start=saved.start;end=saved.end;btRules=saved.rules;btSectors=saved.sectors
        startButton.text="起始 $start";endButton.text="結束 $end";capital.setText(saved.capital.toLong().toString());codes.setText(saved.codes)
        lastLimit=saved.maxHoldingStocks?:25;lastTarget=saved.targetNetPct
        maxHoldings.setText(lastLimit.toString());profitTarget.setText(btPercent(saved.targetNetPct))
        refreshRuleButtons();refreshSectorButton();store.saveDraft(saved)
    }
    private fun openRobotReport(id:String,key:String){
        val root=NeonUi.vertical(this).apply{setPadding(dp(14),dp(12),dp(14),dp(24))}
        val scroll=ScrollView(this).apply{setBackgroundColor(Color.rgb(4,17,30));addView(root)};setContentView(scroll)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll){v,insets->val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets}
        root.addView(NeonUi.row(this,listOf(label("機器人測試詳情",22f,NeonUi.ink,true),NeonUi.button(this,"返回"){finish()})))
        val loading=label("讀取組合紀錄…",13f);root.addView(loading)
        results=NeonUi.vertical(this);root.addView(results)
        Thread{
            val report=runCatching{BtRobotStore(this).report(id,key)}
            runOnUiThread{if(!isFinishing&&!isDestroyed){
                root.removeView(loading)
                report.fold({data->
                    if(data==null){results.addView(label("找不到此組合紀錄",14f));return@fold}
                    root.addView(NeonUi.row(this,listOf(NeonUi.button(this,"條件快照"){showRules(data.getJSONObject("settings"))},NeonUi.button(this,"匯出日誌"){export(data)})),1)
                    showResult(data)
                },{results.addView(label("讀取失敗：${it.message}",14f))})
            }}
        }.start()
    }
    private fun openJournal(id:String){
        val root=NeonUi.vertical(this).apply{setPadding(dp(14),dp(12),dp(14),dp(24))}
        val scroll=ScrollView(this).apply{setBackgroundColor(Color.rgb(4,17,30));addView(root)};setContentView(scroll)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll){v,insets->val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets}
        root.addView(NeonUi.row(this,listOf(label("日誌詳情",25f,NeonUi.ink,true),NeonUi.button(this,"返回"){finish()})))
        root.addView(label("日誌 ${id.take(8)} · 獨立保存的回測紀錄",12f,NeonUi.cyan))
        val loading=label("讀取中…",13f);root.addView(loading)
        results=NeonUi.vertical(this);root.addView(results)
        Thread{
            val data=store.log(id)
            runOnUiThread{
                if(!isFinishing&&!isDestroyed){
                    root.removeView(loading)
                    if(data==null){results.addView(label("找不到這筆日誌，或檔案無法讀取。",14f,NeonUi.amber));return@runOnUiThread}
                    val details=NeonUi.vertical(this)
                    details.addView(label("${BacktestJournalUi.state(data.optString("state", "DONE"))} · App ${data.optString("appVersion","舊版未記錄")}",12f,NeonUi.ink))
                    details.addView(label("啟動 ${BacktestJournalUi.time(data.optLong("startedAt"))}\n${if(data.optBoolean("legacyImported"))"檔案" else "結束"} ${BacktestJournalUi.time(data.optLong("finishedAt"))} · 台北時間",11f))
                    details.addView(NeonUi.row(this,listOf(NeonUi.button(this,"條件快照"){showRules(data.optJSONObject("settings")?:JSONObject())},NeonUi.button(this,"匯出日誌"){export(data)})))
                    details.addView(NeonUi.gap(this,12));root.addView(details,root.indexOfChild(results))
                    if(data.has("run"))showResult(data) else{
                        results.addView(label(data.optString("message","此回測尚未完成"),15f,NeonUi.amber,true))
                        results.addView(label("未完成的回測不提供最終績效；設定與執行狀態已保留。",12f))
                        results.addView(NeonUi.gap(this,12));results.addView(label(BacktestJournalUi.describe(data.optJSONObject("settings")?:JSONObject()),13f))
                    }
                }
            }
        }.start()
    }
    internal fun showResult(data:JSONObject){
        results.removeAllViews();val s=data.getJSONObject("settings");val r=data.getJSONObject("run");val pnl=r.getDouble("profit");val hasRebate=data.optInt("strategyVersion",1)>=3;val legacy=data.optInt("strategyVersion",1)<2
        val curve=BacktestDailyLedger.rows(data);val holdings=r.getJSONArray("holdings");val trades=BacktestTradeLedger.rows(r.getJSONArray("trades"));val tradesByDay=BacktestTradeLedger.byDate(trades)
        val panel=NeonUi.vertical(this).apply{background=NeonUi.panel(this@BacktestActivity,tint(pnl));setPadding(dp(14),dp(14),dp(14),dp(14))}
        panel.addView(label(if(legacy)"舊版開盤策略報告" else "截止日總損益",16f,NeonUi.ink,true))
        panel.addView(label(signed(pnl)+" 元",34f,tint(pnl),true))
        panel.addView(label("報酬率 ${String.format(Locale.US,"%+.2f",pnl/s.getDouble("capital")*100)}% · 初始 ${money(s.getDouble("capital"))}",12f))
        panel.addView(label("${curve.getJSONObject(0).getString("date")} → ${curve.getJSONObject(curve.length()-1).getString("date")}",12f))
        panel.addView(label(BacktestJournalUi.tradingSummary(s),12f,NeonUi.cyan,true))
        panel.addView(label(if(hasRebate)"總損益＝已實現＋未實現＋應收股息＋估計應收折讓金。" else "總損益＝已實現＋未實現＋應收股息；本次舊策略未計折讓金。",11f))
        if(!viewingLog&&data.getString("id")!=store.active())results.addView(label("以下為上次已完成報告，本次尚無新結果。",12f,NeonUi.amber))
        results.addView(panel);results.addView(NeonUi.gap(this,8))
        val realized=r.getDouble("realized");val unrealized=r.optDouble("unrealized",pnl-realized-r.getDouble("dividend")-r.optDouble("rebateAccrued",0.0))
        results.addView(BacktestProfitPanel(this,trades,holdings,realized,unrealized,r.getInt("closed"),curve.getJSONObject(curve.length()-1).getString("date")))
        results.addView(label("應收股息 ${money(r.getDouble("dividend"))} · 可用資金 ${money(r.getDouble("cash"))}",12f))
        results.addView(label("累計買入 ${r.optInt("buys",trades.length()-r.getInt("closed"))} 張 · 累計賣出 ${r.getInt("closed")} 張",13f,NeonUi.ink,true))
        results.addView(label("最大回撤 ${String.format(Locale.US,"%.2f%%",r.getDouble("drawdown"))} · 依每日含留倉的淨資產",12f,NeonUi.amber))
        results.addView(label("未達標持股可能有虧損，不能只看已賣出交易的勝率。",11f))
        results.addView(PaperEquityView(this,s.getDouble("capital"),(0 until curve.length()).map{curve.getJSONObject(it).getDouble("equity")}),LinearLayout.LayoutParams(-1,dp(150)))
        results.addView(label("資料涵蓋 ${data.getInt("loaded")} / ${data.getInt("requested")} 檔",16f,NeonUi.ink,true))
        results.addView(label("缺少歷史查核資料 ${data.optInt("pendingChecks")} 項次 · 缺資料的條件不能通過",11f))
        results.addView(label(data.getString("note"),12f,NeonUi.amber));results.addView(NeonUi.gap(this,8))
        if(!legacy)results.addView(NeonUi.button(this,"查看本次 ABC、產業與交易規則"){showRules(s)})
        if(r.has("limited"))results.addView(NeonUi.button(this,"本次當日限價名單 · ${r.getJSONArray("limited").length()} 筆",NeonUi.amber){
            val frozen=JSONObject().put("source","報告 ${data.optString("id").take(8)} · ${s.optString("start")} → ${s.optString("end")}").put("policy",s.optJSONObject("stockPolicy")).put("rows",r.getJSONArray("limited"))
            java.io.File(cacheDir,"view-limited.json").writeText(frozen.toString())
            startActivity(Intent(this,StockToolsActivity::class.java).putExtra("mode","LIMIT").putExtra("scope",s.optString("stockScope","BACKTEST")).putExtra("reportLimited",true))
        })
        if(hasRebate){results.addView(BacktestRebatePanel(this,r));results.addView(NeonUi.gap(this,12))}
        results.addView(label("每日買賣紀錄",17f,NeonUi.ink,true))
        results.addView(label("金額單位：元。淨利為當日賣出的已實現損益；留倉金額為未賣股票的含費成本。",11f))
        results.addView(label("點買入／賣出展開個股。日漲跌＝收盤對前收；個股淨利已扣費稅，折讓金另列。",11f))
        val dailyHost=NeonUi.vertical(this);results.addView(dailyHost)
        var pageEnd=curve.length()
        fun dailyPage(){
            dailyHost.removeAllViews();val first=maxOf(0,pageEnd-30)
            if(curve.length()>30){
                dailyHost.addView(label("第 ${first+1}～$pageEnd 天／共 ${curve.length()} 個交易日",11f))
                dailyHost.addView(NeonUi.row(this,listOf(
                    NeonUi.button(this,"更早 30 天"){pageEnd=first;dailyPage()}.apply{isEnabled=first>0},
                    NeonUi.button(this,"較新 30 天"){pageEnd=minOf(curve.length(),pageEnd+30);dailyPage()}.apply{isEnabled=pageEnd<curve.length()}
                )))
            }
            for(i in first until pageEnd){val day=curve.getJSONObject(i);dailyHost.addView(BacktestDailyCard(this,day,tradesByDay[day.getString("date")].orEmpty()));dailyHost.addView(NeonUi.gap(this,8))}
        }
        dailyPage()
        results.addView(NeonUi.gap(this,8));results.addView(label("成交明細 · 最近 30 筆",17f,NeonUi.ink,true))
        for(i in (trades.length()-1) downTo maxOf(0,trades.length()-30)){
            val t=trades.getJSONObject(i)
            results.addView(label(t.getString("date"),13f,NeonUi.ink,true));results.addView(BacktestTradeCard(this,t));results.addView(NeonUi.gap(this,8))
        }
        results.addView(label("截止日留倉 · ${holdings.length()} 張",17f,NeonUi.ink,true))
        for(i in 0 until minOf(30,holdings.length())){
            val h=holdings.getJSONObject(i);results.addView(label("${h.getString("code")} ${h.getString("name")} · 1 張\n買入 ${h.optString("entryDate","舊版未記錄")} ${h.optString("entryTime")} · 目標 ${h.optDouble("target")} 元\n最新估值 ${h.getString("markDate")} / ${h.getDouble("mark")} 元 · ${signed(h.optDouble("unrealized",0.0))}",12f));results.addView(NeonUi.gap(this,8))
        }
        results.addView(label("每日紀錄可翻頁，展開後可載入該日所有成交；下方摘要列最近 30 筆成交／30 張持倉。完整資料可匯出。",11f))
        results.addView(NeonUi.button(this,"匯出完整報告與每日買賣明細",NeonUi.mint){export(data)})
        results.addView(NeonUi.button(this,"查看未買入與資料排除原因"){
            val a=data.getJSONArray("excluded");val skipped=r.optJSONArray("skipped")?:org.json.JSONArray()
            val lines=(0 until minOf(100,skipped.length())).map{val row=skipped.getJSONObject(it);"${row.getString("date")} ${row.getString("code")}：${row.getString("reason")}"}+(0 until minOf(100,a.length())).map{a.getString(it)}
            MaterialAlertDialogBuilder(this).setTitle("未買 ${skipped.length()} 次／資料排除 ${a.length()} 檔").setMessage(if(lines.isEmpty())"無" else lines.joinToString("\n")+"\n完整清單見匯出報告").setPositiveButton("關閉",null).show()
        })
    }
    private fun rebateRules(){MaterialAlertDialogBuilder(this).setTitle("折讓金").setMessage(
        "按每個日曆月的買入＋賣出成交金額合計，不含費稅。每一筆成交都保存時間、股數、價格與成交額。\n\n"+
        "合計超過 50,000,000 元：整月乘以 0.1%。未超過（含剛好 5,000 萬）：整月乘以 0.05%。跨月重新累計。\n\n"+
        "達高門檻當天，補列當月先前成交的差額，不回改過去每日損益。折讓金計入總利潤，列為估計應收款，不當作可用資金再買入。截止日未滿月依截至當日成交額估算。\n\n"+
        "保本與獲利目標出場仍以扣除買賣費稅的交易淨利判斷，不用折讓金或股息抵虧損。舊回測日誌保留原策略與原績效，新規則需重新執行回測。"
    ).setPositiveButton("了解",null).show()}
    private fun assumptions(){MaterialAlertDialogBuilder(this).setTitle("尾盤買入／自訂淨利策略").setMessage(
        "1. 啟動時保存回測專用 ABC 勾選條件、產業、持倉檔數上限與獲利百分比。A/B 依現有掃描器使用排除當日的日線；C 用當日日線。歷史 EPS／法人等缺資料會阻擋已勾條件，不會偷偷略過。\n\n"+
        "2. 每日入選股票各買 1,000 股，同日跨 ABC 去重；不同日重複入選可以再買一張。不同股號合計不能超過設定的持倉檔數；同股多張只占一檔，全部賣出該股才釋出名額。名額或現金不足會記錄未買原因；競爭時依股號排序。\n\n"+
        "3. 以當日收盤價加不利滑價 0.1% 模擬尾盤買入，時間記為 13:30（模型假設，可能與延後收盤不同）。C 完整收盤訊號與同價成交無法證明可實際執行，屬理想化同收盤模型，可能高估績效。\n\n"+
        "4. 每張獨立計算目標：扣買入手續費及賣出費稅後，淨利率達到使用者設定值（預設 3%），以含買入費的成本為分母，不含股息或折讓金。最早下一交易日才可賣。超過 5 個日曆日（買入日期差 ≥ 6）改掛扣費稅後的保本價；虧損仍續抱。不把折讓金或股息用來抵交易虧損。\n\n"+
        "5. 開盤扣滑價後已達標，記 09:00 開盤模型；否則以日最高價扣滑價判斷是否可達目標，記 09:00–13:30『盤中觸價、確切時間未知』，不編造分鐘。日量只作總量上限，不證明委託排隊或價位深度可成交。\n\n"+
        "6. 買賣費率各 0.1425%，最低 20 元；賣出稅 0.3%；滑價 0.1%，依跳動單位取整。應收股息與折讓金不再投入。每日先處理舊倉賣出、再處理尾盤買入。\n\n"+
        "7. 截止日不強制賣出未達標股票；總損益含未實現損益、股息與估計折讓金。月買賣成交總額超過 5,000 萬按整月 0.1%，否則 0.05%，未滿月依截至當日成交額估算。只看已賣出的高勝率可能掩蓋留倉虧損。現存股票名單、目前產業分類與拆併股排除仍有樣本偏差。"
    ).setPositiveButton("了解",null).show()}
    private fun export(data:JSONObject){runCatching{
        val enriched=BacktestDailyLedger.enrich(data)
        val dir=File(cacheDir,"paper").apply{mkdirs()};val id=data.getString("id").take(8)
        val files=mutableListOf(File(dir,"回測報告_$id.json").apply{writeText(enriched.toString(2))})
        fun csv(name:String,array:org.json.JSONArray,fields:List<String>,header:String){
            val lines=mutableListOf(header)
            for(i in 0 until array.length()){val row=array.getJSONObject(i);lines.add(fields.joinToString(","){"\""+(if(row.isNull(it))"" else row.optString(it,"")).replace("\"","\"\"")+"\""})}
            files.add(File(dir,"${name}_$id.csv").apply{writeText("\uFEFF"+lines.joinToString("\r\n"))})
        }
        val run=enriched.optJSONObject("run")
        if(run!=null){
        csv("回測交易",run.getJSONArray("trades"),BacktestTradeLedger.csvFields,BacktestTradeLedger.csvHeader)
        run.optJSONArray("rebateMonths")?.let{csv("每月折讓金",it,listOf("month","buyTrades","sellTrades","buyAmount","sellAmount","turnover","rate","amount"),"月份,買入筆數,賣出筆數,買入成交額,賣出成交額,買賣總成交額,適用折讓率,估計應收折讓金")}
        csv("每日總覽",run.getJSONArray("curve"),BacktestDailyLedger.csvFields,BacktestDailyLedger.csvHeader)
        csv("截止日留倉",run.getJSONArray("holdings"),BacktestHoldingLedger.csvFields,BacktestHoldingLedger.csvHeader)
        }
        val uris=ArrayList(files.map{FileProvider.getUriForFile(this,"$packageName.posters",it)})
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"儲存回測報告"))
    }.onFailure{Toast.makeText(this,"匯出失敗：${it.message}",Toast.LENGTH_LONG).show()}}
}
