package com.rex.twboardingscanner.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rex.twboardingscanner.data.*
import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.backtest.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.Locale

/** One shared, compact entry to stock restrictions and scoped saved-data search. */
class StockToolsActivity:AppCompatActivity(){
    companion object{
        fun open(c:android.content.Context,mode:String,scope:StockScope=StockScope.SCANNER){c.startActivity(Intent(c,StockToolsActivity::class.java).putExtra("mode",mode).putExtra("scope",scope.name))}
    }
    private lateinit var store:StockPolicyStore
    private lateinit var body:LinearLayout
    private lateinit var summary:TextView
    private var scope=StockScope.SCANNER
    private var mode="BAN"
    private val handler=Handler(Looper.getMainLooper())
    private val executor=Executors.newFixedThreadPool(2)
    private var epoch=0
    private var directory=emptyList<StockInfo>()
    private var lookupRefresh:(()->Unit)?=null
    private fun label(s:String,size:Float=12f,color:Int=NeonUi.muted,bold:Boolean=false)=NeonUi.label(this,s,size,color,bold)
    private fun card(accent:Int=NeonUi.cyan)=NeonUi.vertical(this).apply{background=NeonUi.panel(context,accent);setPadding(NeonUi.dp(context,12),NeonUi.dp(context,12),NeonUi.dp(context,12),NeonUi.dp(context,12))}
    private fun field(hint:String)=EditText(this).apply{this.hint=hint;isSingleLine=true;setTextColor(NeonUi.ink);setHintTextColor(NeonUi.muted);textSize=16f;minHeight=NeonUi.dp(context,48);inputType=InputType.TYPE_CLASS_NUMBER}
    private fun safe(action:()->Unit){runCatching(action).onFailure{Toast.makeText(this,it.message?:"操作失敗",Toast.LENGTH_LONG).show()}}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);store=StockPolicyStore(this)
        mode=intent.getStringExtra("mode")?:"BAN";scope=runCatching{StockScope.valueOf(intent.getStringExtra("scope")?:"SCANNER")}.getOrDefault(StockScope.SCANNER)
        val root=NeonUi.vertical(this).apply{setPadding(NeonUi.dp(context,14),NeonUi.dp(context,12),NeonUi.dp(context,14),NeonUi.dp(context,24))}
        val scroll=ScrollView(this).apply{setBackgroundColor(android.graphics.Color.rgb(4,17,30));addView(root)};setContentView(scroll)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll){v,i->val b=i.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());v.setPadding(b.left,b.top,b.right,b.bottom);i}
        root.addView(NeonUi.row(this,listOf(label("股票管理中心",22f,NeonUi.ink,true),NeonUi.button(this,"返回"){finish()})))
        root.addView(label("禁股優先 · 限價放行可分區設定",12f,NeonUi.cyan));root.addView(NeonUi.gap(this,12))
        root.addView(NeonUi.row(this,listOf("BAN" to "禁股名單","LIMIT" to "限價名單","SEARCH" to "搜尋股票").map{(key,title)->NeonUi.button(this,title,if(key=="BAN")NeonUi.pink else NeonUi.cyan){mode=key;render()}.apply{tag="tools-$key"}}))
        root.addView(NeonUi.gap(this,10));summary=label("",12f,NeonUi.cyan);root.addView(summary)
        root.addView(NeonUi.gap(this,10));body=NeonUi.vertical(this);root.addView(body)
        directory=StockDirectory(this).cached();render()
        if(directory.isEmpty()||System.currentTimeMillis()-(directory.minOfOrNull{it.fetchedAt}?:0)>6*3600000L)executor.execute{
            val data=runCatching{StockDirectory(this).refresh()}.getOrDefault(directory)
            runOnUiThread{if(!isFinishing&&!isDestroyed){directory=data;lookupRefresh?.invoke()}}
        }
    }
    private fun render(){
        epoch++;lookupRefresh=null;body.removeAllViews();summary.text=store.read().summary()
        when(mode){"LIMIT"->limits();"SEARCH"->search();else->bans()}
    }
    private fun inputLookup(parent:LinearLayout,onCode:(String,LinearLayout)->Unit={_,_->}){
        val input=field("輸入 4 位股號，例如 2330").apply{tag="stock-code-input"};parent.addView(input)
        val info=NeonUi.vertical(this);parent.addView(info);val results=NeonUi.vertical(this);parent.addView(results)
        var code="";var pending:Runnable?=null
        fun show(){
            info.removeAllViews();if(!code.matches(Regex("[1-9][0-9]{3}")))return
            val stock=directory.firstOrNull{it.code==code}
            val panel=card(if(code in store.read().banned)NeonUi.pink else NeonUi.cyan)
            panel.addView(label("$code  ${stock?.name?:"名稱待查核"}",19f,NeonUi.ink,true))
            if(stock==null)panel.addView(label("本機尚無公司資料，正在載入或暫時無法取得；仍可按股號加入禁股。",12f)) else {
                panel.addView(label("${if(stock.market=="TWSE")"上市" else "上櫃"} · ${stock.sector}",12f,NeonUi.cyan))
                panel.addView(NeonUi.gap(this,8))
                panel.addView(NeonUi.row(this,listOf(NeonUi.tile(this,"最近報價",String.format(Locale.TAIWAN,"%,.2f",stock.price),"非即時成交價",NeonUi.pink),NeonUi.tile(this,"成交量",String.format(Locale.TAIWAN,"%,d",stock.volume),"張 · 官方價量",NeonUi.cyan))))
                panel.addView(label("資料取得 ${BacktestJournalUi.time(stock.fetchedAt)}",10f))
            }
            panel.addView(NeonUi.gap(this,6));val banned=code in store.read().banned
            panel.addView(NeonUi.button(this,if(banned)"已加入禁股" else "加入禁股",NeonUi.pink){safe{store.ban(code,stock?.name?:"名稱待查核");Toast.makeText(this,"已儲存禁股；三區停止選入此股",Toast.LENGTH_SHORT).show();render()}}.apply{isEnabled=!banned;tag="add-ban"})
            info.addView(panel);info.addView(NeonUi.gap(this,10))
        }
        lookupRefresh={show()}
        input.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){
                epoch++;code=s.toString().trim();pending?.let{handler.removeCallbacks(it)};results.removeAllViews();show()
                if(code.matches(Regex("[1-9][0-9]{3}"))){val wanted=code;pending=Runnable{onCode(wanted,results)};handler.postDelayed(pending!!,250)}
            }
            override fun afterTextChanged(s:Editable?){}
        })
    }
    private fun bans(){
        body.addView(label("禁股名單",21f,NeonUi.ink,true));body.addView(label("加入後立即保存，套用主頁、歷史回測與機器人。既有持倉仍可賣出。",12f));body.addView(NeonUi.gap(this,8))
        inputLookup(body)
        body.addView(NeonUi.gap(this,12));body.addView(label("已禁用 ${store.read().banned.size} 檔",16f,NeonUi.pink,true))
        store.read().banned.sorted().forEach{code->val row=card(NeonUi.pink);row.addView(label("$code  ${store.name(code)}",17f,NeonUi.ink,true));row.addView(NeonUi.button(this,"解除禁股",NeonUi.muted){safe{store.change{it.copy(banned=it.banned-code)};render()}});body.addView(NeonUi.gap(this,8));body.addView(row)}
        if(store.read().banned.isEmpty())body.addView(label("尚未加入禁股。",13f))
        body.addView(NeonUi.gap(this,12));body.addView(label("修改名單會停止正在執行的回測、暫停機器人；已完成日誌保留。機器人下次開始以新名單建立批次，沿用資料快取。",11f,NeonUi.amber))
    }
    private fun selector(parent:LinearLayout,onSelect:()->Unit){
        val spinner=Spinner(this).apply{adapter=ArrayAdapter(this@StockToolsActivity,android.R.layout.simple_spinner_dropdown_item,StockScope.entries.map{it.label});setSelection(scope.ordinal)}
        parent.addView(spinner,LinearLayout.LayoutParams(-1,NeonUi.dp(this,48)))
        spinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:AdapterView<*>?){};override fun onItemSelected(p:AdapterView<*>?,v:android.view.View?,position:Int,id:Long){if(scope!=StockScope.entries[position]){scope=StockScope.entries[position];onSelect()}}}
    }
    private fun limits(){
        body.addView(label("最高股價",21f,NeonUi.ink,true))
        val amount=field("留白＝不限價格（元）").apply{inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL;setText(store.read().ceiling?.let{btPercent(it)}?:"");tag="price-ceiling"};body.addView(amount)
        body.addView(NeonUi.button(this,"儲存限價",NeonUi.mint){safe{val raw=amount.text.toString().trim();val value=if(raw.isEmpty())null else raw.toDoubleOrNull()?:error("請輸入有效金額");store.change{it.copy(ceiling=value)};render()}}.apply{tag="save-ceiling"})
        body.addView(label("超過金額才排除，等於上限可入選。回測使用各交易日收盤價；已放行的股可略過限價，仍需符合 ABC 條件。",12f))
        body.addView(NeonUi.gap(this,12));body.addView(label("當日限價名單",20f,NeonUi.amber,true));selector(body){render()}
        val saved=(if(intent.getBooleanExtra("reportLimited",false)&&scope.name==intent.getStringExtra("scope"))runCatching{JSONObject(java.io.File(cacheDir,"view-limited.json").readText())}.getOrNull()else null)?:store.candidates(scope);val rows=saved.optJSONArray("rows")?:JSONArray()
        body.addView(label(saved.optString("source","尚無完成掃描／回測"),12f,NeonUi.cyan))
        val prior=StockPolicy.read(saved.optJSONObject("policy"));if(saved.has("policy")&&prior!=store.read())body.addView(label("此為上次執行的快照；修改設定後請重新執行以產生新名單。",11f,NeonUi.amber))
        val dates=(0 until rows.length()).map{rows.getJSONObject(it).getString("date")}.distinct().sortedDescending()
        val cards=NeonUi.vertical(this)
        if(dates.isNotEmpty()){
            val days=Spinner(this).apply{adapter=ArrayAdapter(this@StockToolsActivity,android.R.layout.simple_spinner_dropdown_item,dates)};body.addView(days)
            days.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:AdapterView<*>?){};override fun onItemSelected(p:AdapterView<*>?,v:android.view.View?,pos:Int,id:Long){cards.removeAllViews();limitedCards(cards,rows,dates[pos])}}
        }else body.addView(label("本次沒有超過限價的入選候選股。執行掃描或回測後會按日列出。",13f))
        body.addView(cards);body.addView(NeonUi.gap(this,12));body.addView(label("${scope.label} · 已放行",17f,NeonUi.mint,true))
        store.read().overrides[scope].orEmpty().sorted().forEach{code->body.addView(NeonUi.button(this,"$code ${store.name(code)} · 管理放行",NeonUi.mint){allowDialog(code,store.name(code))})}
    }
    private fun limitedCards(parent:LinearLayout,rows:JSONArray,date:String){
        (0 until rows.length()).map{rows.getJSONObject(it)}.filter{it.getString("date")==date}.forEach{o->
            val code=o.getString("code");val name=o.getString("name");val p=store.read();val banned=code in p.banned
            val c=card(NeonUi.amber);c.addView(label("$code  $name",18f,NeonUi.ink,true));c.addView(label("${o.getString("date")} · ${o.optString("radar")} 區",11f))
            c.addView(label("${String.format(Locale.TAIWAN,"%,.2f",o.getDouble("price"))} 元",27f,NeonUi.amber,true))
            c.addView(NeonUi.button(this,if(banned)"已禁股，不能放行" else if(code in p.overrides[scope].orEmpty())"已放行 · 管理區域" else "選擇放行區域",NeonUi.mint){allowDialog(code,name)}.apply{isEnabled=!banned})
            parent.addView(NeonUi.gap(this,8));parent.addView(c)
        }
    }
    private fun allowDialog(code:String,name:String){
        val p=store.read();val checks=StockScope.entries.map{code in p.overrides[it].orEmpty()}.toBooleanArray()
        MaterialAlertDialogBuilder(this).setTitle("$code $name · 限價放行").setMultiChoiceItems(StockScope.entries.map{it.label}.toTypedArray(),checks){_,i,v->checks[i]=v}
            .setNegativeButton("取消",null).setPositiveButton("儲存"){_,_->safe{require(code !in store.read().banned){"此股已被禁用"};store.allow(code,StockScope.entries.filterIndexed{i,_->checks[i]}.toSet());render()}}.show()
    }
    private fun search(){
        body.addView(label("搜尋已保存資料",21f,NeonUi.ink,true));selector(body){render()}
        body.addView(label("按股號查詢所選區域的掃描／交易／留倉及限價紀錄；不會啟動全市場掃描。",12f));body.addView(NeonUi.gap(this,8))
        inputLookup(body){code,results->searchRecords(code,results)}
    }
    private fun searchRecords(code:String,results:LinearLayout){
        val generation=epoch;val selected=scope;val status=label("搜尋中…",13f,NeonUi.cyan);results.addView(status)
        val hits=NeonUi.vertical(this);results.addView(hits)
        var before=Long.MAX_VALUE;var offset=0;var total=0
        val more=NeonUi.button(this,"載入更多"){ };more.visibility=android.view.View.GONE;results.addView(more)
        fun alive()=generation==epoch&&!isFinishing&&!isDestroyed
        fun showHit(o:JSONObject,title:String,open:()->Unit){
            if(!alive())return
            total++;val c=card();c.addView(label(title,13f,NeonUi.cyan,true))
            val run=o.optJSONObject("run")?:JSONObject();val trades=run.optJSONArray("trades")?:JSONArray();val holdings=run.optJSONArray("holdings")?:JSONArray();val limited=run.optJSONArray("limited")?:JSONArray()
            val own=(0 until trades.length()).map{trades.getJSONObject(it)}.filter{it.optString("code")==code}
            val retained=(0 until holdings.length()).map{holdings.getJSONObject(it)}.filter{it.optString("code")==code}
            val restricted=(0 until limited.length()).map{limited.getJSONObject(it)}.filter{it.optString("code")==code}
            val name=(own+retained+restricted).firstOrNull()?.optString("name")?:code
            c.addView(label("$code $name",17f,NeonUi.ink,true));c.addView(label("買 ${own.count{it.optString("side")=="BUY"}} 筆 · 賣 ${own.count{it.optString("side")=="SELL"}} 筆 · 留倉 ${retained.size} 張 · 限價 ${restricted.size} 日",12f))
            own.lastOrNull()?.let{c.addView(label("最近 ${it.optString("date")} ${it.optString("time")} · ${if(it.optString("side")=="BUY")"買入" else "賣出"} ${it.optDouble("price")} 元",12f))}
            c.addView(NeonUi.button(this,"查看完整日誌"){open()});hits.addView(NeonUi.gap(this,8));hits.addView(c)
        }
        fun matches(o:JSONObject):Boolean{val r=o.optJSONObject("run")?:return false;return listOf("trades","holdings","limited","skipped").any{k->val a=r.optJSONArray(k)?:JSONArray();(0 until a.length()).any{a.getJSONObject(it).optString("code")==code}}}
        fun load(){
            more.visibility=android.view.View.GONE
            executor.execute{
                val outcome=runCatching{
                    if(selected==StockScope.SCANNER){
                        val snapshot=runCatching{JSONObject(java.io.File(filesDir,"scanner-search.json").readText())}.getOrNull()
                        val current=snapshot?.optJSONArray("rows")?:JSONArray()
                        val live=(0 until current.length()).map{current.getJSONObject(it)}.filter{it.optString("code")==code}
                        runOnUiThread{if(alive())live.forEach{o->val c=card(NeonUi.mint);c.addView(label("$code ${o.getString("name")}",17f,NeonUi.ink,true));c.addView(label("最近掃描 ${snapshot?.optString("date")} · ${o.getString("radar")} · ${o.getString("state")}",12f,NeonUi.mint));c.addView(label("${o.getDouble("price")} 元 · "+if(code in store.read().banned)"目前已禁股" else "保存的掃描結果",13f));hits.addView(c);hits.addView(NeonUi.gap(this,8));total++}}
                        val rows=SignalHistoryDb(this).queryByCode(code)
                        runOnUiThread{if(alive()){rows.forEach{row->val c=card();c.addView(label("${row.code} ${row.name}",17f,NeonUi.ink,true));c.addView(label("掃描日誌 · ${row.scanDate} · ${row.radar} 區 · ${row.price} 元",12f));c.addView(NeonUi.button(this,"查看日 K 圖"){StockChartDialog(this,MarketDataProvider(this),row.code,row.name,initial=row.chartBars).show()});hits.addView(c);hits.addView(NeonUi.gap(this,8));total++};status.text="找到 $total 筆掃描日誌（保留原始歷史，不代表目前仍入選）"}}
                    }else if(selected==StockScope.BACKTEST){
                        val bt=BacktestStore(this);val list=bt.history();var found=0
                        while(offset<list.size&&found<30&&alive()){val entry=list[offset++];val report=bt.log(entry.getString("id"))?:continue;if(matches(report)){found++;runOnUiThread{showHit(report,"回測 ${entry.getString("id").take(8)}"){startActivity(Intent(this,BacktestActivity::class.java).putExtra("journalId",entry.getString("id")))}}}
                        runOnUiThread{if(alive()){status.text="找到 $total 份回測 · 已查 $offset / ${list.size}";more.visibility=if(offset<list.size)android.view.View.VISIBLE else android.view.View.GONE}}
                    }else{
                        val robot=BtRobotStore(this);var inspected=0;var found=0;var hasMore=true
                        while(inspected<300&&found<30&&alive()){
                            val page=robot.searchPage(before);if(page.isEmpty()){hasMore=false;break}
                            for(row in page){if(!alive())break;before=row.getLong("n");inspected++;val report=robot.report(row.getString("session"),row.getString("key"))?:continue
                                if(matches(report)){found++;runOnUiThread{showHit(report,"機器人 ${row.getString("session").take(8)} · 第 ${report.optLong("robotSequence")} 組"){startActivity(Intent(this,BacktestActivity::class.java).putExtra("robotSession",row.getString("session")).putExtra("robotKey",row.getString("key")))}}}
                        }
                        offset+=inspected;val remaining=hasMore
                        runOnUiThread{if(alive()){status.text="找到 $total 份結果 · 已查 $offset 組"+(if(remaining)"，可繼續查詢" else "，搜尋完畢");more.visibility=if(remaining)android.view.View.VISIBLE else android.view.View.GONE}}
                    }
                }
                outcome.onFailure{e->runOnUiThread{if(alive())status.text="搜尋失敗：${e.message}"}}
            }
        }
        more.setOnClickListener{load()};load()
    }
    override fun onDestroy(){epoch++;handler.removeCallbacksAndMessages(null);executor.shutdownNow();super.onDestroy()}
}
