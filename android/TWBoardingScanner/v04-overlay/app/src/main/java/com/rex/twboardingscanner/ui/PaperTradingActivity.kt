package com.rex.twboardingscanner.ui

import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rex.twboardingscanner.paper.*
import java.io.File
import java.time.format.DateTimeFormatter
import java.util.Locale

class PaperTradingActivity:AppCompatActivity() {
    private lateinit var repo:PaperRepository
    private lateinit var loop:PaperLoop
    private lateinit var content:LinearLayout
    private lateinit var scroll:ScrollView
    private var error:String?=null
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);repo=PaperRepository(this)
        scroll=ScrollView(this).apply{setBackgroundColor(NeonUi.canvas);isFillViewport=true}
        content=NeonUi.vertical(this).apply{setPadding(dp(16),dp(16),dp(16),dp(28))}
        scroll.addView(content);setContentView(scroll)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll){v,insets->
            val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets}
        loop=PaperLoop(this){error=it;render()};render()
    }
    override fun onResume(){super.onResume();loop.start()}
    override fun onPause(){loop.stop();super.onPause()}
    private fun dp(n:Int)=NeonUi.dp(this,n)
    private fun money(n:Double)=String.format(Locale.TAIWAN,"%,.0f",n)
    private fun signed(n:Double)=(if(n>=0)"+" else "−")+money(kotlin.math.abs(n))
    private fun stamp(n:Long)=if(n==0L)"尚未執行" else PaperEngine.local(n).format(DateTimeFormatter.ofPattern("MM/dd HH:mm:ss"))
    private fun color(n:Double)=if(n>=0)NeonUi.pink else NeonUi.mint
    private fun space(n:Int=12){content.addView(NeonUi.gap(this,n))}
    private fun label(text:String,size:Float=13f,color:Int=NeonUi.muted,bold:Boolean=false)=NeonUi.label(this,text,size,color,bold)
    private fun panel(accent:Int=NeonUi.blue)=NeonUi.vertical(this).apply{background=NeonUi.panel(this@PaperTradingActivity,accent);setPadding(dp(14),dp(14),dp(14),dp(14))}
    private fun render(){
        val y=scroll.scrollY;content.removeAllViews()
        content.addView(NeonUi.header(this,"自買自投","PAPER PORTFOLIO"){finish()})
        content.addView(label("模擬資金・不連接券商",12f,NeonUi.mint));space()

        val b=runCatching{repo.read()}.getOrElse{content.addView(label("帳本無法讀取，已停止操作：${it.message}",14f,NeonUi.pink));return}
        val equity=PaperEngine.equity(b);val pnl=equity-b.capital
        val header=panel(NeonUi.mint)
        header.addView(label(if(b.enabled)"● 自動策略已啟動" else "○ 自動策略已暫停",13f,if(b.enabled)NeonUi.mint else NeonUi.amber,true))
        header.addView(label(error?.let{"更新失敗：$it"}?:b.status,12f,NeonUi.ink))
        header.addView(label("台北時間 09:00–13:20  ·  最後檢查 ${stamp(b.lastRun)}",11f))

        val hero=panel(color(pnl))
        hero.addView(label("累計淨損益  TWD",12f))
        hero.addView(label(signed(pnl),36f,color(pnl),true))
        hero.addView(label("報酬率 ${String.format(Locale.US,"%+.2f",pnl/b.capital*100)}%  ·  已含成交成本及持股預估賣出費稅",11f))
        hero.addView(NeonUi.gap(this,10))
        hero.addView(NeonUi.row(this,listOf(NeonUi.tile(this,"淨資產",money(equity),"初始 ${money(b.capital)}",NeonUi.ink),NeonUi.tile(this,"可用資金",money(b.cash),"每筆固定 1,000 股",NeonUi.cyan))))
        content.addView(hero);space()
        content.addView(header);space()
        val sells=b.trades.filter{it.side=="SELL"};val wins=sells.count{it.realized>0}
        content.addView(NeonUi.row(this,listOf(
            NeonUi.tile(this,"已實現",signed(PaperEngine.realized(b)),"平倉 ${sells.size} 筆",color(PaperEngine.realized(b))),
            NeonUi.tile(this,"未實現",signed(PaperEngine.unrealized(b)),"持有 ${b.positions.size} / 5 檔",color(PaperEngine.unrealized(b))))))
        space(6)
        content.addView(label("平倉勝率 ${if(sells.isEmpty())"—" else "${wins*100/sells.size}%"}  ·  獲利 $wins / ${sells.size} 筆  ·  成交費稅 ${money(b.trades.sumOf{it.fee+it.tax})} 元",12f))
        space()
        val actions=NeonUi.row(this,listOf(NeonUi.primary(this,if(b.enabled)"暫停策略" else "啟動策略"){toggle(b)},NeonUi.button(this,"交易明細 CSV",NeonUi.muted){export(b)}))
        content.addView(actions,content.indexOfChild(header)+1)
        space()
        content.addView(label("資產走勢",17f,NeonUi.ink,true));space(6)
        content.addView(PaperEquityView(this,b.capital,b.days.map{it.equity}),LinearLayout.LayoutParams(-1,dp(140)))
        content.addView(label(if(b.days.isEmpty())"尚無交易時段紀錄，啟動後逐日累積。" else "${b.days.first().date} → ${b.days.last().date} · 每日最後一次有效檢查的淨資產",11f))
        space()
        content.addView(label("模擬持股  ${b.positions.size}",17f,NeonUi.ink,true));space(6)
        if(b.positions.isEmpty())content.addView(NeonUi.empty(this,"目前空手","啟動策略並完成主頁掃描後，等待符合規則的入場機會。"))
        b.positions.forEach {p->
            val gain=PaperEngine.netSell(p.mark)-p.cost
            val card=panel(color(gain))
            card.addView(label("${p.radar}  ${p.code}  ${p.name}",16f,NeonUi.ink,true))
            card.addView(label("1,000 股  ·  成交 ${p.entry}  →  最近價 ${p.mark}",13f))
            card.addView(label(signed(gain),25f,color(gain),true))
            card.addView(label("報價 ${stamp(p.markAt)}${if(System.currentTimeMillis()-p.markAt>90000) " · 非即時估值" else ""}",11f))
            content.addView(card);space(6)
        }
        space()
        val candidatesTitle=label("最新候選池  ${b.candidates.size} 檔",17f,NeonUi.ink,true);content.addView(candidatesTitle)
        content.addView(label("完成掃描 ${stamp(b.candidateAt)} · A ${b.candidates.count{it.radar=="A"}} / B ${b.candidates.count{it.radar=="B"}} / C ${b.candidates.count{it.radar=="C"}}",11f))
        val candidates=panel()
        candidates.addView(label(if(b.candidates.isEmpty())"請回主頁完成 ABC 掃描；未通過與待查核不會進場。" else b.candidates.take(12).joinToString("\n"){"${it.radar}  ${it.code} ${it.name}  ·  ${it.score}%"},13f,NeonUi.ink))
        if(b.candidates.size>12)candidates.addView(label("另有 ${b.candidates.size-12} 檔；每輪依通過率、股號排序檢查前 60 檔。",11f))
        content.addView(candidates);NeonUi.group(content,candidatesTitle,candidates,"候選股票 · ${b.candidates.size} 檔","依最近一次完成的掃描建立","paper-candidates");space()
        val tradesTitle=label("交易紀錄  ${b.trades.size} 筆",17f,NeonUi.ink,true);content.addView(tradesTitle);space(6)
        if(b.trades.isEmpty())content.addView(label("尚無成交。休市、過期或缺少報價時不產生模擬交易。",13f))
        b.trades.asReversed().take(50).forEach{t->
            val card=panel(if(t.side=="BUY")NeonUi.pink else NeonUi.cyan)
            card.addView(label("${if(t.side=="BUY")"買入" else "賣出"}  ${t.code} ${t.name}  ·  ${t.radar}",15f,NeonUi.ink,true))
            card.addView(label("1,000 股 × ${t.price}  ·  ${stamp(t.at)}",13f))
            if(t.side=="SELL")card.addView(label("淨損益 ${signed(t.realized)}",20f,color(t.realized),true))
            card.addView(label(t.reason,12f,NeonUi.ink))
            card.addView(label("手續費 ${money(t.fee)} / 稅 ${money(t.tax)}  ·  報價 ${stamp(t.quoteAt)}",11f))
            content.addView(card);space(6)
        }
        if(b.trades.size>50)content.addView(label("顯示最近 50 筆；CSV 包含完整成交紀錄。"))
        space()
        NeonUi.group(content,tradesTitle,content.getChildAt(content.childCount-1),"交易紀錄 · ${b.trades.size} 筆","逐筆成交、費稅與出場理由","paper-trades")
        content.addView(NeonUi.button(this,"前往歷史回測",NeonUi.blue){startActivity(Intent(this,BacktestActivity::class.java))})
        content.addView(NeonUi.button(this,"查看自動策略與成交規則",NeonUi.amber){rules()})
        content.addView(label("前景每 30 秒；背景約每 15 分鐘（省電可能延後）。強制停止／離線期間不交易、不補單。持股估值會顯示原始報價時間。",11f))
        scroll.post{scroll.scrollTo(0,y)}
    }
    private fun toggle(b:PaperBook){
        if(b.enabled){
            MaterialAlertDialogBuilder(this).setTitle("暫停自動模擬？").setMessage("暫停後不再自動買賣，已有持股和紀錄會保留，直到再次啟動。").setNegativeButton("取消",null).setPositiveButton("暫停"){_,_->runCatching{repo.setEnabled(false);PaperWorker.cancel(this)}.onFailure{error=it.message};render()}.show();return
        }
        val body=NeonUi.vertical(this).apply{setPadding(dp(20),dp(8),dp(20),dp(8))}
        body.addView(label("策略 v1 · 每股 1,000 股，最多 5 檔\n停損 3% / 停利 6% / 13:15 嘗試平倉\n13:20 停止買賣，未平倉持股保留。",14f,NeonUi.ink))
        val input=EditText(this).apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER;setText(b.capital.toLong().toString());hint="模擬本金 TWD";isEnabled=b.trades.isEmpty()}
        body.addView(input);body.addView(label(if(b.trades.isEmpty())"本金可設 50,000～100,000,000 元。" else "已有成交，本金固定以維持績效可比性。",11f))
        val dialog=MaterialAlertDialogBuilder(this).setTitle("啟動自買自投").setView(body).setNegativeButton("取消",null).setPositiveButton("啟動",null).create()
        dialog.setOnShowListener{dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val cap=input.text.toString().toDoubleOrNull()
            if(cap==null||cap !in 50000.0..100000000.0){input.error="請輸入有效本金";return@setOnClickListener}
            runCatching{repo.setEnabled(true,cap);PaperWorker.schedule(this)}.onSuccess{dialog.dismiss();loop.stop();loop.start();render()}.onFailure{Toast.makeText(this,it.message,Toast.LENGTH_LONG).show()}
        }};dialog.show()
    }
    private fun rules(){MaterialAlertDialogBuilder(this).setTitle("自動策略 v1").setMessage(
        "這是固定規則自動模擬，並非語言模型預測。\n\n"+
        "候選：最新一次完整 ABC 掃描，所有已勾條件通過；同股跨區只買一次，依通過率、股號排序。清單保存至下次掃描，超過 7 天停止新買。背景不重跑全市場掃描。\n\n"+
        "進場：台北週一～週五 09:00–13:15，必須取得當日 90 秒內且晚於訊號的真實成交報價；至少 500 張、股價 50 元、賣一至少 1 張、現金充足。同股每日最多買一次、同時最多 5 檔。休市無當日报價不成交。\n\n"+
        "出場：跌 3%、漲 6%，或曾漲 3% 後由峰值回落 2%；13:15 起嘗試賣出全部持股。買一須至少 1 張。13:20 起嚴禁所有成交；無法平倉時留倉。\n\n"+
        "成本：買賣手續費各 0.1425%，最低 20 元；賣出稅保守採 0.3%（不套當沖優惠）。買賣一再加入不利滑價 0.1%，按股票跳動單位取整；超出漲跌停價不成交。每筆固定 1,000 股。\n\n"+
        "績效：總損益＝現金＋持股預估賣出淨值−初始本金；未實現含預估賣出費稅。使用取樣報價，不重建漏掉的盤中行情；未含股息、除權息與拆併股調整，亦不模擬真實排隊及市場衝擊。\n\n"+
        "前景 30 秒、背景約 15 分鐘檢查，受 Android 排程／省電影響。請看最後執行時間；若需穩定盤中監控，交易時保持本 App 在前景。模擬結果用於比較策略，不代表實際成交收益。"
    ).setPositiveButton("了解",null).show()}
    private fun export(b:PaperBook){
        runCatching {
            fun cell(v:Any)="\""+v.toString().replace("\"","\"\"")+"\""
            val rows=mutableListOf("策略,台北成交時間,報價時間,股號,名稱,區域,方向,股數,成交價,手續費,交易稅,已實現損益,理由")
            b.trades.forEach{t->rows.add(listOf("v1",PaperEngine.local(t.at).toString(),PaperEngine.local(t.quoteAt).toString(),t.code,t.name,t.radar,t.side,t.shares,t.price,t.fee,t.tax,t.realized,t.reason).joinToString(","){cell(it)})}
            val file=File(cacheDir,"paper/自買自投_${java.time.LocalDate.now(TAIPEI)}.csv");file.parentFile?.mkdirs();file.writeText("\uFEFF"+rows.joinToString("\r\n"))
            val uri=FileProvider.getUriForFile(this,"$packageName.posters",file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"儲存或分享交易明細"))
        }.onFailure{Toast.makeText(this,"匯出失敗：${it.message}",Toast.LENGTH_LONG).show()}
    }
}

internal class PaperEquityView(c:android.content.Context,private val capital:Double,private val values:List<Double>):View(c){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas:Canvas){super.onDraw(canvas)
        val pad=NeonUi.dp(context,14).toFloat();val pts=listOf(capital)+values
        val low=(pts.minOrNull()?:capital)*.999;val high=(pts.maxOrNull()?:capital)*1.001
        paint.color=NeonUi.surface;canvas.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),pad,pad,paint)
        paint.color=NeonUi.border;paint.strokeWidth=1f
        for(i in 1..3)canvas.drawLine(pad,height*i/4f,width-pad,height*i/4f,paint)
        val path=Path();pts.forEachIndexed{i,v->val x=pad+i*(width-2*pad)/(pts.size-1).coerceAtLeast(1);val y=height-pad-((v-low)/(high-low)*(height-2*pad)).toFloat();if(i==0)path.moveTo(x,y)else path.lineTo(x,y)}
        paint.style=Paint.Style.STROKE;paint.strokeWidth=NeonUi.dp(context,2).toFloat();paint.color=NeonUi.mint;canvas.drawPath(path,paint);paint.style=Paint.Style.FILL
        if(values.isEmpty()){paint.textSize=NeonUi.dp(context,13).toFloat();paint.color=NeonUi.muted;canvas.drawText("等待第一筆有效紀錄",pad,height/2f,paint)}
    }
}
