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
import com.rex.twboardingscanner.domain.RuleMetrics
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
    private lateinit var capital:EditText;private lateinit var codes:EditText;private lateinit var optimize:CheckBox
    private lateinit var status:TextView;private lateinit var results:LinearLayout
    private lateinit var runButton:com.google.android.material.button.MaterialButton
    private var displayed=""
    private val handler=Handler(Looper.getMainLooper())
    private val refresh=object:Runnable{override fun run(){refreshStatus();handler.postDelayed(this,2000)}}
    private fun dp(n:Int)=NeonUi.dp(this,n)
    private fun label(s:String,size:Float=13f,color:Int=NeonUi.muted,bold:Boolean=false)=NeonUi.label(this,s,size,color,bold)
    private fun money(v:Double)=String.format(Locale.TAIWAN,"%,.0f",v)
    private fun signed(v:Double)=(if(v>=0)"+" else "−")+money(kotlin.math.abs(v))
    private fun tint(v:Double)=if(v>=0)NeonUi.pink else NeonUi.mint
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);store=BacktestStore(this)
        val config=store.configuration();start=runCatching{LocalDate.parse(config?.getString("start"))}.getOrDefault(start);end=runCatching{LocalDate.parse(config?.getString("end"))}.getOrDefault(end)
        val root=NeonUi.vertical(this).apply{setPadding(dp(14),dp(12),dp(14),dp(24))}
        val scroll=ScrollView(this).apply{setBackgroundColor(Color.rgb(4,17,30));addView(root)};setContentView(scroll)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll){v,insets->val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets}
        root.addView(NeonUi.row(this,listOf(label("歷史回測",25f,NeonUi.ink,true),NeonUi.button(this,"返回"){finish()})))
        root.addView(label("自動篩選 → 模擬買賣 → 績效驗證",12f,NeonUi.mint));root.addView(NeonUi.gap(this,12))
        root.addView(label("日線研究版 · 前日訊號／次日開盤成交",13f,NeonUi.amber,true))
        root.addView(label("不是 13:20 分時重播；未使用歷史財報或法人快照。現存股票樣本有存活偏差，請連同資料涵蓋率閱讀結果。",12f))
        root.addView(NeonUi.gap(this,12))
        startButton=NeonUi.button(this,"起始 $start"){pick(true)};endButton=NeonUi.button(this,"結束 $end"){pick(false)}
        root.addView(startButton);root.addView(NeonUi.gap(this,6));root.addView(endButton)
        root.addView(label("2016 年至昨日；單次最多 2 年，可選單日。",11f))
        capital=EditText(this).apply{setText((config?.optDouble("capital",3000000.0)?:3000000.0).toLong().toString());hint="初始模擬本金";inputType=android.text.InputType.TYPE_CLASS_NUMBER;setTextColor(NeonUi.ink)}
        root.addView(label("模擬本金 TWD",12f));root.addView(capital)
        codes=EditText(this).apply{setText(config?.optString("codes")?:"");hint="全部股票（或輸入 2330, 3661…）";setTextColor(NeonUi.ink);setHintTextColor(NeonUi.muted)}
        root.addView(label("留空掃描現存上市櫃；全市場首次下載較久。",12f));root.addView(codes)
        optimize=CheckBox(this).apply{text="自動優化：60 日訓練 / 20 日驗證";setTextColor(NeonUi.cyan);textSize=13f;isChecked=config?.optBoolean("optimize",true)?:true};root.addView(optimize)
        root.addView(label("9 組候選配置；少於 80 個交易日改為基準回測。每次重新選參數只用較早資料，不保證提高勝率。",11f))
        runButton=NeonUi.button(this,"開始自動回測",NeonUi.mint){launch()};root.addView(runButton)
        root.addView(NeonUi.button(this,"停止本次回測",NeonUi.amber){WorkManager.getInstance(this).cancelUniqueWork("historical-backtest");store.update(store.active(),"已要求取消；原本已完成的報告保留","CANCELED");refreshStatus()})
        status=label("準備中",13f,NeonUi.ink);root.addView(status);root.addView(NeonUi.gap(this,14))
        results=NeonUi.vertical(this);root.addView(results)
        root.addView(NeonUi.button(this,"查看模型假設與規則"){assumptions()});refreshStatus()
    }
    override fun onResume(){super.onResume();handler.post(refresh)}
    override fun onPause(){handler.removeCallbacks(refresh);super.onPause()}
    private fun pick(first:Boolean){val d=if(first)start else end;val dialog=DatePickerDialog(this,{_,y,m,day->val date=LocalDate.of(y,m+1,day);if(first){start=date;startButton.text="起始 $start"}else{end=date;endButton.text="結束 $end"}},d.year,d.monthValue-1,d.dayOfMonth);dialog.datePicker.minDate=LocalDate.of(2016,1,1).atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli();dialog.datePicker.maxDate=today.minusDays(1).atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli();dialog.show()}
    private fun launch(){
        val cash=capital.text.toString().toDoubleOrNull();val raw=codes.text.toString().trim()
        if(start>end||start<LocalDate.of(2016,1,1)||end>=today||ChronoUnit.DAYS.between(start,end)>730){Toast.makeText(this,"請選擇有效日期，區間最多 2 年",Toast.LENGTH_LONG).show();return}
        if(cash==null||!cash.isFinite()||cash !in 50000.0..100000000.0){capital.error="本金限 50,000～100,000,000";return}
        if(raw.split(Regex("[,，\\s]+" )).filter{it.isNotBlank()}.any{!it.matches(Regex("[1-9][0-9]{3}"))}){codes.error="請輸入四碼股票代號，以逗號分隔";return}
        val id=UUID.randomUUID().toString();val s=BtSettings(start,end,cash,optimize.isChecked,raw)
        val data=workDataOf("id" to id,"start" to start.toString(),"end" to end.toString(),"capital" to cash,"optimize" to s.optimize,"codes" to raw)
        val request=OneTimeWorkRequestBuilder<BacktestWorker>().setInputData(data).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        // REPLACE also handles a process stopped before the old progress label was saved.
        displayed="";store.begin(id,s);WorkManager.getInstance(this).enqueueUniqueWork("historical-backtest",ExistingWorkPolicy.REPLACE,request);refreshStatus()
    }
    private fun refreshStatus(){
        status.text=store.status()
        runButton.text=if(store.state()=="RUNNING")"重新開始本次回測" else "開始自動回測"
        val id=store.resultId();if(id.isBlank()||displayed==id)return
        val data=store.result()?:return
        if(displayed!=id){displayed=id;showResult(data)}
    }
    internal fun showResult(data:JSONObject){
        results.removeAllViews();val s=data.getJSONObject("settings");val r=data.getJSONObject("run");val baseline=data.getJSONObject("baseline");val pnl=r.getDouble("profit")
        val folds=r.getJSONArray("folds");val curve=r.getJSONArray("curve")
        val panel=NeonUi.vertical(this).apply{background=NeonUi.panel(this@BacktestActivity,tint(pnl));setPadding(dp(14),dp(14),dp(14),dp(14))}
        panel.addView(label(if(folds.length()>0)"樣本外總損益" else "基準日線總損益",16f,NeonUi.ink,true))
        panel.addView(label(signed(pnl)+" 元",34f,tint(pnl),true))
        panel.addView(label("報酬率 ${String.format(Locale.US,"%+.2f",pnl/s.getDouble("capital")*100)}% · 初始 ${money(s.getDouble("capital"))}",12f))
        panel.addView(label("${curve.getJSONObject(0).getString("date")} → ${curve.getJSONObject(curve.length()-1).getString("date")}",12f))
        panel.addView(label("含期末持股估值、應收股息，扣買賣費稅及滑價；不是全數平倉的現金利潤。",11f))
        if(data.getString("id")!=store.active())results.addView(label("以下為上次已完成報告，本次尚無新結果。",12f,NeonUi.amber))
        results.addView(panel);results.addView(NeonUi.gap(this,8))
        results.addView(NeonUi.row(this,listOf(NeonUi.tile(this,"平倉勝率",if(r.isNull("winRate"))"—" else String.format(Locale.US,"%.1f%%",r.getDouble("winRate")),"${r.getInt("closed")} 筆平倉 · 價差損益",NeonUi.cyan),NeonUi.tile(this,"最大回撤",String.format(Locale.US,"%.2f%%",r.getDouble("drawdown")),"依每日淨資產",NeonUi.amber))))
        results.addView(label("已實現價差 ${signed(r.getDouble("realized"))} · 應收股息 ${money(r.getDouble("dividend"))}",12f))
        results.addView(label("同期間基準淨利 ${signed(baseline.getDouble("profit"))} · 差額 ${signed(pnl-baseline.getDouble("profit"))}",12f,NeonUi.ink))
        results.addView(label("期末留倉 ${r.getJSONArray("holdings").length()} 檔；缺失行情沿用最近價，日期見報告。",11f))
        results.addView(PaperEquityView(this,s.getDouble("capital"),(0 until curve.length()).map{curve.getJSONObject(it).getDouble("equity")}),LinearLayout.LayoutParams(-1,dp(150)))
        results.addView(label("資料涵蓋 ${data.getInt("loaded")} / ${data.getInt("requested")} 檔",16f,NeonUi.ink,true))
        results.addView(label(data.getString("note"),12f,NeonUi.amber));results.addView(NeonUi.gap(this,8))
        results.addView(label("自動優化紀錄  ${folds.length()} 輪",16f,NeonUi.ink,true))
        for(i in 0 until folds.length()){
            val f=folds.getJSONObject(i)
            results.addView(label("${f.getString("start")} 起使用 ${f.getString("params")}\n訓練 ${f.getString("trainStart")}～${f.getString("trainEnd")} · ${f.getInt("trials")} 組\n${f.getString("note")}",12f));results.addView(NeonUi.gap(this,8))
        }
        results.addView(NeonUi.button(this,"匯出完整回測報告與交易 CSV",NeonUi.mint){export(data)})
        results.addView(NeonUi.button(this,"查看缺失／排除清單"){val a=data.getJSONArray("excluded");MaterialAlertDialogBuilder(this).setTitle("缺失與排除 ${a.length()} 檔").setMessage(if(a.length()==0)"無" else (0 until minOf(100,a.length())).joinToString("\n"){a.getString(it)}+if(a.length()>100)"\n其餘請見完整報告" else "").setPositiveButton("關閉",null).show()})
    }
    private fun assumptions(){MaterialAlertDialogBuilder(this).setTitle("回測模型 v1").setMessage(
        "以現存股票名單研究歷史日線，非完整歷史成分股資料庫，存在存活偏差。未能核對拆併股原始價格者排除；這也會造成樣本偏差。\n\n"+
        "ABC 基礎技術規則採既有定義，全區只看前一交易日已收盤資料；C 的長紅爆量也須等次日開盤才進場。EPS、現金流、法人與未勾查核資料不套回過去。\n\n"+
        "每次買賣 1,000 股、最多 5 檔。以前日收盤判斷停損／停利，次日開盤成交；持有滿 5 個有效交易日出場。開盤距前收約 ±9.5% 時相應買賣不成交，以保守排除可能鎖漲跌停。不能重現盤中觸價或 13:20 價格；沒有排隊、部分成交模型。\n\n"+
        "買賣滑價各 0.1% 並依跳動單位取整；費率 0.1425% 最低 20 元，賣出稅 0.3%。股息於除息日列應收，不供再投資，未扣個人股息稅。期末持股以最後有效收盤價扣預估賣出費稅估值。\n\n"+
        "自動優化：前 60 個交易日只訓練，之後每 20 日重新訓練。3 組篩選（ABC原始／量≥1000且站20MA／量≥2000且站20MA且RSI>55）× 3 組停損停利（2/4、3/6、4/8%）。至少10筆平倉才參與挑選，以訓練淨報酬−0.5倍回撤評分，不只追求勝率。訓練本金每組相同；驗證期持股現金連續累積，不重置。\n\n"+
        "驗證成績獨立呈現，未拿驗證期挑參數。回測參數只作用於本次研究，不改實盤掃描器或自買自投的規則。重新挑日期反覆試驗仍可能過度擬合，不能保證提高勝率。"
    ).setPositiveButton("了解",null).show()}
    private fun export(data:JSONObject){runCatching{
        val dir=File(cacheDir,"paper").apply{mkdirs()};val id=data.getString("id").take(8)
        val json=File(dir,"回測報告_$id.json").apply{writeText(data.toString(2))}
        val a=data.getJSONObject("run").getJSONArray("trades")
        val fields=listOf("date","signalDate","code","name","radar","side","shares","price","fee","tax","pnl","reason")
        val lines=mutableListOf("成交日期(開盤),訊號日期,股號,名稱,區域,方向,股數,價格,手續費,交易稅,已實現價差,理由")
        for(i in 0 until a.length()){val t=a.getJSONObject(i);lines.add(fields.joinToString(","){"\""+t.get(it).toString().replace("\"","\"\"")+"\""})}
        val csv=File(dir,"回測交易_$id.csv").apply{writeText("\uFEFF"+lines.joinToString("\r\n"))}
        val uris=arrayListOf(FileProvider.getUriForFile(this,"$packageName.posters",json),FileProvider.getUriForFile(this,"$packageName.posters",csv))
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"儲存回測報告"))
    }.onFailure{Toast.makeText(this,"匯出失敗：${it.message}",Toast.LENGTH_LONG).show()}}
}
