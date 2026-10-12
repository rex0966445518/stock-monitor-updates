package com.rex.twboardingscanner.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rex.twboardingscanner.backtest.BacktestStore
import com.rex.twboardingscanner.backtest.BtRobotJournalSync
import com.rex.twboardingscanner.backtest.BtRobotStore
import com.rex.twboardingscanner.backtest.BtRobotWorker
import com.rex.twboardingscanner.data.StockPolicyStore
import com.rex.twboardingscanner.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object BacktestJournalUi {
    private val formatter=DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss").withZone(RuleMetrics.TAIPEI)
    fun time(value:Long)=if(value>0)formatter.format(Instant.ofEpochMilli(value)) else "舊版未記錄"
    fun state(value:String)=when(value){"DONE"->"已完成";"RUNNING"->"執行中";"CANCELED"->"已停止";"ERROR"->"失敗";else->"未完成"}
    private fun strings(a:JSONArray?)=if(a==null)emptyList() else (0 until a.length()).map{a.getString(it)}
    fun bannedCount(s:JSONObject):Int?{
        val banned=s.optJSONObject("stockPolicy")?.optJSONArray("banned")?:return null
        return runCatching{(0 until banned.length()).map{i->banned.getString(i).trim().also{require(it.matches(Regex("[1-9][0-9]{3}")))}}.toSet().size}.getOrNull()
    }
    fun tradingSummary(s:JSONObject):String {
        val version=s.optInt("strategyVersion",1)
        if(version<2)return "交易限制依原始舊版報告"
        val limit=if(version>=4&&!s.isNull("maxHoldingStocks"))"最高持倉 ${s.optInt("maxHoldingStocks")} 檔" else if(version<4)"持倉檔數不限" else "持倉上限未記錄"
        val target=s.optDouble("targetNetPct",if(version<4)3.0 else Double.NaN)
        val profit=if(target.isFinite())"獲利賣出 ${com.rex.twboardingscanner.backtest.btPercent(target)}%（扣費稅）" else "獲利目標未記錄"
        return "$limit · $profit" + (if(s.optBoolean("holdProfitUntilMa5Break",false))" · RSI>50／5MA 達標續抱" else "")
    }
    fun describe(s:JSONObject):String {
        val rules=s.optJSONObject("rules");val labels=s.optJSONObject("ruleLabels")
        val sectors=strings(s.optJSONArray("sectorLabels")).ifEmpty{strings(s.optJSONArray("sectors")).map{name->StockSector.entries.firstOrNull{it.name==name}?.label?:name}}
        val intro="回測 ${s.optString("start","未記錄")} → ${s.optString("end","未記錄")}\n"+
            "初始本金 ${String.format(Locale.TAIWAN,"%,.0f",s.optDouble("capital",0.0))} 元\n"+
            "股票 ${s.optString("codes").ifBlank{"全部"}}\n產業 ${sectors.joinToString("、").ifBlank{"舊版未記錄"}}\n"+
            "${tradingSummary(s)}\n"+
            "${s.optString("marketGuardSummary","此舊日誌未啟用大盤保護")}\n"+
            com.rex.twboardingscanner.domain.StockPolicy.read(s.optJSONObject("stockPolicy")).let{p->p.summary()+"\n禁股："+p.banned.sorted().joinToString("、").ifBlank{"無"}+"\n放行："+p.overrides.entries.joinToString("；"){"${it.key.label} ${it.value.sorted().joinToString("、")}"}.ifBlank{"無"}+"\n"}+
            "${s.optString("strategyLabel","舊版策略，請參考原始報告")}\n"+
            com.rex.twboardingscanner.domain.ExitRules.summary(com.rex.twboardingscanner.domain.ExitRules.read(s.optJSONArray("exitRules")))+"\n"+
            "條件版本 ${s.optString("rulesVersion","舊版未記錄")}"
        if(rules==null)return intro+"\n\n舊版沒有完整 ABC 勾選快照。"
        val body=RadarType.entries.joinToString("\n\n"){type->
            val ids=strings(rules.optJSONArray(type.name))
            val items=if(labels?.has(type.name)==true)strings(labels.getJSONArray(type.name)) else ids.map{id->ScanConditions.forRadar(type).firstOrNull{it.id==id}?.label?:"條件代碼 $id"}
            "${type.name.take(1)} 區 · ${ids.size} 項\n"+if(ids.isEmpty())"未選條件，該區不入選" else items.joinToString("\n"){"✓ $it"}
        }
        val excluded=com.rex.twboardingscanner.domain.ExclusionConditions.read(s.optJSONObject("exclusions"))
        val exclusionText="\n\n排除區：\n"+excluded.entries.joinToString("\n"){(type,ids)->"${type.name.take(1)}："+if(ids.isEmpty())"未勾選" else com.rex.twboardingscanner.domain.ExclusionConditions.forRadar(type).filter{it.id in ids}.joinToString("；"){it.label}}
        val costs=s.optJSONObject("costModel")?.let{"\n\n買／賣手續費各 ${it.optDouble("buyFeeRate")*100}%／${it.optDouble("sellFeeRate")*100}%（最低 ${it.optInt("minimumFee")} 元）\n賣出稅 ${it.optDouble("sellTaxRate")*100}% · 滑價 ${it.optDouble("slippageRate")*100}%"}.orEmpty()
        return intro+"\n\n"+body+exclusionText+costs+(if(labels==null)"\n\n舊版僅保存條件代碼；上列名稱依目前版本對照。" else "")
    }
}

class BacktestJournalActivity:AppCompatActivity(){
    private lateinit var entries:LinearLayout
    private lateinit var status:TextView
    private var rows:List<JSONObject> = emptyList()
    private var shown=20
    private val expandedDiffs=mutableSetOf<String>()
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val root=NeonUi.vertical(this).apply{setPadding(NeonUi.dp(this@BacktestJournalActivity,16),NeonUi.dp(this@BacktestJournalActivity,16),NeonUi.dp(this@BacktestJournalActivity,16),NeonUi.dp(this@BacktestJournalActivity,28))}
        val scroll=ScrollView(this).apply{setBackgroundColor(NeonUi.canvas);addView(root)};setContentView(scroll)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll){v,insets->val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets}
        root.addView(NeonUi.header(this,"回測日誌","STRATEGY ARCHIVE"){finish()})
        root.addView(NeonUi.label(this,"每次獨立保存 · 條件快照 · 完整買賣紀錄",12f,NeonUi.cyan))
        root.addView(NeonUi.button(this,"匯入回測日誌 JSON"){
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),704)
        })
        root.addView(NeonUi.gap(this,10))
        status=NeonUi.label(this,"讀取日誌中…",12f);root.addView(status)
        root.addView(NeonUi.gap(this,12));entries=NeonUi.vertical(this);root.addView(entries)
    }
    override fun onResume(){super.onResume();reload()}
    private fun reload(){Thread{
        val loaded=runCatching{BacktestStore(this).history()}
        runOnUiThread{if(!isFinishing&&!isDestroyed)loaded.fold({renderEntries(it)},{status.text="日誌讀取失敗：${it.message}"})}
    }.start()}
    @Deprecated("Activity result compatibility")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        val uri=data?.data?:return
        if(requestCode!=704||resultCode!=RESULT_OK)return
        status.text="正在匯入日誌…"
        Thread{
            val result=runCatching{
                val text=contentResolver.openInputStream(uri)!!.use{input->
                    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=32*1024*1024){"JSON 超過 32 MB"};out.write(buffer,0,n)}
                    out.toString("UTF-8").removePrefix("\uFEFF")
                }
                BacktestStore(this).importReport(JSONObject(text))
            }
            runOnUiThread{if(!isFinishing&&!isDestroyed)result.fold({reload();android.widget.Toast.makeText(this,"已匯入獨立日誌，原始績效保留",android.widget.Toast.LENGTH_LONG).show()},{status.text="匯入失敗：請選擇匯出的完整回測報告 JSON。${it.message}"})}
        }.start()
    }
    private fun syncToRobot(id:String,row:JSONObject){
        val desired=runCatching{BtRobotJournalSync.fromJournal(row)}.getOrElse{
            android.widget.Toast.makeText(this,"無法同步：${it.message}",android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val current=StockPolicyStore(this).read()
        val policyChange=current!=desired.stockPolicy
        val explanation="將本次日誌的 ABCDE 勾選與啟用開關、D／E 數值門檻、各區排除規則、產業、股號範圍、日期、本金、持倉、獲利、5 組下車條件和大盤保護帶入機器人新批次。\n\n"+
            "已勾選條件同步為必選，未勾選條件保持自由測試；不沿用舊批次的排除條件。原回測績效與舊機器人測試紀錄保持不變。切換後按「開始測試」才會運算。"+
            if(policyChange)"\n\n注意：日誌的禁買／限價名單與目前設定不同；確認同步會同步更新三區共用名單，並暫停目前測試。" else
                "\n\n本次禁買／限價與目前共用名單一致。"
        MaterialAlertDialogBuilder(this)
            .setTitle("同步至自動機器人 · ${id.take(8)}")
            .setMessage(explanation)
            .setNegativeButton("取消",null)
            .setPositiveButton("同步並前往機器人"){_,_->
                status.text="正在同步日誌 ${id.take(8)} 的條件快照…"
                Thread{
                    val result=runCatching{
                        val robot=BtRobotStore(this)
                        val old=robot.active()
                        if(old.isNotBlank())BtRobotWorker.pause(this,old)
                        val policy=StockPolicyStore(this)
                        if(policy.read()!=desired.stockPolicy)policy.change{desired.stockPolicy}
                        // Persist both the archived seed AND its mandatory ABCDE checks.
                        BtRobotJournalSync.createBatch(robot,desired,id)
                    }
                    runOnUiThread{if(!isFinishing&&!isDestroyed)result.fold({newId->
                        android.widget.Toast.makeText(this,"已套用日誌 ${id.take(8)}；按開始測試才執行",android.widget.Toast.LENGTH_LONG).show()
                        startActivity(Intent(this,BtRobotActivity::class.java).putExtra("syncedJournal",id).putExtra("robotSession",newId))
                    },{error->
                        status.text="同步失敗：${error.message}"
                        android.widget.Toast.makeText(this,"同步失敗：${error.message}",android.widget.Toast.LENGTH_LONG).show()
                    })}
                }.start()
            }.show()
    }
    internal fun renderEntries(data:List<JSONObject>){
        rows=data.sortedWith(compareByDescending<JSONObject>{it.optLong("startedAt").takeIf{t->t>0}?:it.optLong("finishedAt")}.thenByDescending{it.optString("id")})
        expandedDiffs.retainAll(rows.map{it.optString("id")}.toSet());shown=20;renderPage()
    }
    private fun renderPage(){
        entries.removeAllViews();status.text="共 ${rows.size} 次回測 · 新到舊排列"
        if(rows.isEmpty()){entries.addView(NeonUi.empty(this,"尚無回測紀錄","開始一次回測後，條件快照、執行狀態與交易結果會自動保存在這裡。"));return}
        rows.take(shown).forEachIndexed{index,row->
            val id=row.getString("id");val s=row.optJSONObject("settings")?:JSONObject();val complete=row.optString("state")=="DONE"
            val pnl=row.optDouble("profit",0.0);val color=if(!complete)NeonUi.amber else if(pnl>=0)NeonUi.pink else NeonUi.mint
            val panel=NeonUi.vertical(this).apply{background=NeonUi.panel(this@BacktestJournalActivity,color);setPadding(NeonUi.dp(this@BacktestJournalActivity,12),NeonUi.dp(this@BacktestJournalActivity,12),NeonUi.dp(this@BacktestJournalActivity,12),NeonUi.dp(this@BacktestJournalActivity,12))}
            val stamp=row.optLong("startedAt").takeIf{it>0}?:row.optLong("finishedAt")
            panel.addView(NeonUi.label(this,BacktestJournalUi.state(row.optString("state"))+" · "+id.take(8),13f,color,true))
            panel.addView(NeonUi.label(this,BacktestJournalUi.time(stamp)+(if(row.optBoolean("legacyImported"))" · 舊版檔案時間" else " · 台北"),11f))
            panel.addView(NeonUi.label(this,"${s.optString("start","—")} → ${s.optString("end","—")}",15f,NeonUi.ink,true))
            val banned=BacktestJournalUi.bannedCount(s)
            panel.addView(NeonUi.label(this,banned?.let{"禁股 $it 檔"}?:"禁股數未記錄",13f,if(banned==null)NeonUi.muted else if(banned>0)NeonUi.amber else NeonUi.cyan,true).apply{
                tag="journal-banned-$id";contentDescription=banned?.let{"本次回測保存的禁股名單共 $it 檔"}?:"此筆舊日誌未保存禁股數量"
            })
            panel.addView(NeonUi.label(this,if(complete)String.format(Locale.TAIWAN,"%+,.0f 元",pnl) else "尚無完成績效",28f,color,true))
            if(complete)panel.addView(NeonUi.label(this,"買入 ${row.optInt("buys")} 張 · 賣出 ${row.optInt("closed")} 張 · 留倉 ${row.optInt("holdings")} 張",11f))
            panel.addView(NeonUi.label(this,"本金 ${String.format(Locale.TAIWAN,"%,.0f",s.optDouble("capital",0.0))} 元",12f))
            panel.addView(NeonUi.label(this,BacktestJournalUi.tradingSummary(s),12f,NeonUi.cyan))
            panel.addView(NeonUi.label(this,if(s.optInt("marketGuardVersion")>0)"大盤暴跌保護 · 已啟用" else "大盤保護 · 舊紀錄未啟用",11f,NeonUi.amber))
            val rules=s.optJSONObject("rules")
            panel.addView(NeonUi.label(this,if(rules==null)"舊版條件未完整記錄" else RadarType.entries.joinToString(" · "){"${it.name.take(1)} ${rules.optJSONArray(it.name)?.length()?:0} 項"},12f,NeonUi.cyan))
            panel.addView(BacktestDiffView(this,row,rows.getOrNull(index+1),id in expandedDiffs){open->if(open)expandedDiffs.add(id) else expandedDiffs.remove(id)})
            panel.addView(NeonUi.gap(this,8))
            panel.addView(NeonUi.row(this,listOf(NeonUi.button(this,"條件快照"){BtSnapshotDialog.show(this,s,"回測條件 · ${id.take(8)}")},NeonUi.button(this,"檢閱日誌",color){startActivity(Intent(this,BacktestActivity::class.java).putExtra("journalId",id))})))
            panel.addView(NeonUi.gap(this,6))
            panel.addView(NeonUi.primary(this,"同步至自動機器人"){syncToRobot(id,row)}.apply{
                tag="journal-sync-$id"
                isEnabled=complete
                contentDescription="將日誌 ${id.take(8)} 保存的完整條件套用到自動測試機器人"
            })
            entries.addView(panel);entries.addView(NeonUi.gap(this,10))
        }
        if(shown<rows.size)entries.addView(NeonUi.button(this,"載入更多（已顯示 $shown / ${rows.size}）"){shown+=20;renderPage()}.apply{tag="journal-load-more"})
    }
}
