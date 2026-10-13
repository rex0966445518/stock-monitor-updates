package com.rex.twboardingscanner.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rex.twboardingscanner.backtest.*
import com.rex.twboardingscanner.data.StockPolicyStore
import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.paper.*
import java.time.LocalDate

/**
 * Paper portfolio now exposes the SAME selectable backtest fields.
 * Past dates do NOT replay real-time quotes: they are a live activation window.
 * The existing book is never reset when the user changes strategy parameters.
 */
class PaperStrategyActivity:AppCompatActivity() {
    private lateinit var repo:PaperRepository
    private lateinit var book:PaperBook
    private lateinit var preset:BtSettings
    private lateinit var root:LinearLayout
    private lateinit var startDateButton:com.google.android.material.button.MaterialButton
    private lateinit var endDateButton:com.google.android.material.button.MaterialButton
    private lateinit var capital:EditText
    private lateinit var maxHoldings:EditText
    private lateinit var targetNet:EditText
    private lateinit var trendHoldCheck:CheckBox
    private lateinit var codes:EditText
    private lateinit var sectorButton:com.google.android.material.button.MaterialButton
    private lateinit var policySummary:TextView
    private lateinit var exitButton:com.google.android.material.button.MaterialButton
    private val radarButtons=mutableMapOf<RadarType,com.google.android.material.button.MaterialButton>()
    private var start=LocalDate.now(RuleMetrics.TAIPEI)
    private var end=start.plusDays(365)
    private var sectors:Set<StockSector> = StockSector.entries.toSet()
    private var rules:Map<RadarType,Set<String>> = defaultBtRules()
    private var exitRules:List<ExitRule> = emptyList()
    private fun dp(v:Int)=NeonUi.dp(this,v)
    private fun label(v:String,size:Float=12f,color:Int=NeonUi.muted,bold:Boolean=false)=NeonUi.label(this,v,size,color,bold)
    private fun section(title:String,subtitle:String="")=NeonUi.section(this,title,subtitle)
    private fun input(value:String,hint:String,decimal:Boolean=false):EditText=NeonUi.field(EditText(this).apply{
        setText(value);this.hint=hint;setSelectAllOnFocus(true)
        inputType=InputType.TYPE_CLASS_NUMBER or (if(decimal)InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
    })
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        repo=PaperRepository(this);book=repo.read()
        preset=book.strategy?:PaperStrategyConfig.defaults(this,book.capital)
        loadDraft(preset,false)
        val scroll=ScrollView(this).apply{setBackgroundColor(NeonUi.canvas)}
        root=NeonUi.vertical(this).apply{setPadding(dp(16),dp(16),dp(16),dp(32))}
        scroll.addView(root);setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll){v,i->
            val bars=i.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom);i
        }
        root.addView(NeonUi.header(this,"自買自投設定","PAPER STRATEGY",
            "模擬盤中買賣；依歷史回測相同欄位設定，僅模擬、不連券商"){finish()})
        val period=section("01 交易日期與模擬本金","自買自投使用當日可取得的報價，不會重新播放歷史行情。")
        startDateButton=NeonUi.button(this,""){pickDate(true)}.apply{tag="paper-start-date"}
        endDateButton=NeonUi.button(this,""){pickDate(false)}.apply{tag="paper-end-date"}
        period.addView(NeonUi.row(this,listOf(startDateButton,endDateButton)))
        period.addView(NeonUi.gap(this,7))
        period.addView(label("日期是自動買入的生效期間（最多 730 天），超過結束日不再新增持股；既有持股仍可按賣出條件處理。",11f,NeonUi.amber))
        period.addView(NeonUi.gap(this,10))
        period.addView(label("模擬本金（元）",13f,NeonUi.ink,true))
        capital=input(book.capital.toLong().toString(),"50,000～100,000,000")
            .apply{tag="paper-capital";isEnabled=book.trades.isEmpty()}
        period.addView(capital)
        if(book.trades.isNotEmpty())period.addView(label("已有交易紀錄，為維持原始收益計算，本金不可修改；其他條件可自行調整。",11f,NeonUi.amber))
        root.addView(period);root.addView(NeonUi.gap(this,12))
        val trade=section("02 持倉與賣出條件","自訂最高持倉、扣費稅淨利目標、5 組認賠條件。")
        val first=NeonUi.vertical(this)
        val second=NeonUi.vertical(this)
        first.addView(label("最高持倉檔數",12f,NeonUi.cyan,true))
        maxHoldings=input((preset.maxHoldingStocks?:5).toString(),"1 以上整數").apply{tag="paper-max-holdings"}
        first.addView(maxHoldings)
        second.addView(label("淨利下車（%）",12f,NeonUi.pink,true))
        targetNet=input(btPercent(preset.targetNetPct),"0.01～1000",true).apply{tag="paper-net-target"}
        second.addView(targetNet)
        trade.addView(NeonUi.row(this,listOf(first,second)))
        trendHoldCheck=CheckBox(this).apply{
            tag="paper-profit-trend-hold";textSize=13f;setTextColor(NeonUi.cyan)
            text="淨利達標且 RSI(14)>50／日K未跌破5MA 續抱；收盤跌破5MA 後依有效報價賣出"
            isChecked=preset.holdProfitUntilMa5Break
        }
        trade.addView(trendHoldCheck)
        exitButton=NeonUi.button(this,"",NeonUi.amber){
            ExitRulesEditor.show(this,"自買自投",exitRules){selected->
                exitRules=selected
                exitButton.text="下車賣出條件 · ${selected.count{it.active}} / 6 項"
            }
        }.apply{tag="paper-exit-rules"}
        trade.addView(exitButton)
        trade.addView(label("新自訂策略採用隔日起扣費稅淨利出場；留倉超過 5 個日曆日採保本條件，或依勾選的認賠規則賣出。不會在 13:15 強制清倉。",11f))
        root.addView(trade);root.addView(NeonUi.gap(this,12))
        val universe=section("03 股票範圍與 ABCDEF 入場篩查",
            "與歷史回測相同勾選條件，獨立於主頁篩查器保存。")
        universe.addView(label("股票代號（留空為所有股票）",12f,NeonUi.ink,true))
        codes=NeonUi.field(EditText(this).apply{
            setText(preset.codes);hint="全部，或 2330, 3661…";tag="paper-stock-codes"
        })
        universe.addView(codes)
        sectorButton=NeonUi.button(this,""){editSectors()}.apply{tag="paper-sectors"}
        universe.addView(sectorButton)
        universe.addView(label("A／B／C／D／E／F 勾選條件（可全部取消一區）",13f,NeonUi.ink,true))
        val choices=RadarType.entries.map{type->
            NeonUi.button(this,"",when(type){
                RadarType.A_EARLY_BREAKOUT->NeonUi.pink
                RadarType.B_DEEP_REVERSAL->NeonUi.amber
                else->NeonUi.cyan
            }){editRules(type)}.also{radarButtons[type]=it;it.tag="paper-rules-${type.name}"}
        }
        universe.addView(NeonUi.row(this,choices))
        universe.addView(label("保存設定後，請回主頁重新執行全市場掃描，才會依這套獨立條件更新候選股。未取得的財報／法人資料一律視為未通過。",11f,NeonUi.amber))
        root.addView(universe);root.addView(NeonUi.gap(this,12))
        val policy=section("04 禁股／限價與大盤保護","沿用主頁與自買自投共用禁股、限價與放行名單。")
        policySummary=label("",12f,NeonUi.cyan,true).apply{tag="paper-policy-summary"}
        policy.addView(policySummary)
        policy.addView(NeonUi.row(this,listOf(
            NeonUi.button(this,"禁股名單",NeonUi.pink){StockToolsActivity.open(this,"BAN",StockScope.SCANNER)},
            NeonUi.button(this,"限價名單",NeonUi.amber){StockToolsActivity.open(this,"LIMIT",StockScope.SCANNER)},
            NeonUi.button(this,"搜尋股票",NeonUi.cyan){StockToolsActivity.open(this,"SEARCH",StockScope.SCANNER)}
        )))
        policy.addView(label("大盤暴跌保護始終生效：未確認市場安全時停止新買入，既有持倉仍可賣出。",11f))
        root.addView(policy);root.addView(NeonUi.gap(this,14))
        root.addView(NeonUi.primary(this,"儲存自買自投策略"){saveStrategy()}.apply{tag="paper-save-strategy"})
        root.addView(NeonUi.gap(this,9))
        root.addView(NeonUi.button(this,"從歷史回測載入條件"){importBacktestDraft()}.apply{tag="paper-load-backtest"})
        root.addView(NeonUi.gap(this,7))
        root.addView(label("儲存後策略會先暫停，舊持股、成交及已實現損益完整保留。更新候選池後再自行啟動。與歷史回測不同，這裡僅使用收到的盤中報價，不補成交、未計歷史回測的每月折讓金。",11f))
        refreshControls()
    }
    override fun onResume(){
        super.onResume()
        if(::policySummary.isInitialized)refreshPolicy()
    }
    private fun refreshPolicy(){
        policySummary.text=com.rex.twboardingscanner.data.StockPolicyStore(this).read().summary()
    }
    private fun refreshControls(){
        startDateButton.text="起始日\n$start";endDateButton.text="結束日\n$end"
        exitButton.text="下車賣出條件 · ${exitRules.count{it.active}} / 6 項"
        sectorButton.text="產業篩選 · ${sectors.size} 類"
        radarButtons.forEach{(type,button)->button.text="${type.name.take(1)} ${if(type in preset.enabledRadars)"●" else "○"} · ${rules[type].orEmpty().size} 項"}
        refreshPolicy()
    }
    private fun loadDraft(s:BtSettings,show:Boolean=true){
        preset=s;start=s.start;end=s.end;rules=s.rules.mapValues{it.value.toSet()}
        sectors=s.sectors.toSet();exitRules=s.exitRules.toList()
        if(show){
            capital.setText(if(book.trades.isEmpty())btPercent(s.capital) else btPercent(book.capital))
            maxHoldings.setText((s.maxHoldingStocks?:5).toString())
            targetNet.setText(btPercent(s.targetNetPct))
            trendHoldCheck.isChecked=s.holdProfitUntilMa5Break
            codes.setText(s.codes)
            refreshControls()
        }
    }
    private fun pickDate(first:Boolean){
        val current=if(first)start else end
        val picker=DatePickerDialog(this,{_,y,m,d->
            val date=LocalDate.of(y,m+1,d)
            if(first)start=date else end=date
            refreshControls()
        },current.year,current.monthValue-1,current.dayOfMonth)
        picker.datePicker.minDate=LocalDate.of(2016,1,1).atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli()
        picker.datePicker.maxDate=LocalDate.now(RuleMetrics.TAIPEI).plusYears(3).atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli()
        picker.show()
    }
    private fun editRules(type:RadarType){
        val entries=ScanConditions.forRadar(type)
        val body=NeonUi.vertical(this).apply{setPadding(dp(15),dp(10),dp(15),dp(10))}
        if(type==RadarType.E_DIF_TOUCH)body.addView(NeonUi.button(this,"E 線距門檻與剛交叉設定",NeonUi.pink){
            EPatternEditor.show(this,preset.eConfig){cfg->preset=preset.copy(eConfig=cfg)}
        }.apply{tag="paper-e-parameters"})
        if(type==RadarType.F_VOLUME_CONTRACTION)body.addView(NeonUi.button(this,"F 縮量上漲 · 天數與門檻",NeonUi.pink){
            FPatternEditor.show(this,preset.fConfig){cfg->preset=preset.copy(fConfig=cfg)}
        }.apply{tag="paper-e-parameters"})
        body.addView(label("同區勾選條件均須符合，資料待查核不得通過。",12f))
        val enabled=CheckBox(this).apply{text="啟用 ${type.name.take(1)} 區入場分析";setTextColor(NeonUi.cyan);isChecked=type in preset.enabledRadars;tag="paper-enabled-${type.name}"}
        body.addView(enabled)
        val boxes=entries.map{cond->
            CheckBox(this).apply{
                text=cond.label;textSize=13f;setTextColor(NeonUi.ink)
                isChecked=cond.id in rules[type].orEmpty();minHeight=dp(48)
            }.also{body.addView(it)}
        }
        val readExclusions=ExclusionEditor.fields(this,body,type,preset.exclusions.getValue(type))
        val controls=NeonUi.row(this,listOf(
            NeonUi.button(this,"全不選"){boxes.forEach{it.isChecked=false}},
            NeonUi.button(this,"恢復預設"){boxes.forEachIndexed{i,cb->cb.isChecked=entries[i].defaultEnabled}}
        ))
        body.addView(controls)
        val scroller=ScrollView(this).apply{addView(body)}
        MaterialAlertDialogBuilder(this).setTitle("${type.name.take(1)} 區模擬入場條件")
            .setView(scroller).setNegativeButton("取消",null)
            .setPositiveButton("套用"){_,_->
                preset=preset.copy(exclusions=preset.exclusions+mapOf(type to readExclusions()),enabledRadars=if(enabled.isChecked)preset.enabledRadars+type else preset.enabledRadars-type)
                rules=rules+mapOf(type to entries.filterIndexed{i,_->boxes[i].isChecked}.map{it.id}.toSet())
                refreshControls()
            }.show()
    }
    private fun editSectors(){
        val all=StockSector.entries
        val body=NeonUi.vertical(this).apply{setPadding(dp(15),dp(8),dp(15),dp(8))}
        val boxes=all.map{sector->
            CheckBox(this).apply{text=sector.label;textSize=14f;setTextColor(NeonUi.ink)
                isChecked=sector in sectors;minHeight=dp(48)}.also{body.addView(it)}
        }
        body.addView(NeonUi.button(this,"全選產業"){boxes.forEach{it.isChecked=true}})
        MaterialAlertDialogBuilder(this).setTitle("自買自投產業篩選")
            .setView(ScrollView(this).apply{addView(body)}).setNegativeButton("取消",null)
            .setPositiveButton("套用"){_,_->
                sectors=all.filterIndexed{i,_->boxes[i].isChecked}.toSet();refreshControls()
            }.show()
    }
    private fun importBacktestDraft(){
        val historical=runCatching{BtSettingsCodec.decode(BacktestStore(this).configuration()!!)}.getOrNull()
        if(historical==null){Toast.makeText(this,"找不到可載入的歷史回測設定，請先在歷史回測儲存條件",Toast.LENGTH_LONG).show();return}
        MaterialAlertDialogBuilder(this).setTitle("套用目前歷史回測設定？")
            .setMessage("將載入回測的 ABCDEF、產業、股號、日期、持倉上限、獲利及下車條件。禁買／限價仍讀取三區共用名單。\n\n請留意：自買自投不是歷史重播，若匯入的結束日已過去，將不會新增模擬買入。")
            .setNegativeButton("取消",null).setPositiveButton("載入"){_,_->
                loadDraft(historical.copy(stockScope=StockScope.SCANNER,
                    capital=if(book.trades.isEmpty())historical.capital else book.capital),true)
            }.show()
    }
    internal fun capture():BtSettings{
        val cash=capital.text.toString().replace(",","").trim().toDoubleOrNull()
            ?:throw IllegalArgumentException("請填寫有效模擬本金")
        val max=maxHoldings.text.toString().trim().toIntOrNull()
            ?:throw IllegalArgumentException("持倉上限請填正整數")
        val target=targetNet.text.toString().trim().toDoubleOrNull()
            ?:throw IllegalArgumentException("淨利下車請填數字百分比")
        return preset.copy(
            start=start,end=end,capital=cash,codes=codes.text.toString().trim(),
            rules=rules.mapValues{it.value.toSet()},sectors=sectors.toSet(),
            maxHoldingStocks=max,targetNetPct=target,exitRules=exitRules.toList(),
            holdProfitUntilMa5Break=trendHoldCheck.isChecked,
            stockPolicy=StockPolicyStore(this).read(),stockScope=StockScope.SCANNER,
            marketGuardVersion=MarketCrashGuard.VERSION
        ).also{PaperStrategyConfig.validate(it)}
    }
    private fun saveStrategy(){
        val proposed=runCatching{capture()}.getOrElse{
            Toast.makeText(this,"設定未儲存：${it.message}",Toast.LENGTH_LONG).show();return
        }
        val confirm=if(repo.read().trades.isNotEmpty())"原有成交與持倉完全保留，設定在下次開始後套用。" else "尚無成交，設定本金將成為模擬帳本初始資金。"
        MaterialAlertDialogBuilder(this).setTitle("儲存自買自投策略")
            .setMessage("$confirm\n\n將暫停模擬策略並清空舊候選池。請回主頁完成一次全市場掃描，再啟動新策略；不會自動產生任何真實交易。")
            .setNegativeButton("取消",null)
            .setPositiveButton("儲存"){_,_->
                runCatching{repo.saveStrategy(proposed);PaperWorker.cancel(this)}
                    .onSuccess{Toast.makeText(this,"自訂條件已保存；請重新掃描並啟動模擬策略",Toast.LENGTH_LONG).show();finish()}
                    .onFailure{Toast.makeText(this,"儲存失敗：${it.message}",Toast.LENGTH_LONG).show()}
            }.show()
    }
}
